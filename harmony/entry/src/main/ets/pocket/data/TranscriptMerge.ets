import { ChatMessage } from './Repository';

/**
 * 全量历史回放与本地消息流的合并（ArkTS 移植 Android TranscriptMerge.kt 的简化版）。
 *
 * 为什么不能 clear+addAll 盲目替换：daemon 每次（重）连都会回放历史，且回放是
 * 截尾的（~100 行）。盲目替换会 (a) 丢掉窗口之前的本地 scrollback、(b) 把还没进
 * transcript 的排队气泡永远抹掉、(c) 打断正在流式增长的泡泡。
 *
 * 规则（始终保序）：
 *  - 空回放 = daemon 显式 /clear，照单全收（清空）；
 *  - 回放在本地列表里找锚点，锚点之前的本地行（更老的 scrollback）原样保留；
 *  - 配对上的行以本地为准（user 保留回执/图片，assistant 取更长的文本，tool 补 ok）；
 *  - 本地独有的行（thinking / 流式中 / 排队气泡）原地穿过，不挪位、不复制；
 *  - 硬分叉时回放从该点接管（可能有别的设备产生的回合），未回执的气泡仍被救回。
 */

/** transcript 里永远不会有的行：流式中的泡泡、thinking（daemon 不回放思考块）。 */
function isLocalOnly(m: ChatMessage): boolean {
  return m.streaming || m.role === 'thinking';
}

function couldPair(l: ChatMessage, r: ChatMessage): boolean {
  if (l.role !== r.role) return false;
  switch (l.role) {
    case 'user':
      return userTextMatch(l.text, r.text);
    case 'assistant':
      return l.text.startsWith(r.text) || r.text.startsWith(l.text);
    case 'tool':
      return l.tool === r.tool;
    case 'error':
      return l.text === r.text;
    default:
      return false;
  }
}

/** 回放在本地列表里的落点：能配出最长连续run的最深位置（回放是 transcript 尾部窗口）。 */
function findAnchor(local: ChatMessage[], replay: ChatMessage[]): number {
  const r0 = replay[0];
  let best = -1;
  let bestScore = 0;
  for (let k = 0; k < local.length; k++) {
    if (isLocalOnly(local[k]) || !couldPair(local[k], r0)) continue;
    let li = k;
    let ri = 0;
    let n = 0;
    while (li < local.length && ri < replay.length) {
      if (isLocalOnly(local[li])) {
        li++;
        continue;
      }
      if (!couldPair(local[li], replay[ri])) break;
      n++;
      li++;
      ri++;
    }
    if (n >= bestScore) {
      best = k;
      bestScore = n;
    }
  }
  return best;
}

/** user 行配对：全等，或带 @path 引用的出栈文本以原文开头。 */
function userTextMatch(localText: string, replayText: string): boolean {
  return replayText === localText || replayText.startsWith(`${localText}\n`);
}

/** 回放全文里是否已有这条 user 消息（证明已被 transcript 消费）。 */
function replayHasUser(replay: ChatMessage[], text: string): boolean {
  return replay.some((r: ChatMessage) => r.role === 'user' && userTextMatch(text, r.text));
}

/**
 * 分叉丢弃区里的 user 气泡救回：回放证明不了它已消费（不问回执状态——ack 只代表
 * daemon 收到，不代表回合已跑），且在本地尾部 RECENT_ROWS 行以内（真·刚发的排队
 * 消息）。尾部限制是关键：截尾回放窗口之外的【老】消息不在尾部，不会被误判救回
 * （避免退化成"老消息复制到尾部"）。
 */
const RECENT_ROWS = 12;

function rescueUnmatched(dropped: ChatMessage[], replay: ChatMessage[], localSize: number, fromIndex: number): ChatMessage[] {
  const out: ChatMessage[] = [];
  for (let i = 0; i < dropped.length; i++) {
    const m = dropped[i];
    if (m.role !== 'user') continue;
    if (fromIndex + i < localSize - RECENT_ROWS) continue; // 太老 = 窗口外的历史，不救
    if (replayHasUser(replay, m.text)) continue;           // 回放已含，自然去重
    out.push(m);
  }
  return out;
}

/** 合并全量回放。纯函数，可反复调用。 */
export function mergeHistory(local: ChatMessage[], replay: ChatMessage[]): ChatMessage[] {
  if (replay.length === 0) return []; // /clear 清屏
  if (local.length === 0) return replay;
  const anchor = findAnchor(local, replay);
  if (anchor < 0) {
    // 完全对不上：回放整体接管 + 救回尾部未被证明消费的气泡
    return [...replay, ...rescueUnmatched(local, replay, local.length, 0)];
  }
  const out: ChatMessage[] = local.slice(0, anchor); // 锚点前的老 scrollback 原样保留
  let li = anchor;
  let ri = 0;
  while (li < local.length && ri < replay.length) {
    const l = local[li];
    const r = replay[ri];
    if (couldPair(l, r)) {
      if (l.role === 'assistant') {
        // 流式泡泡与磁盘行对齐：谁在文本上延伸就取谁（回放滞后保住流式，离线回补补全文本）
        l.text = r.text.startsWith(l.text) ? r.text : l.text;
      } else if (l.role === 'tool' && l.ok === null && r.ok !== null) {
        l.ok = r.ok;
      }
      // user：保留本地行（回执状态 / 图片 / 文件 chip 是 transcript 没有的）
      out.push(l);
      li++;
      ri++;
      continue;
    }
    if (isLocalOnly(l)) {
      out.push(l);
      li++;
      continue;
    }
    if (l.role === 'user') {
      // 排队中的气泡：transcript 还没追上（回合开始才落盘）——若它能与回放里更靠后的
      // 某行配对则原地穿过；等回放追上来时自然配对去重。位置保持不变。
      let pairsLater = false;
      for (let j = ri + 1; j < replay.length; j++) {
        if (couldPair(l, replay[j])) {
          pairsLater = true;
          break;
        }
      }
      if (pairsLater) {
        out.push(l);
        li++;
        continue;
      }
    }
    // 硬分叉：回放从这点接管（可能包含本机没见过的、来自其他设备的回合）
    for (; ri < replay.length; ri++) out.push(replay[ri]);
    for (const m of rescueUnmatched(local.slice(li), replay, local.length, li)) out.push(m);
    return out;
  }
  while (li < local.length) {
    out.push(local[li]); // 回放耗尽，本地超前（流式中）——保留
    li++;
  }
  while (ri < replay.length) {
    out.push(replay[ri]); // 断线期间的回补
    ri++;
  }
  return out;
}

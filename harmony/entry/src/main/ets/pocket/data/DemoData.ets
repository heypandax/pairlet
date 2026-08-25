import { DirectoryEntry, PermissionAsk, SessionSummary } from '../protocol/Protocol';
import { ChatMessage } from './Repository';

/**
 * 免配对演示模式的自包含示例数据（对齐 mobile 端 DemoData.kt 的立意：
 * 应用商店审核 + 首次体验预览，无 relay / daemon 依赖，直接喂 Repository 状态）。
 * 内容为中文（面向 AppGallery 审核与国内用户截图场景）。
 */

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

function ago(ms: number): number {
  return Date.now() - ms;
}

export const DEMO_LIVE_DIR = '/Users/alex/code/cc-pocket';

export function demoDirs(): DirectoryEntry[] {
  const live = new DirectoryEntry();
  live.path = DEMO_LIVE_DIR;
  live.name = 'cc-pocket';
  live.isDir = true;
  live.hasSessions = true;
  live.open = true;
  live.executing = true;
  live.activeSessionId = 'demo-live-0001';
  live.activeSessionTitle = '为应用商店审核添加演示模式';

  const site = new DirectoryEntry();
  site.path = '/Users/alex/code/cc-pocket-site';
  site.name = 'cc-pocket-site';
  site.isDir = true;
  site.hasSessions = true;

  const relay = new DirectoryEntry();
  relay.path = '/Users/alex/code/relay-server';
  relay.name = 'relay-server';
  relay.isDir = true;
  relay.hasSessions = true;

  const notes = new DirectoryEntry();
  notes.path = '/Users/alex/code/notes-cli';
  notes.name = 'notes-cli';
  notes.isDir = true;
  notes.hasSessions = true;

  const dotfiles = new DirectoryEntry();
  dotfiles.path = '/Users/alex/dotfiles';
  dotfiles.name = 'dotfiles';
  dotfiles.isDir = true;
  dotfiles.hasSessions = true;

  return [live, site, relay, notes, dotfiles];
}

export function demoSessions(workdir: string): SessionSummary[] {
  const s1 = new SessionSummary();
  s1.sessionId = 'demo-live-0001';
  s1.title = '为应用商店审核添加演示模式';
  s1.firstPrompt = '加一个免配对的演示模式，让审核员不用电脑也能看到应用全貌';
  s1.messageCount = 12;
  s1.cwd = workdir;
  s1.lastModified = ago(2 * MIN);
  s1.live = workdir === DEMO_LIVE_DIR;

  const s2 = new SessionSummary();
  s2.sessionId = 'demo-s2';
  s2.title = '修复重连横幅闪烁';
  s2.firstPrompt = '打开会话时"重连中"横幅会闪一下';
  s2.messageCount = 8;
  s2.cwd = workdir;
  s2.lastModified = ago(5 * HOUR);

  const s3 = new SessionSummary();
  s3.sessionId = 'demo-s3';
  s3.title = '输入框支持语音听写';
  s3.firstPrompt = '能不能在输入框加语音听写？';
  s3.messageCount = 21;
  s3.cwd = workdir;
  s3.lastModified = ago(2 * DAY);

  const s4 = new SessionSummary();
  s4.sessionId = 'demo-s4';
  s4.title = '全部界面文案本地化';
  s4.firstPrompt = '把所有界面串接上中文本地化';
  s4.messageCount = 15;
  s4.cwd = workdir;
  s4.lastModified = ago(6 * DAY);

  return [s1, s2, s3, s4];
}

/** 预置转录：打开会话即像真实历史（用户 / 助手 / 工具回合齐全）。 */
export function demoHistory(): ChatMessage[] {
  const rows: ChatMessage[] = [];
  let seq = 0;
  const push = (role: 'user' | 'assistant' | 'tool', text: string, tool: string | null = null): void => {
    const m = new ChatMessage();
    m.id = `demo-h${seq++}`;
    m.role = role;
    m.text = text;
    m.tool = tool;
    rows.push(m);
  };
  push('user', '给设置页加一个深色模式开关');
  push('assistant', '好的，我来加深色模式开关。先看下当前设置页和主题是怎么组织的。');
  push('tool', 'SettingsView.ets', 'Read');
  push('tool', 'Theme.ets', 'Read');
  push('assistant',
    '主题目前只有一套浅色 token，方案如下：\n\n' +
    '1. 在 `Theme.ets` 里补一套 `darkColors` 色板\n' +
    '2. 持久化一个 `darkMode` 开关，按它切换色板\n' +
    '3. 在**设置 → 外观**加一行 `Switch`\n\n' +
    '现在开始改主题。');
  push('tool', 'Theme.ets', 'Edit');
  push('assistant', '完成——深色色板已接入并持久化，设置页开关就位。要我跑一次构建确认编译通过吗？');
  return rows;
}

/** 首个演示回合的授权卡（展示工具审批交互）。 */
export function demoAsk(): PermissionAsk {
  const ask = new PermissionAsk();
  ask.convoId = 'demo-convo';
  ask.askId = 'demo-ask-1';
  ask.tool = 'Bash';
  ask.inputPreview = 'git status';
  ask.title = '运行命令';
  ask.rule = 'git status';
  return ask;
}

/** 授权通过后的流式回复分片（肉眼可见地逐段到达）。 */
export const DEMO_REPLY_CHUNKS: string[] = [
  '当前状态快速摘要：\n\n',
  '- 工作树在 `main` 上是干净的\n- 演示流程走的就是真实消息通路\n\n',
  '要继续下一个改动，还是先打开某个文件看看？',
];

/** 后续回合（无授权步骤）的回复分片。 */
export const DEMO_PLAIN_CHUNKS: string[] = [
  '收到。',
  '我的做法是：\n\n1. 在相应模块里完成修改\n2. 补一个小测试\n3. 跑构建确认通过\n\n',
  '要我直接开始吗？',
];

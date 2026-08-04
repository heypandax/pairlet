import { Tok } from './Theme';

/**
 * 轻量 markdown 渲染（对齐 Android Markdown.kt 的最小可视子集）：
 * 块级：``` 代码围栏 / #~#### 标题 / - * + 与 1. 列表 / 其余为段落（连续行合并）。
 * 行内：**bold**、`code`、*italic*。流式追加时整块重解析（文本量级小，可接受）。
 * 目标是「不再看到 ** 原文」，不是完整 CommonMark。
 */

export interface MdSpan {
  text: string;
  bold: boolean;
  italic: boolean;
  code: boolean;
}

export type MdKind = 'para' | 'code' | 'h' | 'li';

export interface MdBlock {
  kind: MdKind;
  text: string;      // code 块的原文 / li 的行文
  level: number;     // h 的级别（1-4）/ li 的缩进级
  bullet: string;    // li 的引导符（'•' 或 '1.'），其余为 ''
  spans: MdSpan[];   // para/h/li 的行内解析结果
}

function plainSpans(text: string): MdSpan[] {
  const s: MdSpan = { text, bold: false, italic: false, code: false };
  return [s];
}

/** 行内解析：**bold**、`code`、*italic*（按出现顺序贪心，不嵌套）。 */
export function parseInline(text: string): MdSpan[] {
  const spans: MdSpan[] = [];
  let bold = false;
  let italic = false;
  let code = false;
  let buf = '';
  const flush = (): void => {
    if (buf.length > 0) {
      const s: MdSpan = { text: buf, bold, italic, code };
      spans.push(s);
      buf = '';
    }
  };
  let i = 0;
  while (i < text.length) {
    const two = text.substring(i, i + 2);
    const one = text.charAt(i);
    if (!code && two === '**') {
      flush();
      bold = !bold;
      i += 2;
      continue;
    }
    if (one === '`') {
      flush();
      code = !code;
      i += 1;
      continue;
    }
    if (!code && one === '*') {
      flush();
      italic = !italic;
      i += 1;
      continue;
    }
    buf += one;
    i += 1;
  }
  flush();
  // 未闭合的标记（流式中途）不回头修补——下一个 chunk 会补全，重解析后自然正确
  return spans.length > 0 ? spans : plainSpans('');
}

/** 块级切分。 */
export function parseBlocks(text: string): MdBlock[] {
  const blocks: MdBlock[] = [];
  const lines = text.split('\n');
  let inCode = false;
  let codeBuf = '';
  let paraBuf: string[] = [];

  const flushPara = (): void => {
    if (paraBuf.length > 0) {
      const t = paraBuf.join(' ').trim();
      if (t.length > 0) {
        const b: MdBlock = { kind: 'para', text: t, level: 0, bullet: '', spans: parseInline(t) };
        blocks.push(b);
      }
      paraBuf = [];
    }
  };
  const flushCode = (): void => {
    const b: MdBlock = { kind: 'code', text: codeBuf, level: 0, bullet: '', spans: plainSpans(codeBuf) };
    blocks.push(b);
    codeBuf = '';
  };

  for (const raw of lines) {
    const line = raw;
    const trimmed = line.trim();
    if (trimmed.startsWith('```')) {
      if (inCode) {
        flushCode();
        inCode = false;
      } else {
        flushPara();
        inCode = true;
        codeBuf = '';
      }
      continue;
    }
    if (inCode) {
      codeBuf = codeBuf.length > 0 ? `${codeBuf}\n${line}` : line;
      continue;
    }
    if (trimmed.length === 0) {
      flushPara();
      continue;
    }
    // 标题
    let hLevel = 0;
    while (hLevel < trimmed.length && hLevel < 4 && trimmed.charAt(hLevel) === '#') hLevel++;
    if (hLevel > 0 && trimmed.length > hLevel && trimmed.charAt(hLevel) === ' ') {
      flushPara();
      const t = trimmed.substring(hLevel + 1);
      const b: MdBlock = { kind: 'h', text: t, level: hLevel, bullet: '', spans: parseInline(t) };
      blocks.push(b);
      continue;
    }
    // 无序列表
    if (trimmed.startsWith('- ') || trimmed.startsWith('* ') || trimmed.startsWith('+ ')) {
      flushPara();
      const indent = Math.floor((line.length - line.trimStart().length) / 2);
      const t = trimmed.substring(2);
      const b: MdBlock = { kind: 'li', text: t, level: indent, bullet: '•', spans: parseInline(t) };
      blocks.push(b);
      continue;
    }
    // 有序列表 "1. " / "12) "
    const m = /^(\d+)[.)]\s+/.exec(trimmed);
    if (m !== null) {
      flushPara();
      const t = trimmed.substring(m[0].length);
      const b: MdBlock = { kind: 'li', text: t, level: 0, bullet: `${m[1]}.`, spans: parseInline(t) };
      blocks.push(b);
      continue;
    }
    paraBuf.push(trimmed);
  }
  if (inCode) {
    flushCode(); // 流式中的未闭合围栏：按已有内容先渲染
  } else {
    flushPara();
  }
  return blocks;
}

/** assistant 消息的 markdown 正文。 */
@Component
export struct MdText {
  @Prop text: string;

  /** 行内 span 序列（code span 与普通 span 分两条链，避免传空 fontFamily）。 */
  @Builder
  InlineSpans(spans: MdSpan[], forceBold: boolean, keyPrefix: string) {
    ForEach(spans, (s: MdSpan, sidx: number) => {
      if (s.code) {
        Span(s.text)
          .fontColor(Tok.accent)
          .fontFamily('monospace')
          .fontWeight(forceBold ? FontWeight.Bold : FontWeight.Normal)
      } else {
        Span(s.text)
          .fontWeight(forceBold || s.bold ? FontWeight.Bold : FontWeight.Normal)
          .fontStyle(s.italic ? FontStyle.Italic : FontStyle.Normal)
          .fontColor(Tok.tx)
      }
    }, (s: MdSpan, sidx: number) => `${keyPrefix}-${sidx}-${s.text.length}`)
  }

  build() {
    Column({ space: 6 }) {
      ForEach(parseBlocks(this.text), (b: MdBlock, idx: number) => {
        if (b.kind === 'code') {
          Scroll() {
            Text(b.text.length > 0 ? b.text : ' ')
              .fontSize(12.5)
              .fontColor(Tok.tx)
              .fontFamily('monospace')
              .textAlign(TextAlign.Start)
          }
          .scrollable(ScrollDirection.Horizontal)
          .scrollBar(BarState.Off)
          .width('100%')
          .padding({ left: 12, right: 12, top: 9, bottom: 9 })
          .backgroundColor(Tok.raised)
          .borderRadius(10)
          .border({ width: 1, color: Tok.hair, radius: 10 })
        } else if (b.kind === 'h') {
          Text() {
            this.InlineSpans(b.spans, true, `${idx}`)
          }
          .fontSize(b.level === 1 ? 19 : b.level === 2 ? 17 : 15.5)
          .width('100%')
          .textAlign(TextAlign.Start)
        } else if (b.kind === 'li') {
          Row({ space: 8 }) {
            Text(b.bullet)
              .fontSize(15)
              .fontColor(Tok.tx2)
            Text() {
              this.InlineSpans(b.spans, false, `${idx}`)
            }
            .fontSize(15)
            .layoutWeight(1)
            .textAlign(TextAlign.Start)
          }
          .width('100%')
          .padding({ left: b.level * 14 })
          .alignItems(VerticalAlign.Top)
        } else {
          Text() {
            this.InlineSpans(b.spans, false, `${idx}`)
          }
          .fontSize(15)
          .width('100%')
          .textAlign(TextAlign.Start)
        }
      }, (b: MdBlock, idx: number) => `${idx}-${b.kind}-${b.text.length}`)
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
  }
}

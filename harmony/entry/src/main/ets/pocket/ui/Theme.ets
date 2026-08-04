import { mediaquery } from '@kit.ArkUI';

/**
 * 设计 token —— 逐值对齐 Android 端 theme/Theme.kt 的 DarkPalette / LightPalette
 * （"Calm Terminal Companion"）。字段是可变 static：Tok.apply() 换色后由
 * Index 的 themeVersion 分支整树重建，所有 build 时读取处自然刷新。
 */

export type ThemeMode = 'dark' | 'light' | 'system';

export class Tok {
  static base: string = '#0E0F11';    // 应用底色
  static surface: string = '#16181B'; // 卡片 / 顶栏
  static raised: string = '#1E2125';  // 浮起元素（输入条、chip）
  static hair: string = '#2A2E33';    // 发丝线边框
  static tx: string = '#ECEDEE';      // 主文字
  static tx2: string = '#9BA1A6';     // 次文字
  static muted: string = '#6B7177';   // 弱化文字
  static accent: string = '#D97757';  // 主色（陶土橙）
  static accentPressed: string = '#C4633F';
  static ok: string = '#4FB477';
  static warn: string = '#E0A93B';
  static danger: string = '#E5604D';
  static info: string = '#5B9BD5';

  /** 应用深色 / 浅色整套 token（值见 Android Theme.kt 的两个 Palette）。 */
  static apply(dark: boolean): void {
    if (dark) {
      Tok.base = '#0E0F11'; Tok.surface = '#16181B'; Tok.raised = '#1E2125'; Tok.hair = '#2A2E33';
      Tok.tx = '#ECEDEE'; Tok.tx2 = '#9BA1A6'; Tok.muted = '#6B7177';
      Tok.accent = '#D97757'; Tok.accentPressed = '#C4633F';
      Tok.ok = '#4FB477'; Tok.warn = '#E0A93B'; Tok.danger = '#E5604D'; Tok.info = '#5B9BD5';
    } else {
      Tok.base = '#FAF9F7'; Tok.surface = '#FFFFFF'; Tok.raised = '#F1EFEB'; Tok.hair = '#E4E1DB';
      Tok.tx = '#1C1D1F'; Tok.tx2 = '#5B6066'; Tok.muted = '#878C92';
      Tok.accent = '#C15F3C'; Tok.accentPressed = '#A94E30';
      Tok.ok = '#2E9E5B'; Tok.warn = '#B07D1C'; Tok.danger = '#C53D2B'; Tok.info = '#3B7DC4';
    }
  }

  /** 跟随系统时的系统深浅探测；查询失败按深色（对齐 Android 的 DARK 兜底）。 */
  static systemDark(): boolean {
    try {
      return mediaquery.matchMediaSync('(dark-mode: true)').matches;
    } catch (e) {
      return true;
    }
  }

  static resolveDark(mode: ThemeMode): boolean {
    if (mode === 'dark') return true;
    if (mode === 'light') return false;
    return Tok.systemDark();
  }
}

/** 内容限宽（vp）：窄于它的手机无感；折叠屏展开 / 平板上正文限宽居中，不拉满边。 */
export const CONTENT_MAX = 860;

/** "2.4 MB" / "812 KB"（对齐 Android fmtSize）。 */
export function fmtSize(bytes: number): string {
  const mb = bytes / (1024 * 1024);
  if (mb >= 10) return `${Math.floor(mb)} MB`;
  if (mb >= 1) return `${Math.round(mb * 10) / 10} MB`;
  return `${Math.max(bytes > 0 ? 1 : 0, Math.floor(bytes / 1024))} KB`;
}

/** "Q3-…rt.pdf" 中段截断（对齐 Android middleTrunc）。 */
export function middleTrunc(name: string, head: number = 10, tail: number = 8): string {
  if (name.length <= head + tail + 1) return name;
  return `${name.substring(0, head)}…${name.substring(name.length - tail)}`;
}

/** 123456 → "123,456"（对齐 Android groupDigits，上下文 token 数用）。 */
export function groupDigits(n: number): string {
  const s = `${Math.floor(n)}`;
  let out = '';
  for (let i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 === 0) out += ',';
    out += s.charAt(i);
  }
  return out;
}

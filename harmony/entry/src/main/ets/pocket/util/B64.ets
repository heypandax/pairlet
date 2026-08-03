/**
 * 标准 base64（带 padding，RFC 4648）—— SendPrompt.images / FileChunk.base64 用。
 * 与 B64Url.ets（无填充 URL_SAFE，配对用）区分开。手摇避免 Base64Helper 的枚举差异。
 * 字符串下标在 ArkTS 受限，用 charAt。
 */

const CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function b64Encode(data: Uint8Array): string {
  const out = new Array<string>();
  for (let i = 0; i < data.length; i += 3) {
    const b0 = data[i];
    const b1 = i + 1 < data.length ? data[i + 1] : -1;
    const b2 = i + 2 < data.length ? data[i + 2] : -1;
    out.push(CHARS.charAt(b0 >> 2));
    out.push(CHARS.charAt(((b0 & 0x03) << 4) | (b1 >= 0 ? b1 >> 4 : 0)));
    out.push(b1 >= 0 ? CHARS.charAt(((b1 & 0x0f) << 2) | (b2 >= 0 ? b2 >> 6 : 0)) : '=');
    out.push(b2 >= 0 ? CHARS.charAt(b2 & 0x3f) : '=');
  }
  return out.join('');
}

/** 与 Kotlin base64Len 一致：原始字节数 → base64 字符数（用于图片预算核算）。 */
export function b64Len(rawBytes: number): number {
  return 4 * Math.floor((rawBytes + 2) / 3);
}

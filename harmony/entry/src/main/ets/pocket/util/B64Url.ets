/**
 * base64url（无填充）编解码，对齐 Kotlin 侧 util/B64Url 与协议控制面
 * （"All byte-valued fields below are base64url(no-pad) of raw bytes" — Messages.kt）。
 * 手摇实现，避免依赖 Base64Helper 的 URL_SAFE 枚举在各 SDK 版本上的差异。
 * 注：ArkTS 禁止字符串下标访问（arkts-no-props-by-index），一律 charAt。
 */

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function b64UrlEncode(data: Uint8Array): string {
  let out = '';
  let i = 0;
  while (i + 3 <= data.length) {
    const n = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
    out += ALPHABET.charAt((n >> 18) & 63) + ALPHABET.charAt((n >> 12) & 63) +
      ALPHABET.charAt((n >> 6) & 63) + ALPHABET.charAt(n & 63);
    i += 3;
  }
  const rem = data.length - i;
  if (rem === 1) {
    const n = data[i] << 16;
    out += ALPHABET.charAt((n >> 18) & 63) + ALPHABET.charAt((n >> 12) & 63);
  } else if (rem === 2) {
    const n = (data[i] << 16) | (data[i + 1] << 8);
    out += ALPHABET.charAt((n >> 18) & 63) + ALPHABET.charAt((n >> 12) & 63) + ALPHABET.charAt((n >> 6) & 63);
  }
  return out.replace(/\+/g, '-').replace(/\//g, '_');
}

export function b64UrlDecode(s: string): Uint8Array {
  const std = s.replace(/-/g, '+').replace(/_/g, '/');
  const padLen = (4 - (std.length % 4)) % 4;
  const padded = std + '='.repeat(padLen);
  const out = new Array<number>();
  let i = 0;
  while (i + 4 <= padded.length) {
    const c0 = ALPHABET.indexOf(padded.charAt(i));
    const c1 = ALPHABET.indexOf(padded.charAt(i + 1));
    const c2 = padded.charAt(i + 2) === '=' ? -1 : ALPHABET.indexOf(padded.charAt(i + 2));
    const c3 = padded.charAt(i + 3) === '=' ? -1 : ALPHABET.indexOf(padded.charAt(i + 3));
    if (c0 < 0 || c1 < 0) break;
    const n = (c0 << 18) | (c1 << 12) | ((c2 < 0 ? 0 : c2) << 6) | (c3 < 0 ? 0 : c3);
    out.push((n >> 16) & 0xff);
    if (c2 >= 0) out.push((n >> 8) & 0xff);
    if (c3 >= 0) out.push(n & 0xff);
    i += 4;
  }
  return new Uint8Array(out);
}

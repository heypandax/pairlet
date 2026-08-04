import { util } from '@kit.ArkTS';

/** UTF-8 / Uint8Array 转换与拼接。对齐 Kotlin 侧 ByteArray 语义。 */

const encoder = util.TextEncoder.create('utf-8');
const decoder = util.TextDecoder.create('utf-8');

export function utf8(s: string): Uint8Array {
  if (s.length === 0) return new Uint8Array(0); // encodeInto('') 行为不可靠，直接短路
  return encoder.encodeInto(s);
}

export function fromUtf8(b: Uint8Array): string {
  return decoder.decodeToString(b);
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
  let total = 0;
  for (const p of parts) total += p.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

/** 大端字节序 -> bigint（用于把 P-256 标量/坐标喂给软件 ECDH）。 */
export function bytesToBigInt(b: Uint8Array): bigint {
  let v = 0n;
  for (let i = 0; i < b.length; i++) {
    v = (v << 8n) | BigInt(b[i]);
  }
  return v;
}

/** bigint -> 定长大端字节序（左侧补零）。 */
export function bigIntToBytes(v: bigint, len: number): Uint8Array {
  const out = new Uint8Array(len);
  let x = v;
  for (let i = len - 1; i >= 0; i--) {
    out[i] = Number(x & 0xffn);
    x >>= 8n;
  }
  return out;
}

export function bytesToHex(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i++) {
    s += b[i].toString(16).padStart(2, '0');
  }
  return s;
}

export function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  }
  return out;
}

export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { bigIntToBytes, bytesEqual, bytesToBigInt, concatBytes, hexToBytes, utf8 } from '../util/Bytes';

/**
 * cc-pocket E2E 密码学原语（ArkTS 移植）。
 * 套件与 protocol/e2e/E2ECrypto.kt 完全一致：P-256 ECDH + HKDF(HMAC-SHA256) + AES-256-GCM。
 *
 * 设计说明（spike 决策）：
 * - ECDH 用纯 bigint 软件实现（Jacobian 坐标），因为 cryptoFramework 的 AsyKeySpec 系列接口
 *   （ECCKeyPairSpec / getAsyKeySpecBigInt）的字段形状需要对照本机 SDK d.ts 逐字段核实，
 *   而软件实现的行为完全确定、可用公开测试向量自证。TODO(hardening)：核实 SDK spec API 后
 *   换成 cryptoFramework ECDH（密钥不出 TEE、抗时序）。
 * - HMAC / AES-GCM 用 cryptoFramework（接口稳定、有硬件加速；transport 每帧都过 AES-GCM）。
 *
 * 自检：selfTest() 覆盖 RFC 5869 HKDF 向量、P-256 2G 向量、ECDH 互验、GCM 往返。
 * 配对流程首次使用前必须跑通（UI 开发期会调用并展示结果）。
 */

// ─────────────────────────── P-256 (secp256r1) ───────────────────────────

const P = BigInt('0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff');
const N = BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
const A_COEFF = P - 3n;
const B_COEFF = BigInt('0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b');
const GX = BigInt('0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296');
const GY = BigInt('0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5');

function mod(x: bigint, m: bigint): bigint {
  const r = x % m;
  return r >= 0n ? r : r + m;
}

/** a^-1 mod p，费马小定理（p 为素数）：a^(p-2) mod p。 */
function modInv(a: bigint, m: bigint): bigint {
  return modPow(mod(a, m), m - 2n, m);
}

function modPow(base: bigint, exp: bigint, m: bigint): bigint {
  let result = 1n;
  let b = mod(base, m);
  let e = exp;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % m;
    b = (b * b) % m;
    e >>= 1n;
  }
  return result;
}

/** Jacobian 坐标点；无穷远点用 z=0 表示。 */
class JacPoint {
  x: bigint;
  y: bigint;
  z: bigint;

  constructor(x: bigint, y: bigint, z: bigint) {
    this.x = x;
    this.y = y;
    this.z = z;
  }
  static readonly INF: JacPoint = new JacPoint(0n, 1n, 0n);
  static fromAffine(x: bigint, y: bigint): JacPoint {
    return new JacPoint(x, y, 1n);
  }
  isInf(): boolean {
    return this.z === 0n;
  }
  toAffine(): bigint[] {
    if (this.isInf()) return [0n, 0n];
    const zi = modInv(this.z, P);
    const zi2 = (zi * zi) % P;
    const zi3 = (zi2 * zi) % P;
    return [(this.x * zi2) % P, (this.y * zi3) % P];
  }
}

function pointDouble(p: JacPoint): JacPoint {
  if (p.isInf() || p.y === 0n) return JacPoint.INF;
  const xx = (p.x * p.x) % P;
  const zz = (p.z * p.z) % P;
  const zz4 = (zz * zz) % P;
  // alpha = 3x² + az⁴ （a = p-3）
  const alpha = mod(3n * xx + A_COEFF * zz4, P);
  const yy = (p.y * p.y) % P;
  const beta = (((p.x * yy) % P) * 4n) % P;
  const x3 = mod(alpha * alpha - 2n * beta, P);
  const yyyy = (yy * yy) % P;
  const y3 = mod(alpha * (beta - x3) - 8n * yyyy, P);
  const z3 = mod((p.y + p.z) * (p.y + p.z) - yy - zz, P);
  return new JacPoint(x3, y3, z3);
}

function pointAdd(p1: JacPoint, p2: JacPoint): JacPoint {
  if (p1.isInf()) return p2;
  if (p2.isInf()) return p1;
  const z1z1 = (p1.z * p1.z) % P;
  const z2z2 = (p2.z * p2.z) % P;
  const u1 = (p1.x * z2z2) % P;
  const u2 = (p2.x * z1z1) % P;
  const s1 = (((p1.y * z2z2) % P) * p2.z) % P;
  const s2 = (((p2.y * z1z1) % P) * p1.z) % P;
  if (u1 === u2) {
    return s1 === s2 ? pointDouble(p1) : JacPoint.INF;
  }
  const h = mod(u2 - u1, P);
  const r = mod(s2 - s1, P);
  const hh = (h * h) % P;
  const hhh = (hh * h) % P;
  const u1hh = (u1 * hh) % P;
  const x3 = mod(r * r - hhh - 2n * u1hh, P);
  const y3 = mod(r * (u1hh - x3) - s1 * hhh, P);
  const z3 = (((p1.z * p2.z) % P) * h) % P;
  return new JacPoint(x3, y3, z3);
}

/** k·P，朴素 double-and-add（MSB→LSB）。握手期只调用 4~5 次，性能足够。 */
function scalarMult(k: bigint, px: bigint, py: bigint): bigint[] {
  let r = JacPoint.INF;
  const q = JacPoint.fromAffine(px, py);
  for (let i = 255; i >= 0; i--) {
    r = pointDouble(r);
    if ((k >> BigInt(i)) & 1n) {
      r = pointAdd(r, q);
    }
  }
  return r.toAffine();
}

function onCurve(x: bigint, y: bigint): boolean {
  if (x < 0n || x >= P || y < 0n || y >= P) return false;
  return mod(y * y - (x * x * x + A_COEFF * x + B_COEFF), P) === 0n;
}

// ─────────────────────────── 对外原语（对齐 E2ECrypto.kt） ───────────────────────────

export class E2EKeyPair {
  privateRaw: Uint8Array; // 32-byte 大端标量
  publicRaw: Uint8Array;  // 65-byte 非压缩点 0x04‖X‖Y

  constructor(privateRaw: Uint8Array, publicRaw: Uint8Array) {
    this.privateRaw = privateRaw;
    this.publicRaw = publicRaw;
  }
}

export class E2ECrypto {
  /** P-256 密钥对：密码学随机标量 k ∈ [1, n-1]，公钥 0x04‖X‖Y。 */
  static async generateKeyPair(): Promise<E2EKeyPair> {
    const rand = cryptoFramework.createRandom();
    while (true) {
      const blob = await rand.generateRandom(32);
      const k = bytesToBigInt(blob.data);
      if (k > 0n && k < N) {
        const r = scalarMult(k, GX, GY);
        const pub = concatBytes(new Uint8Array([4]), bigIntToBytes(r[0], 32), bigIntToBytes(r[1], 32));
        return new E2EKeyPair(bigIntToBytes(k, 32), pub);
      }
    }
  }

  /** ECDH：我方 32-byte 私钥标量 × 对端 65-byte 公钥点 → 32-byte 共享秘密（X 坐标大端）。 */
  static agree(privateRaw: Uint8Array, peerPublicRaw: Uint8Array): Uint8Array {
    if (peerPublicRaw.length !== 65 || peerPublicRaw[0] !== 4) {
      throw new Error('bad peer public key');
    }
    const x = bytesToBigInt(peerPublicRaw.slice(1, 33));
    const y = bytesToBigInt(peerPublicRaw.slice(33, 65));
    if (!onCurve(x, y)) {
      throw new Error('peer public key not on P-256');
    }
    const k = bytesToBigInt(privateRaw);
    if (k <= 0n || k >= N) {
      throw new Error('bad private scalar');
    }
    const sr = scalarMult(k, x, y);
    return bigIntToBytes(sr[0], 32);
  }

  /** HKDF-SHA256（RFC 5869），与 E2ECrypto.kt 的手摇实现逐字节一致。 */
  static async hkdf(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
    const prk = await E2ECrypto.hmacSha256(salt.length === 0 ? new Uint8Array(32) : salt, ikm);
    const out = new Array<number>();
    let t = new Uint8Array(0);
    let counter = 1;
    while (out.length < length) {
      t = await E2ECrypto.hmacSha256(prk, concatBytes(t, info, new Uint8Array([counter & 0xff])));
      for (const b of t) {
        if (out.length < length) out.push(b);
      }
      counter++;
    }
    return new Uint8Array(out);
  }

  /** AES-256-GCM seal：nonce 12 字节；返回 ciphertext‖tag（16 字节），同 Java AES/GCM 输出布局。 */
  static async seal(key: Uint8Array, nonce: Uint8Array, plaintext: Uint8Array, aad: Uint8Array): Promise<Uint8Array> {
    const symKey = await E2ECrypto.aesKey(key);
    const cipher = cryptoFramework.createCipher('AES256|GCM|PKCS7');
    const spec = E2ECrypto.gcmSpec(nonce, aad, new Uint8Array(0));
    cipher.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, symKey, spec);
    const part1 = cipher.updateSync({ data: plaintext });
    // 官方文档：GCM 的 authTag 从 doFinal 的返回值获取（不是回填 spec.authTag——
    // 实测 API 24 真机 spec.authTag 始终为空，doFinal(null) 返回的 DataBlob 才是 tag）。
    const fin = cipher.doFinalSync(null);
    const finData = fin?.data ?? new Uint8Array(0);
    if (finData.length < 16) {
      throw new Error(`GCM tag not produced (len=${finData.length})`);
    }
    const tag = finData.slice(finData.length - 16);
    const extraCt = finData.slice(0, finData.length - 16); // 防御：doFinal 若带出剩余密文则拼上
    return concatBytes(part1?.data ?? new Uint8Array(0), extraCt, tag);
  }

  /** AES-256-GCM open：输入 ciphertext‖tag；认证失败返回 null（对齐 Kotlin 的 `?: return null`）。 */
  static async open(key: Uint8Array, nonce: Uint8Array, ctTag: Uint8Array, aad: Uint8Array): Promise<Uint8Array | null> {
    if (ctTag.length < 16) return null;
    const ct = ctTag.slice(0, ctTag.length - 16);
    const tag = ctTag.slice(ctTag.length - 16);
    try {
      const symKey = await E2ECrypto.aesKey(key);
      const cipher = cryptoFramework.createCipher('AES256|GCM|PKCS7');
      const spec = E2ECrypto.gcmSpec(nonce, aad, tag);
      cipher.initSync(cryptoFramework.CryptoMode.DECRYPT_MODE, symKey, spec);
      const part1 = cipher.updateSync({ data: ct });
      const part2 = cipher.doFinalSync(null); // 认证失败在此抛 BusinessError
      return concatBytes(part1?.data ?? new Uint8Array(0), part2?.data ?? new Uint8Array(0));
    } catch (e) {
      return null; // 认证失败
    }
  }

  // ── cryptoFramework 封装 ──

  private static gcmSpec(nonce: Uint8Array, aad: Uint8Array, tag: Uint8Array): cryptoFramework.GcmParamsSpec {
    const spec: cryptoFramework.GcmParamsSpec = {
      iv: { data: nonce },
      aad: { data: aad },
      authTag: { data: tag },
      algName: 'GcmParamsSpec',
    };
    return spec;
  }

  private static async aesKey(key: Uint8Array): Promise<cryptoFramework.SymKey> {
    const gen = cryptoFramework.createSymKeyGenerator('AES256');
    return gen.convertKey({ data: key });
  }

  static async hmacSha256(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
    const gen = cryptoFramework.createSymKeyGenerator('HMAC');
    const symKey = await gen.convertKey({ data: key });
    const mac = cryptoFramework.createMac('SHA256');
    await mac.init(symKey);
    await mac.update({ data: data });
    const out = await mac.doFinal();
    return out.data;
  }

  // ── 自检：配对前必须全绿 ──

  /**
   * 覆盖：HKDF RFC5869 case1、P-256 2G 公开向量、ECDH 互验（dh(a,B)=dh(b,A)）、
   * GCM seal/open 往返 + 篡改检测。返回失败项列表（空 = 全过）。
   */
  static async selfTest(): Promise<string[]> {
    const failures = new Array<string>();
    try {
      // RFC 5869 Test Case 1
      const okm = await E2ECrypto.hkdf(
        new Uint8Array(22).fill(0x0b),
        hexToBytes('000102030405060708090a0b0c'),
        hexToBytes('f0f1f2f3f4f5f6f7f8f9'),
        42);
      const expect = hexToBytes(
        '3cb25f25faacd57a90434f64d0362f2a' +
        '2d2d0a90cf1a5a4c5db02d56ecc4c5bf' +
        '34007208d5b887185865');
      if (!bytesEqual(okm, expect)) failures.push('hkdf-rfc5869-case1');
    } catch (e) {
      failures.push(`hkdf-throw:${e}`);
    }
    try {
      // P-256 2G 公开向量
      const g2 = scalarMult(2n, GX, GY);
      const x = g2[0];
      const y = g2[1];
      const ex = BigInt('0x7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978');
      const ey = BigInt('0x07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1');
      if (x !== ex || y !== ey) failures.push('p256-2g');
    } catch (e) {
      failures.push(`p256-throw:${e}`);
    }
    try {
      // ECDH 互验 + onCurve 校验
      const kpA = await E2ECrypto.generateKeyPair();
      const kpB = await E2ECrypto.generateKeyPair();
      const s1 = E2ECrypto.agree(kpA.privateRaw, kpB.publicRaw);
      const s2 = E2ECrypto.agree(kpB.privateRaw, kpA.publicRaw);
      if (!bytesEqual(s1, s2)) failures.push('ecdh-agree');
      if (s1.length !== 32) failures.push('ecdh-len');
    } catch (e) {
      failures.push(`ecdh-throw:${e}`);
    }
    try {
      // GCM 往返 + 篡改拒绝
      const key = new Uint8Array(32).fill(7);
      const nonce = new Uint8Array(12).fill(3);
      const aad = utf8('ccpocket-e2e-v1');
      const pt = utf8('hello harmony');
      const sealed = await E2ECrypto.seal(key, nonce, pt, aad);
      const opened = await E2ECrypto.open(key, nonce, sealed, aad);
      if (opened === null || !bytesEqual(opened, pt)) failures.push('gcm-roundtrip');
      const tampered = sealed.slice();
      tampered[0] ^= 1;
      if ((await E2ECrypto.open(key, nonce, tampered, aad)) !== null) failures.push('gcm-tamper-accepted');
    } catch (e) {
      failures.push(`gcm-throw:${e}`);
    }
    return failures;
  }
}

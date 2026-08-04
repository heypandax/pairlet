import { concatBytes } from '../util/Bytes';
import { E2ECrypto, E2EKeyPair } from './E2ECrypto';

/**
 * E2E 会话（ArkTS 移植），逐行对齐 protocol/e2e/E2ESession.kt：
 *
 * 握手（4-DH，device 为 initiator）：
 *   device 发 msg1 = e_i.pub（65B），daemon 回 msg2 = e_r.pub
 *   双方推导 HKDF(ikm = es‖ss‖ee‖se‖psk, salt = transcript, info = "ccpocket-e2e-keys-v1", 64)
 *   → k_i2r ‖ k_r2i；transcript = "ccpocket-e2e-v1"‖s_i‖s_r‖e_i‖e_r
 *
 * 传输：AES-256-GCM，每方向独立密钥，严格递增 64 位计数器作 nonce。
 * 每帧 = [8B 大端计数器]‖ciphertext‖tag；nonce = 4 个零字节‖8B 大端计数器。
 * 重放/乱序（计数器不严格递增）拒绝。
 */

const AAD = new Uint8Array([99, 99, 112, 111, 99, 107, 101, 116, 45, 101, 50, 101, 45, 118, 49]); // "ccpocket-e2e-v1"
const PROLOGUE = AAD;
const KEYINFO = new Uint8Array([99, 99, 112, 111, 99, 107, 101, 116, 45, 101, 50, 101, 45, 107, 101, 121, 115, 45, 118, 49]); // "ccpocket-e2e-keys-v1"

function ctrBytes(ctr: number): Uint8Array {
  const out = new Uint8Array(8);
  let v = ctr;
  for (let i = 7; i >= 0; i--) {
    out[i] = v & 0xff;
    v = Math.floor(v / 256);
  }
  return out;
}

function nonce(ctr: number): Uint8Array {
  const out = new Uint8Array(12);
  out.set(ctrBytes(ctr), 4);
  return out;
}

export class E2ESession {
  private sendKey: Uint8Array;
  private recvKey: Uint8Array;
  private sendCtr = 0;
  private recvCtr = -1; // 已接受的最大计数器；下一个必须严格更大

  constructor(sendKey: Uint8Array, recvKey: Uint8Array) {
    this.sendKey = sendKey;
    this.recvKey = recvKey;
  }

  async seal(plaintext: Uint8Array): Promise<Uint8Array> {
    const ctr = this.sendCtr++;
    const sealed = await E2ECrypto.seal(this.sendKey, nonce(ctr), plaintext, AAD);
    return concatBytes(ctrBytes(ctr), sealed);
  }

  /** 认证失败 / 帧畸形 / 重放乱序 → null。 */
  async open(frame: Uint8Array): Promise<Uint8Array | null> {
    if (frame.length < 8 + 16) return null;
    let ctr = 0;
    for (let i = 0; i < 8; i++) ctr = ctr * 256 + frame[i];
    if (ctr <= this.recvCtr) return null;
    const pt = await E2ECrypto.open(this.recvKey, nonce(ctr), frame.slice(8), AAD);
    if (pt === null) return null;
    this.recvCtr = ctr;
    return pt;
  }
}

/** initiator（device）侧握手中状态：发 ephPublic（msg1），拿到 msg2 后 finish()。 */
export class E2EInitiator {
  readonly ephPublic: Uint8Array;
  private staticPriv: Uint8Array;
  private staticPub: Uint8Array;
  private peerStaticPub: Uint8Array;
  private psk: Uint8Array;
  private eph: E2EKeyPair;

  constructor(
    staticPriv: Uint8Array,
    staticPub: Uint8Array,
    peerStaticPub: Uint8Array,
    psk: Uint8Array,
    eph: E2EKeyPair,
  ) {
    this.staticPriv = staticPriv;
    this.staticPub = staticPub;
    this.peerStaticPub = peerStaticPub;
    this.psk = psk;
    this.eph = eph;
    this.ephPublic = eph.publicRaw;
  }

  async finish(responderEphPub: Uint8Array): Promise<E2ESession> {
    const es = E2ECrypto.agree(this.eph.privateRaw, this.peerStaticPub);      // DH(E_i, S_r)
    const ss = E2ECrypto.agree(this.staticPriv, this.peerStaticPub);         // DH(S_i, S_r)
    const ee = E2ECrypto.agree(this.eph.privateRaw, responderEphPub);        // DH(E_i, E_r)
    const se = E2ECrypto.agree(this.staticPriv, responderEphPub);            // DH(S_i, E_r)
    const transcript = concatBytes(PROLOGUE, this.staticPub, this.peerStaticPub, this.eph.publicRaw, responderEphPub);
    const okm = await E2ECrypto.hkdf(concatBytes(es, ss, ee, se, this.psk), transcript, KEYINFO, 64);
    // initiator 发送用 k_i2r，接收用 k_r2i（对齐 Kotlin: sendKey = keys.first, recvKey = keys.second）
    return new E2ESession(okm.slice(0, 32), okm.slice(32, 64));
  }
}

export async function beginHandshake(
  staticPriv: Uint8Array,
  staticPub: Uint8Array,
  peerStaticPub: Uint8Array,
  psk: Uint8Array,
): Promise<E2EInitiator> {
  const eph = await E2ECrypto.generateKeyPair();
  return new E2EInitiator(staticPriv, staticPub, peerStaticPub, psk, eph);
}

/**
 * 全链路握手自检：本机同时扮演 initiator（device）与 responder（daemon），
 * 验证 4-DH 推导双向一致 + transport 帧双向 seal/open + 重放拒绝。
 * 配对页展示——无需 daemon 即可在真机上定位握手类故障。返回失败项（空 = 通过）。
 */
export async function selfTestHandshake(): Promise<string[]> {
  const failures = new Array<string>();
  try {
    const iStatic = await E2ECrypto.generateKeyPair(); // device 静态
    const rStatic = await E2ECrypto.generateKeyPair(); // daemon 静态
    const psk = new Uint8Array([1, 2, 3, 4]);
    const init = await beginHandshake(iStatic.privateRaw, iStatic.publicRaw, rStatic.publicRaw, psk);
    const rEph = await E2ECrypto.generateKeyPair(); // daemon 临时（msg2）

    // initiator 侧（被测路径）
    const sessI = await init.finish(rEph.publicRaw);

    // responder 侧：镜像 finish() 的四个 DH（es/ss/ee/se 含义见上），密钥方向互换
    const es = E2ECrypto.agree(rStatic.privateRaw, init.ephPublic);   // DH(E_i, S_r)
    const ss = E2ECrypto.agree(rStatic.privateRaw, iStatic.publicRaw); // DH(S_i, S_r)
    const ee = E2ECrypto.agree(rEph.privateRaw, init.ephPublic);       // DH(E_i, E_r)
    const se = E2ECrypto.agree(rEph.privateRaw, iStatic.publicRaw);    // DH(S_i, E_r)
    const transcript = concatBytes(PROLOGUE, iStatic.publicRaw, rStatic.publicRaw, init.ephPublic, rEph.publicRaw);
    const okm = await E2ECrypto.hkdf(concatBytes(es, ss, ee, se, psk), transcript, KEYINFO, 64);
    // responder 发送用 k_r2i（okm 后 32B），接收用 k_i2r
    const sessR = new E2ESession(okm.slice(32, 64), okm.slice(0, 32));

    // i → r
    const msg1 = new Uint8Array([10, 20, 30]);
    const sealed1 = await sessI.seal(msg1);
    const opened1 = await sessR.open(sealed1);
    if (opened1 === null || opened1.length !== 3 || opened1[0] !== 10) failures.push('hs-i2r');
    // r → i
    const sealed2 = await sessR.seal(new Uint8Array([40, 50]));
    const opened2 = await sessI.open(sealed2);
    if (opened2 === null || opened2.length !== 2 || opened2[0] !== 40) failures.push('hs-r2i');
    // 重放拒绝：同一帧再喂一次必须拒
    if ((await sessR.open(sealed1)) !== null) failures.push('hs-replay-accepted');
  } catch (e) {
    const err = e as Error;
    failures.push(`hs-throw:${err.message} @ ${(err.stack ?? '').split('\n')[1] ?? ''}`);
  }
  return failures;
}

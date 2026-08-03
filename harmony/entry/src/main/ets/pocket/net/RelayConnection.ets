import { webSocket } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { E2ESession, E2EInitiator, beginHandshake } from '../crypto/E2ESession';
import { E2EKeyPair } from '../crypto/E2ECrypto';
import { PairedDaemon } from '../store/Store';
import { b64UrlDecode } from '../util/B64Url';
import { concatBytes, fromUtf8, utf8 } from '../util/Bytes';
import { controlDeviceHello, controlPing, envelopeJson, parseEnvelope } from '../protocol/Protocol';
import { hilog } from '@kit.PerformanceAnalysisKit';

/**
 * relay E2E 连接（ArkTS 移植 mobile/net/RelayE2EConnection.kt）。
 *
 * 时序：ws 连 {relay}/v1/device
 *   → TEXT 发 Envelope{to:RELAY, body:DeviceHello}
 *   → 等 TEXT pocket/attached（或 pocket/auth.error）
 *   → BINARY 发 [0x01]‖e_i.pub（Wire.HANDSHAKE）
 *   → 等 BINARY [0x01]‖e_r.pub → 4-DH 推导会话密钥
 *   → BINARY [0x02]‖[8B ctr]‖AES-GCM(Envelope JSON) 双向 transport
 *
 * 心跳：HarmonyOS webSocket 不暴露协议层 ping，用应用层 pocket/ping（relay 会回 pong），
 * 正好就是协议里为"探测僵尸连接"设计的帧（见 Messages.kt Ping 注释）。
 */

const TAG = 'ccpocket';
const HANDSHAKE_TIMEOUT_MS = 15000;
const PING_INTERVAL_MS = 20000;
const PONG_TIMEOUT_MS = 10000;
const PONG_MAX_MISSES = 2; // 连续未收到 pong 达此次数才判定掉线（配合 relay Ping 回显，抖动时少一次全量重连）

export interface RelayCallbacks {
  /** 完成 Attached + E2E 握手，transport 可用。 */
  onReady: () => void;
  /** 收到一帧 daemon→phone 的业务帧（body 已 JSON.parse）。 */
  onFrame: (body: Record<string, Object>) => void;
  /** relay 控制帧（PeerPresence 等；Attached/AuthError 在握手期内已消费）。 */
  onControl: (body: Record<string, Object>) => void;
  /** 连接关闭/失败（含握手超时、心跳超时）；reason 供日志与状态展示。 */
  onDown: (reason: string) => void;
  /** daemon 上线状态变化（pocket/peer.presence）。 */
  onPeerPresence: (online: boolean) => void;
}

export class RelayAuthException extends Error {
  code: string;

  constructor(code: string) {
    super(`relay auth failed: ${code}`);
    this.code = code;
  }
}

type Phase = 'hello' | 'handshake' | 'transport' | 'closed';

export class RelayConnection {
  private ws: webSocket.WebSocket | null = null;
  private phase: Phase = 'hello';
  private session: E2ESession | null = null;
  private initiator: E2EInitiator | null = null;
  private outbox: string[] = []; // 已 JSON 序列化的 Envelope，握手完成前缓冲
  private nextId = 0;
  private pingTimer = -1;
  private handshakeTimer = -1;
  private pongTimer = -1;
  private pongMisses = 0; // 连续未命中 pong 计数；收到 pong 或停止心跳清零（见 armPongWait/onPongTimeout）
  private alive = false; // 本连接是否仍被需要（主动断开后回调全部丢弃）
  // 出/入站各持一条 promise 链，把 AES seal/open 串行化：保证「取号顺序 = 上线顺序」「按到达顺序解密投递」，
  // 否则各帧异步加密完成先后不定 → daemon 严格递增计数器检查把迟到的小计数器帧当重放静默丢弃（对齐 Kotlin 单协程写端）。
  private sendChain: Promise<void> = Promise.resolve();
  private recvChain: Promise<void> = Promise.resolve();
  private cb: RelayCallbacks;

  constructor(cb: RelayCallbacks) {
    this.cb = cb;
  }

  /** 建立连接并完成握手。失败走 cb.onDown（auth 失败先抛 RelayAuthException 语义经 reason 传达）。 */
  async connect(paired: PairedDaemon, keys: E2EKeyPair, firstTicket: string): Promise<void> {
    this.alive = true;
    this.phase = 'hello';
    const ws = webSocket.createWebSocket();
    this.ws = ws;

    ws.on('open', (err: BusinessError) => {
      if (!this.alive) return;
      if (err) {
        this.fail(`open:${err.code}`);
        return;
      }
      // 认证头：DeviceHello
      this.sendText(controlDeviceHello(paired.deviceId, paired.credential));
      // 握手总超时（对齐 Kotlin HANDSHAKE 窗口外的 connect watchdog）
      this.handshakeTimer = setTimeout(() => this.fail('handshake-timeout'), HANDSHAKE_TIMEOUT_MS);
    });

    ws.on('message', (err: BusinessError, value: string | ArrayBuffer) => {
      if (!this.alive || err) return;
      if (typeof value === 'string') {
        this.onText(value, paired, keys, firstTicket);
      } else {
        this.onBinary(new Uint8Array(value));
      }
    });

    ws.on('close', (err: BusinessError) => {
      if (!this.alive) return;
      this.fail(`close:${err?.code ?? 'eof'}`);
    });

    ws.on('error', (err: BusinessError) => {
      if (!this.alive) return;
      this.fail(`error:${err?.code ?? 'unknown'}`);
    });

    ws.connect(`${paired.relay}/v1/device`, (err: BusinessError) => {
      if (!this.alive) return;
      if (err) this.fail(`connect:${err.code}`);
    });
  }

  // ── 入站 ──

  private onText(text: string, paired: PairedDaemon, keys: E2EKeyPair, firstTicket: string): void {
    const body = parseEnvelope(text);
    if (body === null) return;
    const t = typeof body['t'] === 'string' ? (body['t'] as string) : '';
    if (this.phase === 'hello') {
      if (t === 'pocket/attached') {
        // 开始 E2E 握手（initiator）
        this.startHandshake(paired, keys, firstTicket);
      } else if (t === 'pocket/auth.error') {
        const code = typeof body['code'] === 'string' ? (body['code'] as string) : 'unknown';
        this.fail(`auth:${code}`);
      }
      return;
    }
    // transport 阶段的 TEXT = relay 控制面
    if (t === 'pocket/pong') {
      this.armPongWait(false);
    } else if (t === 'pocket/peer.presence') {
      this.cb.onPeerPresence(body['online'] === true);
    } else {
      this.cb.onControl(body);
    }
  }

  private onBinary(data: Uint8Array): void {
    if (data.length === 0) return;
    const type = data[0];
    const payload = data.slice(1);
    if (this.phase === 'handshake' && type === 1) {
      // msg2：responder 临时公钥 → 推导会话
      const init = this.initiator;
      if (init === null) return;
      init.finish(payload).then((session) => {
        if (!this.alive) return;
        this.session = session;
        this.phase = 'transport';
        clearTimeout(this.handshakeTimer);
        this.handshakeTimer = -1;
        this.startPing();
        this.cb.onReady();
        this.flushOutbox();
      }).catch((e: Error) => this.fail(`hs-derive:${e.message} @ ${(e.stack ?? '').split('\n')[1] ?? ''}`));
      return;
    }
    if (this.phase === 'transport' && type === 2 && this.session !== null) {
      const session = this.session;
      // 进入 recvChain：open→dispatch 按到达顺序串行，避免异步解密乱序回写 recvCtr 小值 + 乱序投递。
      this.recvChain = this.recvChain.then((): Promise<void> => {
        if (!this.alive || this.session === null) return Promise.resolve();
        return session.open(payload).then((pt) => {
          if (pt === null) {
            hilog.warn(0x0000, TAG, 'transport frame dropped (decrypt/replay)');
            return;
          }
          try {
            const env = JSON.parse(fromUtf8(pt)) as Record<string, Object>;
            const body = (env['body'] ?? {}) as Record<string, Object>;
            this.cb.onFrame(body);
          } catch (e) {
            hilog.warn(0x0000, TAG, 'bad envelope json');
          }
        });
      }).catch((e: Error) => this.fail(`decrypt:${e.message}`));
    }
  }

  private startHandshake(paired: PairedDaemon, keys: E2EKeyPair, firstTicket: string): void {
    // 防御：空票据（重连）直接给空 PSK，绝不把空串喂给 TextEncoder（实测 encodeInto('') 行为不可靠）
    const psk = firstTicket.length > 0 ? utf8(firstTicket) : new Uint8Array(0);
    beginHandshake(keys.privateRaw, keys.publicRaw, b64UrlDecode(paired.daemonPub), psk)
      .then((init) => {
        if (!this.alive) return;
        this.initiator = init;
        this.phase = 'handshake';
        this.sendBinary(concatBytes(new Uint8Array([1]), init.ephPublic));
      })
      .catch((e: Error) => this.fail(`hs-begin:${e.message}`));
  }

  // ── 出站 ──

  /** 控制面帧（RELAY 路由）：ping / push.register 等，TEXT 明文发送，不过 E2E。 */
  sendControl(text: string): void {
    this.sendText(text);
  }

  /** 业务帧（phone→daemon）。握手完成前缓冲，完成后按序补发（对齐 Kotlin outbox）。 */
  send(body: Object): void {
    const json = envelopeJson(body, 'PEER', `${this.nextId++}`);
    if (this.phase !== 'transport') {
      this.outbox.push(json);
      return;
    }
    this.sealAndSend(json);
  }

  private flushOutbox(): void {
    const pending = this.outbox;
    this.outbox = [];
    for (const json of pending) this.sealAndSend(json);
  }

  private sealAndSend(envelopeJsonText: string): void {
    const session = this.session;
    if (session === null) return;
    // 进入 sendChain：把 seal 的「取号 + 上线」整体串行，保证取号顺序 = 上线顺序。
    // .catch 正常返回 → 链复位为 resolved，一次失败不永久卡死后续帧（fail 已置 alive=false，后续帧自动短路）。
    this.sendChain = this.sendChain.then((): Promise<void> => {
      if (!this.alive || this.session === null) return Promise.resolve();
      return session.seal(utf8(envelopeJsonText)).then((sealed) => {
        if (this.alive) {
          this.sendBinary(concatBytes(new Uint8Array([2]), sealed));
        }
      });
    }).catch((e: Error) => this.fail(`seal:${e.message}`));
  }

  private sendText(text: string): void {
    this.ws?.send(text, (err: BusinessError) => {
      if (err) hilog.warn(0x0000, TAG, `ws send text failed: ${err.code}`);
    });
  }

  private sendBinary(data: Uint8Array): void {
    this.ws?.send(data.buffer as ArrayBuffer, (err: BusinessError) => {
      if (err) hilog.warn(0x0000, TAG, `ws send bin failed: ${err.code}`);
    });
  }

  // ── 心跳（应用层 pocket/ping） ──

  private startPing(): void {
    this.stopPing();
    this.pingTimer = setInterval(() => {
      this.sendText(controlPing(Date.now()));
      this.armPongWait(true);
    }, PING_INTERVAL_MS);
  }

  private armPongWait(arm: boolean): void {
    if (this.pongTimer >= 0) {
      clearTimeout(this.pongTimer);
      this.pongTimer = -1;
    }
    if (arm) {
      this.pongTimer = setTimeout(() => this.onPongTimeout(), PONG_TIMEOUT_MS);
    } else {
      this.pongMisses = 0; // 收到 pong（或停止心跳）→ 清零连续未命中计数
    }
  }

  private onPongTimeout(): void {
    this.pongTimer = -1;
    this.pongMisses++;
    // 未达阈值不立即断连：下一个 ping 周期（PING_INTERVAL_MS > PONG_TIMEOUT_MS）会再次 armPongWait 复探。
    if (this.pongMisses >= PONG_MAX_MISSES) {
      this.fail('pong-timeout');
    }
  }

  private stopPing(): void {
    if (this.pingTimer >= 0) {
      clearInterval(this.pingTimer);
      this.pingTimer = -1;
    }
    this.armPongWait(false);
  }

  // ── 生命周期 ──

  private fail(reason: string): void {
    if (!this.alive) return;
    this.alive = false;
    this.teardown();
    this.cb.onDown(reason);
  }

  /** 主动断开（切账号 / 解配）；之后所有回调静默。 */
  disconnect(): void {
    this.alive = false;
    this.teardown();
  }

  private teardown(): void {
    this.phase = 'closed';
    this.stopPing();
    if (this.handshakeTimer >= 0) {
      clearTimeout(this.handshakeTimer);
      this.handshakeTimer = -1;
    }
    const ws = this.ws;
    this.ws = null;
    if (ws !== null) {
      ws.off('open');
      ws.off('message');
      ws.off('close');
      ws.off('error');
      ws.close(() => {});
    }
    this.session = null;
    this.initiator = null;
    this.outbox = [];
    this.sendChain = Promise.resolve(); // 复位串行链，避免复用连接对象时残留旧的 rejected/pending 态
    this.recvChain = Promise.resolve();
    this.pongMisses = 0;
  }
}

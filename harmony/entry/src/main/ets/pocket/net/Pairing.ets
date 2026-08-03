import { http } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { b64UrlEncode } from '../util/B64Url';
import { E2EKeyPair } from '../crypto/E2ECrypto';
import { PairedDaemon, Store } from '../store/Store';
import { jo, js } from '../protocol/Protocol';

/**
 * 配对（ArkTS 移植 mobile/pairing/Pairing.kt 的网络部分）。
 *
 * 流程：6 位码 → POST /v1/pair/code → {accountId, daemonPub, ticket}
 *      → POST /v1/pair/redeem {ticket, devicePubKey} → {deviceId, credential, accountId}
 *      → 持久化 PairedDaemon + 首连 PSK（ticket）。
 */

/** 与 Kotlin Pairing.DEFAULT_RELAY 一致。 */
export const DEFAULT_RELAY = 'wss://pocket.ark-nexus.cc';

function httpBase(relay: string): string {
  return relay.replace('wss://', 'https://').replace('ws://', 'http://');
}

async function postJson(url: string, body: string): Promise<string> {
  const req = http.createHttp();
  try {
    const resp = await req.request(url, {
      method: http.RequestMethod.POST,
      extraData: body,
      header: { 'content-type': 'application/json' },
      connectTimeout: 15000,
      readTimeout: 15000,
      expectDataType: http.HttpDataType.STRING,
    });
    if (resp.responseCode < 200 || resp.responseCode >= 300) {
      throw new Error(`HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 120)}`);
    }
    return String(resp.result);
  } catch (e) {
    // NetworkKit 的 BusinessError.message 常为空，code 才是定位关键（2300006=DNS…）
    const be = e as BusinessError;
    if (typeof be.code === 'number') throw new Error(`net:${be.code} ${be.message ?? ''}`);
    throw e instanceof Error ? e : new Error(String(e));
  } finally {
    req.destroy();
  }
}

/** POST /v1/pair/code 的响应。 */
export interface ResolvedCode {
  accountId: string;
  daemonPub: string;
  ticket: string;
}

/** ccpocket://pair?relay=&acct=&dpk=&ticket= 扫码链接解析结果。 */
export interface PairLink {
  relay: string;
  accountId: string;
  daemonPub: string;
  ticket: string;
}

interface PairCodeReq {
  code: string;
}

interface PairRedeemReq {
  ticket: string;
  devicePubKey: string;
}

export class Pairing {
  /** 解析用户输入的 6 位配对码（relay 辅助路径）。 */
  static async resolveCode(code: string): Promise<ResolvedCode> {
    const reqBody: PairCodeReq = { code };
    const text = await postJson(`${httpBase(DEFAULT_RELAY)}/v1/pair/code`, JSON.stringify(reqBody));
    const o = jo(JSON.parse(text));
    const accountId = js(o['accountId']);
    const daemonPub = js(o['daemonPub']);
    const ticket = js(o['ticket']);
    if (accountId.length === 0 || daemonPub.length === 0 || ticket.length === 0) {
      throw new Error('invalid or expired code');
    }
    const resolved: ResolvedCode = { accountId, daemonPub, ticket };
    return resolved;
  }

  /**
   * 解析 daemon 二维码编码的完整配对链接：
   * ccpocket://pair?relay=<wss>&acct=<id>&dpk=<daemon e2e pub>&ticket=<one-time>
   * 与 Kotlin Pairing.parse 一致：不 URL 解码（daemon 生成时不编码）。
   * 缺任一字段 / 是 ?code= 短链 → null（短链走 resolveCode 路径）。
   */
  static parseLink(url: string): PairLink | null {
    const q = url.indexOf('?');
    if (q < 0) return null;
    const m = new Map<string, string>();
    const pairs = url.substring(q + 1).split('&');
    for (const p of pairs) {
      const i = p.indexOf('=');
      if (i > 0) m.set(p.substring(0, i), p.substring(i + 1));
    }
    const relay = m.get('relay');
    const acct = m.get('acct');
    const dpk = m.get('dpk');
    const ticket = m.get('ticket');
    if (relay === undefined || acct === undefined || dpk === undefined || ticket === undefined) return null;
    const link: PairLink = { relay, accountId: acct, daemonPub: dpk, ticket };
    return link;
  }

  /** 兑换票据：注册本设备公钥，拿到设备凭证，持久化配对记录。 */
  static async redeem(relay: string, accountId: string, daemonPub: string, ticket: string, keys: E2EKeyPair): Promise<PairedDaemon> {
    const reqBody: PairRedeemReq = { ticket, devicePubKey: b64UrlEncode(keys.publicRaw) };
    const body = JSON.stringify(reqBody);
    const text = await postJson(`${httpBase(relay)}/v1/pair/redeem`, body);
    const o = jo(JSON.parse(text));
    const deviceId = js(o['deviceId']);
    const credential = js(o['credential']);
    if (deviceId.length === 0 || credential.length === 0) {
      throw new Error('pairing failed');
    }
    const paired = new PairedDaemon(relay, accountId, daemonPub, deviceId, credential);
    await Store.savePaired(paired, ticket);
    return paired;
  }
}

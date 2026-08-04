import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { b64UrlDecode, b64UrlEncode } from '../util/B64Url';
import { fromUtf8, utf8 } from '../util/Bytes';
import { E2ECrypto, E2EKeyPair } from '../crypto/E2ECrypto';
import { Vault } from './Vault';

/**
 * 配对记录与安全存储（ArkTS 移植 mobile/pairing/Pairing.kt 的存储部分）。
 *
 * 机密分级（hardening 已落地）：
 * - 设备私钥 / relay credential / 首连票据 → **Asset Store**（系统加密，TEE 保护，
 *   不随明文 preferences 进云备份），对齐 Android Keystore / iOS Keychain；
 * - 非机密（公钥、绑定元数据、主题/模式设置）→ preferences。
 * 老版本的明文数据在首次启动时由 migrateToVault() 透明搬迁；
 * Asset Store 不可用（模拟器/服务异常）时回落明文并记 warn，功能不中断。
 */

/** 配对结果——重连所需的全部。对齐 Kotlin PairedDaemon（字段子集）。 */
export class PairedDaemon {
  relay: string;
  accountId: string;
  daemonPub: string; // base64url P-256 公钥（带外学到，认证 daemon 身份）
  deviceId: string;
  credential: string; // relay bearer 凭证（hydrated 时才有；preferences 落盘版本恒为空串）
  hostName: string | null;

  constructor(
    relay: string,
    accountId: string,
    daemonPub: string,
    deviceId: string,
    credential: string,
    hostName: string | null = null,
  ) {
    this.relay = relay;
    this.accountId = accountId;
    this.daemonPub = daemonPub;
    this.deviceId = deviceId;
    this.credential = credential;
    this.hostName = hostName;
  }

  withoutCredential(): PairedDaemon {
    return new PairedDaemon(this.relay, this.accountId, this.daemonPub, this.deviceId, '', this.hostName);
  }

  withCredential(cred: string): PairedDaemon {
    return new PairedDaemon(this.relay, this.accountId, this.daemonPub, this.deviceId, cred, this.hostName);
  }

  toJson(): string {
    return JSON.stringify(this);
  }

  static fromJson(text: string): PairedDaemon | null {
    try {
      return PairedDaemon.fromObject(JSON.parse(text) as Record<string, Object>);
    } catch (e) {
      return null;
    }
  }

  static fromObject(o: Record<string, Object>): PairedDaemon | null {
    try {
      const hostName = typeof o['hostName'] === 'string' ? (o['hostName'] as string) : null;
      const credential = typeof o['credential'] === 'string' ? (o['credential'] as string) : '';
      return new PairedDaemon(
        String(o['relay']), String(o['accountId']), String(o['daemonPub']),
        String(o['deviceId']), credential, hostName);
    } catch (e) {
      return null;
    }
  }

  displayName(): string {
    if (this.hostName !== null && this.hostName.length > 0) return this.hostName;
    return `${this.accountId.substring(0, 12)}…`;
  }
}

const TAG = 'ccpocket';

const K_PRIV = 'device_priv';   // 仅遗留/回落：正常应只在 Vault
const K_PUB = 'device_pub';
const K_PAIRED = 'paired_daemon'; // 兼容字段：始终镜像 active 绑定（旧版本只认它）
const K_BINDINGS = 'bindings';    // PairedDaemon JSON 数组（多电脑；credential 恒剥离）
const K_ACTIVE = 'active_binding'; // active 的 accountId
const K_FIRST_TICKET = 'first_ticket'; // 仅遗留/回落：正常应只在 Vault
const K_THEME_MODE = 'theme_mode'; // 'dark' | 'light' | 'system'（缺省 dark，对齐 Android 默认）
// 新会话默认权限模式：'default'|'auto'|'acceptEdits'|'plan'|'bypassPermissions'（对齐 defaultMode）
const K_DEFAULT_MODE = 'default_mode';

// Vault 别名
const V_DEVICE_PRIV = 'device_priv';
const V_FIRST_TICKET = 'first_ticket';

export class Store {
  private static prefs: preferences.Preferences | null = null;

  static init(context: common.UIAbilityContext): void {
    if (Store.prefs === null) {
      Store.prefs = preferences.getPreferencesSync(context, { name: 'ccpocket' });
    }
  }

  private static p(): preferences.Preferences {
    if (Store.prefs === null) throw new Error('Store.init() not called');
    return Store.prefs;
  }

  private static getString(key: string): string | null {
    const v = Store.p().getSync(key, '');
    const s = String(v);
    return s.length > 0 ? s : null;
  }

  private static putString(key: string, value: string): void {
    Store.p().putSync(key, value);
    Store.p().flush();
  }

  private static remove(key: string): void {
    Store.p().deleteSync(key);
    Store.p().flush();
  }

  private static credAlias(accountId: string): string {
    return `cred_${accountId}`;
  }

  // ═══ 老版本明文 → Vault 透明迁移（幂等；Vault 失败则保持明文回落） ═══

  static async migrateToVault(): Promise<void> {
    // 设备私钥
    const priv = Store.getString(K_PRIV);
    if (priv !== null) {
      if (await Vault.saveVerified(V_DEVICE_PRIV, b64UrlDecode(priv))) {
        Store.remove(K_PRIV);
      }
    }
    // 绑定列表里的 credential
    const text = Store.getString(K_BINDINGS);
    if (text !== null) {
      try {
        const arr = JSON.parse(text) as Object[];
        let changed = false;
        const out: Object[] = [];
        for (const o of arr) {
          let d = PairedDaemon.fromObject(o as Record<string, Object>);
          if (d === null) continue;
          if (d.credential.length > 0 && await Vault.saveVerified(Store.credAlias(d.accountId), utf8(d.credential))) {
            d = d.withoutCredential();
            changed = true;
          }
          out.push(JSON.parse(d.toJson()) as Object);
        }
        if (changed) Store.putString(K_BINDINGS, JSON.stringify(out));
      } catch (e) {
        // 解析失败留给 loadBindings 的容错路径
      }
    }
    // 兼容镜像
    const single = Store.getString(K_PAIRED);
    if (single !== null) {
      const d = PairedDaemon.fromJson(single);
      if (d !== null && d.credential.length > 0) {
        if (await Vault.saveVerified(Store.credAlias(d.accountId), utf8(d.credential))) {
          Store.putString(K_PAIRED, d.withoutCredential().toJson());
        }
      }
    }
    // 首连票据
    const ticket = Store.getString(K_FIRST_TICKET);
    if (ticket !== null) {
      if (await Vault.saveVerified(V_FIRST_TICKET, utf8(ticket))) {
        Store.remove(K_FIRST_TICKET);
      }
    }
  }

  // ═══ 设备密钥 ═══

  /** 本设备长期 P-256 密钥对：私钥在 Vault，公钥在 preferences（公钥非机密）。 */
  static async deviceKeys(): Promise<E2EKeyPair> {
    const pub = Store.getString(K_PUB);
    const vpriv = await Vault.load(V_DEVICE_PRIV);
    if (vpriv !== null && pub !== null) {
      return new E2EKeyPair(vpriv, b64UrlDecode(pub));
    }
    // 遗留明文（migrateToVault 未跑或失败）
    const legacyPriv = Store.getString(K_PRIV);
    if (legacyPriv !== null && pub !== null) {
      const priv = b64UrlDecode(legacyPriv);
      if (await Vault.saveVerified(V_DEVICE_PRIV, priv)) {
        Store.remove(K_PRIV);
      }
      return new E2EKeyPair(priv, b64UrlDecode(pub));
    }
    const kp = await E2ECrypto.generateKeyPair();
    if (!(await Vault.saveVerified(V_DEVICE_PRIV, kp.privateRaw))) {
      Store.putString(K_PRIV, b64UrlEncode(kp.privateRaw)); // 明文回落（Vault 已记 warn）
    }
    Store.putString(K_PUB, b64UrlEncode(kp.publicRaw));
    return kp;
  }

  // ═══ 绑定（多电脑） ═══

  /** 新配对 upsert 进绑定列表并设为 active；credential 落 Vault，preferences 只存剥离副本。 */
  static async savePaired(p: PairedDaemon, firstTicket: string): Promise<void> {
    const stored = await Store.persistCredential(p);
    const list = Store.loadBindings().filter((b: PairedDaemon) => b.accountId !== p.accountId);
    list.push(stored);
    Store.writeBindings(list, p.accountId);
    if (firstTicket.length > 0) {
      if (!(await Vault.saveVerified(V_FIRST_TICKET, utf8(firstTicket)))) {
        Store.putString(K_FIRST_TICKET, firstTicket);
      }
    }
  }

  /** credential 进 Vault；返回可安全落 preferences 的副本（Vault 失败时保留明文，Vault 已记 warn）。 */
  private static async persistCredential(p: PairedDaemon): Promise<PairedDaemon> {
    if (p.credential.length === 0) return p;
    if (await Vault.saveVerified(Store.credAlias(p.accountId), utf8(p.credential))) {
      return p.withoutCredential();
    }
    return p;
  }

  /** 全部绑定（元数据；credential 可能为空串——连接前用 credentialOf 补齐）。 */
  static loadBindings(): PairedDaemon[] {
    const text = Store.getString(K_BINDINGS);
    if (text !== null) {
      try {
        const arr = JSON.parse(text) as Object[];
        const out: PairedDaemon[] = [];
        for (const o of arr) {
          const d = PairedDaemon.fromObject(o as Record<string, Object>);
          if (d !== null) out.push(d);
        }
        if (out.length > 0) return out;
      } catch (e) {
        // fall through to legacy
      }
    }
    const single = PairedDaemon.fromJson(Store.getString(K_PAIRED) ?? '');
    return single !== null ? [single] : [];
  }

  /** active 绑定；未显式设置时取列表第一个。 */
  static loadActive(): PairedDaemon | null {
    const list = Store.loadBindings();
    if (list.length === 0) return null;
    const activeId = Store.getString(K_ACTIVE);
    const found = list.find((b: PairedDaemon) => b.accountId === activeId);
    return found ?? list[0];
  }

  /** 连接的 credential：对象上没有就去 Vault 取（取不到 = 被清/未迁移，返回空串走 auth 失败重配）。 */
  static async credentialOf(p: PairedDaemon): Promise<string> {
    if (p.credential.length > 0) return p.credential;
    const v = await Vault.load(Store.credAlias(p.accountId));
    return v !== null ? fromUtf8(v) : '';
  }

  static setActive(accountId: string): void {
    const list = Store.loadBindings();
    const found = list.find((b: PairedDaemon) => b.accountId === accountId);
    if (found === undefined) return;
    Store.writeBindings(list, accountId);
  }

  /** 删除一个绑定（含 Vault 里的 credential）；返回删除后应激活的绑定（没有剩余则 null）。 */
  static removeBinding(accountId: string): PairedDaemon | null {
    Vault.remove(Store.credAlias(accountId));
    const list = Store.loadBindings().filter((b: PairedDaemon) => b.accountId !== accountId);
    Store.writeBindings(list, list.length > 0 ? list[0].accountId : '');
    if (list.length === 0) Store.remove(K_FIRST_TICKET);
    return list.length > 0 ? list[0] : null;
  }

  /** 写绑定列表 + active + 兼容镜像（K_PAIRED 始终 = active）。落盘前一律剥离 credential。 */
  private static writeBindings(list: PairedDaemon[], activeId: string): void {
    const safe = list.map((b: PairedDaemon) => b.credential.length > 0 ? b.withoutCredential() : b);
    Store.putString(K_BINDINGS, JSON.stringify(safe.map((b: PairedDaemon) => JSON.parse(b.toJson()) as Object)));
    if (activeId.length > 0) Store.putString(K_ACTIVE, activeId);
    const active = safe.find((b: PairedDaemon) => b.accountId === activeId) ?? (safe.length > 0 ? safe[0] : null);
    if (active !== null) {
      Store.putString(K_PAIRED, active.toJson());
    } else {
      Store.remove(K_PAIRED);
    }
  }

  static loadPaired(): PairedDaemon | null {
    return Store.loadActive();
  }

  /** 首连握手用的 PSK；取出即焚（Vault 优先，遗留明文兜底）。 */
  static async takeFirstTicket(): Promise<string> {
    const v = await Vault.load(V_FIRST_TICKET);
    if (v !== null) {
      Vault.remove(V_FIRST_TICKET);
      return fromUtf8(v);
    }
    const t = Store.getString(K_FIRST_TICKET) ?? '';
    if (t.length > 0) Store.remove(K_FIRST_TICKET);
    return t;
  }

  static saveHostName(name: string): void {
    const list = Store.loadBindings();
    const activeId = Store.getString(K_ACTIVE);
    const target = list.find((b: PairedDaemon) => b.accountId === activeId) ?? (list.length > 0 ? list[0] : null);
    if (target === null) return;
    target.hostName = name;
    Store.writeBindings(list, target.accountId);
  }

  static unpair(): void {
    const active = Store.loadActive();
    if (active !== null) {
      Store.removeBinding(active.accountId);
    } else {
      Store.remove(K_PAIRED);
      Store.remove(K_FIRST_TICKET);
    }
  }

  // ═══ 设置 ═══

  /** 主题模式（'dark'|'light'|'system'）；缺省 dark（App 一直是深色，不回归老用户）。 */
  static loadThemeMode(): string {
    return Store.getString(K_THEME_MODE) ?? 'dark';
  }

  static saveThemeMode(mode: string): void {
    Store.putString(K_THEME_MODE, mode);
  }

  /** 新会话默认权限模式（缺省 'default' = 询问）。 */
  static loadDefaultMode(): string {
    return Store.getString(K_DEFAULT_MODE) ?? 'default';
  }

  static saveDefaultMode(mode: string): void {
    Store.putString(K_DEFAULT_MODE, mode);
  }
}

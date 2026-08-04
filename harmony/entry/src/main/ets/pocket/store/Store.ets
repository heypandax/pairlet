import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { b64UrlDecode, b64UrlEncode } from '../util/B64Url';
import { E2ECrypto, E2EKeyPair } from '../crypto/E2ECrypto';

/**
 * 配对记录与安全存储（ArkTS 移植 mobile/pairing/Pairing.kt 的存储部分）。
 *
 * ⚠️ spike 用 preferences 明文落盘（含设备私钥 / relay 凭证）。对齐 Android 的
 * EncryptedSharedPreferences / iOS Keychain 之前，生产化必须换 HUKS / Asset Store
 * （@kit.UniversalKeystoreKit）——TODO(hardening)。
 */

/** 配对结果——重连所需的全部。对齐 Kotlin PairedDaemon（字段子集）。 */
export class PairedDaemon {
  relay: string;
  accountId: string;
  daemonPub: string; // base64url P-256 公钥（带外学到，认证 daemon 身份）
  deviceId: string;
  credential: string; // relay bearer 凭证
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
      return new PairedDaemon(
        String(o['relay']), String(o['accountId']), String(o['daemonPub']),
        String(o['deviceId']), String(o['credential']), hostName);
    } catch (e) {
      return null;
    }
  }

  displayName(): string {
    if (this.hostName !== null && this.hostName.length > 0) return this.hostName;
    return `${this.accountId.substring(0, 12)}…`;
  }
}

const K_PRIV = 'device_priv';
const K_PUB = 'device_pub';
const K_PAIRED = 'paired_daemon'; // 兼容字段：始终镜像 active 绑定（旧版本只认它）
const K_BINDINGS = 'bindings';    // PairedDaemon JSON 数组（多电脑）
const K_ACTIVE = 'active_binding'; // active 的 accountId
// 握手 PSK 只用一次的配对票据：首连成功后必须丢弃（对齐 Kotlin 的 firstTicket 语义）
const K_FIRST_TICKET = 'first_ticket';
const K_THEME_MODE = 'theme_mode'; // 'dark' | 'light' | 'system'（缺省 dark，对齐 Android 默认）
// 新会话默认权限模式：'default'|'auto'|'acceptEdits'|'plan'|'bypassPermissions'（对齐 defaultMode）
const K_DEFAULT_MODE = 'default_mode';

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

  /** 本设备长期 P-256 密钥对：首次生成后持久化（对齐 Pairing.deviceKeys()）。 */
  static async deviceKeys(): Promise<E2EKeyPair> {
    const priv = Store.getString(K_PRIV);
    const pub = Store.getString(K_PUB);
    if (priv !== null && pub !== null) {
      return new E2EKeyPair(b64UrlDecode(priv), b64UrlDecode(pub));
    }
    const kp = await E2ECrypto.generateKeyPair();
    Store.putString(K_PRIV, b64UrlEncode(kp.privateRaw));
    Store.putString(K_PUB, b64UrlEncode(kp.publicRaw));
    return kp;
  }

  /** 新配对 upsert 进绑定列表并设为 active（重复配对同账号 = 覆盖旧凭证）。 */
  static savePaired(p: PairedDaemon, firstTicket: string): void {
    const list = Store.loadBindings().filter((b: PairedDaemon) => b.accountId !== p.accountId);
    list.push(p);
    Store.writeBindings(list, p.accountId);
    Store.putString(K_FIRST_TICKET, firstTicket);
  }

  /** 全部绑定。旧版本只有单条 K_PAIRED 的，读时透明迁移进列表。 */
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

  static setActive(accountId: string): void {
    const list = Store.loadBindings();
    const found = list.find((b: PairedDaemon) => b.accountId === accountId);
    if (found === undefined) return;
    Store.writeBindings(list, accountId);
  }

  /** 删除一个绑定；返回删除后应激活的绑定（没有剩余则 null）。 */
  static removeBinding(accountId: string): PairedDaemon | null {
    const list = Store.loadBindings().filter((b: PairedDaemon) => b.accountId !== accountId);
    Store.writeBindings(list, list.length > 0 ? list[0].accountId : '');
    if (list.length === 0) Store.remove(K_FIRST_TICKET);
    return list.length > 0 ? list[0] : null;
  }

  /** 写绑定列表 + active + 兼容镜像（K_PAIRED 始终 = active）。 */
  private static writeBindings(list: PairedDaemon[], activeId: string): void {
    Store.putString(K_BINDINGS, JSON.stringify(list.map((b: PairedDaemon) => JSON.parse(b.toJson()) as Object)));
    if (activeId.length > 0) Store.putString(K_ACTIVE, activeId);
    const active = list.find((b: PairedDaemon) => b.accountId === activeId) ?? (list.length > 0 ? list[0] : null);
    if (active !== null) {
      Store.putString(K_PAIRED, active.toJson());
    } else {
      Store.remove(K_PAIRED);
    }
  }

  static loadPaired(): PairedDaemon | null {
    return Store.loadActive();
  }

  /** 首连握手用的 PSK；取出即焚。 */
  static takeFirstTicket(): string {
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

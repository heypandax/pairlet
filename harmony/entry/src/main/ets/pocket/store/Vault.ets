import { asset } from '@kit.AssetStoreKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { utf8 } from '../util/Bytes';

/**
 * Asset Store（鸿蒙 Keychain）封装：敏感小数据（设备私钥 / relay 凭证 / 握手票据）
 * 的机密存储。secret 由系统加密保存（TEE 保护，不随明文 preferences 落入云备份），
 * 对齐 Android Keystore / iOS Keychain 的存储等级。
 *
 * 失败（模拟器 / 服务异常）时调用方回落 preferences 明文并记 warn——功能不中断，
 * 安全等级如实降级。ACCESSIBILITY = DEVICE_FIRST_UNLOCKED（解锁后可用，重启保留）；
 * 不申请 IS_PERSISTENT（那需要额外权限，恢复出厂即清除属可接受）。
 */

const TAG = 'ccpocket';

export class Vault {
  /** null = 未探测；true/false = 上次调用成败（败则走明文回落，避免每读必惊） */
  static ok: boolean | null = null;

  static async save(alias: string, secret: Uint8Array): Promise<boolean> {
    try {
      const attr: asset.AssetMap = new Map<asset.Tag, asset.Value>();
      attr.set(asset.Tag.SECRET, secret);
      attr.set(asset.Tag.ALIAS, utf8(alias));
      attr.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
      attr.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.OVERWRITE);
      await asset.add(attr);
      Vault.ok = true;
      return true;
    } catch (e) {
      Vault.ok = false;
      hilog.warn(0x0000, TAG, `vault save ${alias} failed: ${(e as Error).message}`);
      return false;
    }
  }

  static async load(alias: string): Promise<Uint8Array | null> {
    try {
      const q: asset.AssetMap = new Map<asset.Tag, asset.Value>();
      q.set(asset.Tag.ALIAS, utf8(alias));
      q.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL); // 显式要求含 SECRET（防默认只回属性）
      const rows = await asset.query(q);
      if (rows.length === 0) {
        Vault.ok = true; // 服务正常，只是没有这个条目
        return null;
      }
      const secret = rows[0].get(asset.Tag.SECRET);
      Vault.ok = true;
      return secret instanceof Uint8Array ? secret : null;
    } catch (e) {
      Vault.ok = false;
      hilog.warn(0x0000, TAG, `vault load ${alias} failed: ${(e as Error).message}`);
      return null;
    }
  }

  /**
   * 写后读回校验：save 后立即 load 比对，一致才返回 true。
   * 凡"存 Vault 后删明文"的路径都必须走它——save 报成功但 query 读不回的
   * 设备不对称曾把凭证删丢（auth:bad_credential 回退事故）。校验不过 = 视为
   * Vault 不可用，调用方保留明文（功能不断，只是没加固）。
   */
  static async saveVerified(alias: string, secret: Uint8Array): Promise<boolean> {
    if (!(await Vault.save(alias, secret))) return false;
    const back = await Vault.load(alias);
    if (back === null || back.length !== secret.length) return false;
    for (let i = 0; i < secret.length; i++) {
      if (back[i] !== secret[i]) return false;
    }
    return true;
  }

  /** 自检：一次性别名上的完整写读删往返（配对页展示）。 */
  static async selfTest(): Promise<boolean> {
    const probe = new Uint8Array([9, 8, 7, 6, 5, 4, 3, 2, 1]);
    const alias = 'vault_selftest';
    const ok = await Vault.saveVerified(alias, probe);
    await Vault.remove(alias);
    return ok;
  }

  static async remove(alias: string): Promise<void> {
    try {
      const q: asset.AssetMap = new Map<asset.Tag, asset.Value>();
      q.set(asset.Tag.ALIAS, utf8(alias));
      await asset.remove(q);
    } catch (e) {
      // 条目不存在也算成功（幂等删除）
    }
  }
}

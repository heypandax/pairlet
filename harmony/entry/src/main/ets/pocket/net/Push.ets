import { pushService } from '@kit.PushKit';
import { notificationManager } from '@kit.NotificationKit';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

/**
 * Push Kit 注册（对齐 mobile PushController 语义）：
 * token 随 module.json5 的 AGC client_id 下发，失败（未开通/未配置）静默降级为空——
 * 应用照常工作，只是没有离线推送。token 缓存一份，每次重连重发注册（对齐协议注释
 * "Re-sent on every reconnect"）。
 */

const TAG = 'ccpocket';

export class Push {
  private static cachedToken: string = '';
  private static tokenTried: boolean = false;

  /** 通知授权弹窗（系统只弹一次）；用户在系统设置里关过则 reject，静默吞掉。 */
  static async requestNotificationAuth(ctx: common.UIAbilityContext): Promise<void> {
    try {
      await notificationManager.requestEnableNotification(ctx);
    } catch (e) {
      hilog.info(0x0000, TAG, 'notification auth not granted');
    }
  }

  /** 拿 push token；拿不到（AGC 未开通 Push Kit / client_id 未配）返回空串。 */
  static async token(): Promise<string> {
    if (Push.tokenTried) return Push.cachedToken;
    Push.tokenTried = true;
    try {
      Push.cachedToken = await pushService.getToken();
      hilog.info(0x0000, TAG, `push token acquired (${Push.cachedToken.length} chars)`);
    } catch (e) {
      const err = e as BusinessError;
      hilog.warn(0x0000, TAG, `push getToken failed: ${err.code} ${err.message}`);
      Push.cachedToken = '';
    }
    return Push.cachedToken;
  }
}

interface BusinessError {
  code: number;
  message: string;
}

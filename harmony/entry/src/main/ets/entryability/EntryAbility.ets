import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { Store } from '../pocket/store/Store';
import { Repository } from '../pocket/data/Repository';
import { Push } from '../pocket/net/Push';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    Store.init(this.context);
    // 通知授权（系统只弹一次；拒绝/已授权都静默继续——推送是增量能力，不是硬依赖）
    Push.requestNotificationAuth(this.context);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', () => {});
  }

  /** 回前台：掉线立即重连（鸿蒙后台 socket 必死，这是唯一恢复路径）。 */
  onForeground(): void {
    Repository.instance.onAppForeground();
  }
}

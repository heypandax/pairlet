import { Tok } from './Theme';
import { Repository } from '../data/Repository';
import { PairingView } from './PairingView';
import { ProjectsView } from './ProjectsView';
import { SessionsView } from './SessionsView';
import { ChatView } from './ChatView';
import { SettingsView } from './SettingsView';

/** 根视图：按 repo.screen 切换页面。所有动态渲染都收在 @ObjectLink 子组件内。 */
@Component
export struct RootView {
  @ObjectLink repo: Repository;

  build() {
    Column() {
      if (this.repo.brandNoticePending) {
        Row() {
          Text('CC Pocket 现已更名为 Pairlet，设备上显示为 CC Pairlet。')
            .fontSize(13).fontColor(Tok.tx2).layoutWeight(1)
          Button('知道了').onClick(() => this.repo.dismissBrandNotice())
        }.padding(12).width('100%').backgroundColor(Tok.surface)
      }
      Stack() {
        if (this.repo.screen === 'pairing') {
          PairingView({ repo: this.repo })
        } else if (this.repo.screen === 'projects') {
          ProjectsView({ repo: this.repo })
        } else if (this.repo.screen === 'sessions') {
          SessionsView({ repo: this.repo })
        } else if (this.repo.screen === 'settings') {
          SettingsView({ repo: this.repo })
        } else {
          ChatView({ repo: this.repo })
        }
      }
      .width('100%').layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }
}

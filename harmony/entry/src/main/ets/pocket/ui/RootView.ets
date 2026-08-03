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
    .width('100%')
    .height('100%')
  }
}

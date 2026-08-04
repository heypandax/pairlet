import { Repository } from '../pocket/data/Repository';
import { RootView } from '../pocket/ui/RootView';
import { Tok } from '../pocket/ui/Theme';

@Entry
@Component
struct Index {
  @State repo: Repository = Repository.instance;

  aboutToAppear(): void {
    this.repo.init();
  }

  /** 系统返回键/手势：先在应用内逐级返回（聊天→会话→项目），到顶才退出。 */
  onBackPress(): boolean {
    return this.repo.handleBack();
  }

  build() {
    Column() {
      // 主题切换：themeVersion 变化使 if 分支换位，整树销毁重建——
      // Tok 是 static 可变值，所有 build 时读取处随之刷新（对齐 Android 的 CompositionLocal 换色）
      if (this.repo.themeVersion % 2 === 0) {
        RootView({ repo: this.repo })
      } else {
        RootView({ repo: this.repo })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Tok.base)
  }
}

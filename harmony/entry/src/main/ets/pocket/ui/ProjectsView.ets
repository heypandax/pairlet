import { Repository } from '../data/Repository';
import { DirectoryEntry } from '../protocol/Protocol';
import { PairedDaemon } from '../store/Store';
import { Tok, CONTENT_MAX } from './Theme';

/** 项目列表页（Directories），深色主题；宽屏内容限宽居中；顶栏可切换已配对的电脑。 */
@Component
export struct ProjectsView {
  @ObjectLink repo: Repository;
  @State showSwitcher: boolean = false;

  build() {
    Stack() {
      Column() {
        // 顶栏（背景通栏，内容限宽居中）
        Column() {
          Row() {
            // 电脑名（可点：多绑定时打开切换器）
            Row({ space: 4 }) {
              Text(this.repo.paired?.displayName() ?? 'CC Pocket')
                .fontSize(18)
                .fontColor(Tok.tx)
                .fontWeight(FontWeight.Medium)
              Text('▾')
                .fontSize(12)
                .fontColor(Tok.muted)
            }
            .layoutWeight(1)
            .onClick(() => {
              this.showSwitcher = true;
            })
            Text(this.repo.connState === 'online' ? '● 在线' : (this.repo.connState === 'connecting' ? '● 连接中' : '● 离线'))
              .fontSize(12)
              .fontColor(this.repo.connState === 'online' ? Tok.ok : Tok.danger)
              .margin({ right: 12 })
            Text('⚙')
              .fontSize(17)
              .fontColor(Tok.tx2)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .onClick(() => this.repo.openSettings())
          }
          .width('100%')
          .constraintSize({ maxWidth: CONTENT_MAX })
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
        }
        .width('100%')
        .alignItems(HorizontalAlign.Center)
        .backgroundColor(Tok.surface)
        .border({ width: { bottom: 1 }, color: Tok.hair })

        if (this.repo.statusLine.length > 0) {
          Text(this.repo.statusLine)
            .fontSize(12)
            .fontColor(Tok.danger)
            .padding({ left: 16, top: 8, bottom: 8 })
            .width('100%')
            .constraintSize({ maxWidth: CONTENT_MAX })
        }

        List() {
          ForEach(this.repo.directories, (dir: DirectoryEntry) => {
            ListItem() {
              Row() {
                Column({ space: 3 }) {
                  Text(dir.name.length > 0 ? dir.name : dir.path)
                    .fontSize(16)
                    .fontColor(Tok.tx)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(dir.path)
                    .fontSize(12)
                    .fontColor(Tok.muted)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)

                if (dir.executing) {
                  Text('● 运行中')
                    .fontSize(11)
                    .fontColor(Tok.ok)
                } else if (dir.open) {
                  Text('已打开')
                    .fontSize(11)
                    .fontColor(Tok.info)
                }
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 13, bottom: 13 })
            }
            .backgroundColor(Tok.base)
            .onClick(() => this.repo.openProject(dir))
          }, (dir: DirectoryEntry) => dir.path)
        }
        .layoutWeight(1)
        .width('100%')
        .constraintSize({ maxWidth: CONTENT_MAX })
        .divider({ strokeWidth: 0.5, color: Tok.hair })
      }
      .width('100%')
      .height('100%')
      .alignItems(HorizontalAlign.Center)

      // ── 电脑切换弹层 ──
      if (this.showSwitcher) {
        Column() {
          Column() {
            // 遮罩点击区关闭
            Blank()
              .layoutWeight(1)
              .onClick(() => {
                this.showSwitcher = false;
              })
            Column({ space: 2 }) {
              Text('已配对的电脑')
                .fontSize(13)
                .fontColor(Tok.muted)
                .padding({ left: 16, top: 14, bottom: 8 })
                .width('100%')
              ForEach(this.repo.bindings, (b: PairedDaemon) => {
                Row() {
                  Text(b.displayName())
                    .fontSize(16)
                    .fontColor(Tok.tx)
                    .layoutWeight(1)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (this.repo.paired !== null && this.repo.paired.accountId === b.accountId) {
                    Text('✓')
                      .fontSize(16)
                      .fontColor(Tok.accent)
                  }
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 13, bottom: 13 })
                .backgroundColor(this.repo.paired !== null && this.repo.paired.accountId === b.accountId ? Tok.raised : Tok.surface)
                .onClick(() => {
                  this.showSwitcher = false;
                  this.repo.switchComputer(b.accountId);
                })
              }, (b: PairedDaemon) => b.accountId)
              Row({ space: 8 }) {
                Text('＋')
                  .fontSize(16)
                  .fontColor(Tok.accent)
                Text('添加电脑')
                  .fontSize(16)
                  .fontColor(Tok.accent)
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 13, bottom: 16 })
              .onClick(() => {
                this.showSwitcher = false;
                this.repo.addComputer();
              })
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .constraintSize({ maxWidth: CONTENT_MAX })
            .backgroundColor(Tok.surface)
            .borderRadius({ topLeft: 18, topRight: 18 })
            .border({ width: { top: 1 }, color: Tok.hair })
          }
          .width('100%')
          .height('100%')
          .alignItems(HorizontalAlign.Center)
        }
        .width('100%')
        .height('100%')
        .backgroundColor('#80000000')
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Tok.base)
  }
}

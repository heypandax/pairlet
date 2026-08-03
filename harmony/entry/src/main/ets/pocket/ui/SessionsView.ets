import { Repository } from '../data/Repository';
import { SessionSummary } from '../protocol/Protocol';
import { Tok, CONTENT_MAX } from './Theme';

/** 会话列表页（一个 workdir 下的可恢复会话 + 新会话入口），深色主题；宽屏内容限宽居中。 */
@Component
export struct SessionsView {
  @ObjectLink repo: Repository;

  build() {
    Column() {
      // 顶栏（背景通栏，内容限宽居中）
      Column() {
        Row() {
          Text('‹ 项目')
            .fontSize(14)
            .fontColor(Tok.accent)
            .padding({ left: 12, top: 10, bottom: 10, right: 12 })
            .onClick(() => this.repo.backToProjects())
          Text(this.repo.currentWorkdir)
            .fontSize(12)
            .fontColor(Tok.muted)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
            .textAlign(TextAlign.End)
            .padding({ right: 12 })
        }
        .width('100%')
        .constraintSize({ maxWidth: CONTENT_MAX })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Center)
      .backgroundColor(Tok.surface)
      .border({ width: { bottom: 1 }, color: Tok.hair })

      List() {
        ListItem() {
          Row() {
            Text('＋ 新会话')
              .fontSize(16)
              .fontColor(Tok.accent)
              .padding({ left: 16, top: 13, bottom: 13 })
          }
          .width('100%')
        }
        .backgroundColor(Tok.base)
        .onClick(() => this.repo.openSession(null))

        ForEach(this.repo.sessions, (s: SessionSummary) => {
          ListItem() {
            Column({ space: 4 }) {
              Row() {
                Text(s.title.length > 0 ? s.title : s.firstPrompt)
                  .fontSize(15)
                  .fontColor(Tok.tx)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .layoutWeight(1)
                if (s.live) {
                  Text('●')
                    .fontSize(10)
                    .fontColor(Tok.ok)
                }
              }
              Text(`${s.messageCount} 条消息 · ${new Date(s.lastModified).toLocaleDateString()}`)
                .fontSize(11)
                .fontColor(Tok.muted)
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          }
          .backgroundColor(Tok.base)
          .onClick(() => this.repo.openSession(s.sessionId, s.title.length > 0 ? s.title : s.firstPrompt))
        }, (s: SessionSummary) => s.sessionId)
      }
      .layoutWeight(1)
      .width('100%')
      .constraintSize({ maxWidth: CONTENT_MAX })
      .divider({ strokeWidth: 0.5, color: Tok.hair })
    }
    .width('100%')
    .height('100%')
    .alignItems(HorizontalAlign.Center)
    .backgroundColor(Tok.base)
  }
}

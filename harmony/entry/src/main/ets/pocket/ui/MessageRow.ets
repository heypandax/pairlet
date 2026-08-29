import { ChatMessage, SentFile } from '../data/Repository';
import { QuestionAnswer } from '../protocol/Protocol';
import { MdText } from './Markdown';
import { Tok, fmtSize, middleTrunc } from './Theme';

/**
 * 单条消息。深色主题，对齐 Android HistoryRows 的分层：
 * user = 右侧陶土橙气泡（图在上、文在下、文件 chip 殿后）；
 * assistant = 全宽正文（无气泡，终端感）；
 * thinking = 弱化斜体；tool = 浮起卡片 + 工具名 + ✓/✗；error = 危险色条。
 *
 * tool 行内部再分三态（issue #321，对齐 commonMain）：已作答的提问 / 未作答的提问 / 普通工具。
 * 两种提问行都是只读的——回放里的 pending 属于另一个进程的回合，结构上补答不了，所以它们只把
 * 「问了什么、答没答」说清楚，不给按钮。活的提问仍走可作答的 AskCard。
 */
@Component
export struct MessageRow {
  @ObjectLink msg: ChatMessage;

  build() {
    Column() {
      if (this.msg.role === 'user') {
        this.UserBubble()
      } else if (this.msg.role === 'tool') {
        if (this.msg.answers !== null) {
          this.QuestionsAnsweredRow(this.msg.answers ?? [])
        } else if (this.msg.unanswered) {
          this.QuestionsUnansweredRow()
        } else {
          this.ToolCard()
        }
      } else if (this.msg.role === 'thinking') {
        Text(`💭 ${this.msg.text}`)
          .fontSize(13)
          .fontColor(Tok.muted)
          .fontStyle(FontStyle.Italic)
          .width('100%')
          .textAlign(TextAlign.Start)
          .padding({ left: 16, right: 16, top: 3, bottom: 3 })
      } else if (this.msg.role === 'error') {
        Row() {
          Text(this.msg.text)
            .fontSize(13)
            .fontColor(Tok.danger)
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .margin({ left: 16, right: 16, top: 4, bottom: 4 })
        .backgroundColor('#1AE5604D')
        .borderRadius(10)
        .border({ width: 1, color: '#40E5604D', radius: 10 })
      } else {
        // assistant：全宽正文（markdown 渲染）
        MdText({ text: this.msg.text.length > 0 ? this.msg.text : ' ' })
          .width('100%')
          .padding({ left: 16, right: 16, top: 5, bottom: 5 })
      }
    }
    .width('100%')
  }

  @Builder
  UserBubble() {
    Column({ space: 8 }) {
      if (this.msg.images.length > 0) {
        Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.End }) {
          ForEach(this.msg.images, (pm: PixelMap, idx: number) => {
            Image(pm)
              .width(120)
              .height(120)
              .objectFit(ImageFit.Cover)
              .borderRadius(10)
              .margin({ left: 6, bottom: 6 })
          }, (pm: PixelMap, idx: number) => `${this.msg.id}-img-${idx}`)
        }
      }
      if (this.msg.text.length > 0) {
        Text(this.msg.text)
          .fontSize(15)
          .fontColor('#FFFFFF')
          .textAlign(TextAlign.Start)
      }
      ForEach(this.msg.files, (f: SentFile) => {
        this.SentFileChip(f)
      }, (f: SentFile) => `${this.msg.id}-${f.path}`)
      if (!this.msg.delivered) {
        Text('发送中…')
          .fontSize(10)
          .fontColor('#B3FFFFFF')
      }
    }
    .alignItems(HorizontalAlign.End)
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(Tok.accent)
    .borderRadius(14)
    .constraintSize({ maxWidth: '82%' }) // 短消息收缩包裹，长消息限宽
    .alignSelf(ItemAlign.End)
    .margin({ left: 48, right: 12, top: 4, bottom: 4 })
  }

  @Builder
  SentFileChip(f: SentFile) {
    Column() {
      Row({ space: 10 }) {
        Text('📄')
          .fontSize(18)
        Column({ space: 3 }) {
          Text(middleTrunc(f.name, 16, 10))
            .fontSize(13)
            .fontColor('#FFFFFF')
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(`✓ ${fmtSize(f.size)} · 已落到工作区`)
            .fontSize(10)
            .fontColor('#B3FFFFFF')
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }
      .width('100%')
      .padding(10)
      .backgroundColor('#26FFFFFF')
      .borderRadius(10)
      Text(`@${f.path}`)
        .fontSize(10)
        .fontColor('#B3FFFFFF')
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 4 })
    }
    .alignItems(HorizontalAlign.Start)
    .width(260)
  }

  @Builder
  ToolCard() {
    Column({ space: 4 }) {
      Row({ space: 6 }) {
        Text(`🔧 ${this.msg.tool ?? 'tool'}`)
          .fontSize(12)
          .fontColor(Tok.tx2)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.msg.ok !== null) {
          Text(this.msg.ok ? '✓' : '✗')
            .fontSize(12)
            .fontColor(this.msg.ok ? Tok.ok : Tok.danger)
        }
      }
      .width('100%')
      if (this.msg.text.length > 0) {
        Text(this.msg.text)
          .fontSize(12)
          .fontColor(Tok.muted)
          .maxLines(6)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .textAlign(TextAlign.Start)
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .margin({ left: 16, right: 16, top: 3, bottom: 3 })
    .backgroundColor(Tok.raised)
    .borderRadius(10)
    .border({ width: 1, color: Tok.hair, radius: 10 })
  }

  /**
   * 已作答的提问（issue #321）：留在回放里的 (问题 → 答案) 列表，只读。
   *
   * 问题文本沿用 AskCard 提问态的字号 / 字重，答案用 ✓ 标出——同一件事在活卡里是「选中的选项」，
   * 在回放里就是「当时选了什么」。自由输入的回答是 question 为空串的那一对，此时只显示答案。
   */
  @Builder
  QuestionsAnsweredRow(items: QuestionAnswer[]) {
    Column({ space: 8 }) {
      Text(`❓ 回答了 ${items.length} 个问题`)
        .fontSize(12)
        .fontColor(Tok.tx2)
        .width('100%')
        .textAlign(TextAlign.Start)
      ForEach(items, (qa: QuestionAnswer, idx: number) => {
        Column({ space: 3 }) {
          if (qa.question.length > 0) {
            Text(qa.question)
              .fontSize(12)
              .fontColor(Tok.muted)
              .width('100%')
              .textAlign(TextAlign.Start)
          }
          Row({ space: 6 }) {
            Text('✓')
              .fontSize(12)
              .fontColor(Tok.accent)
            Text(qa.answer)
              .fontSize(13)
              .fontColor(Tok.tx)
              .layoutWeight(1)
              .textAlign(TextAlign.Start)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
      }, (qa: QuestionAnswer, idx: number) => `${this.msg.id}-qa-${idx}`)
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .margin({ left: 16, right: 16, top: 3, bottom: 3 })
    .backgroundColor(Tok.raised)
    .borderRadius(10)
    .border({ width: 1, color: Tok.hair, radius: 10 })
  }

  /**
   * 从未被作答的提问（issue #321）：问过，回合就这么结束了。
   *
   * 借用与已答行相同的卡框，所以它读起来还是「一个提问」，但可点的东西全都不在了，并且第一行直说
   * 为什么——#321 抱怨的正是一张看得见却完不成的提问卡。语气克制：未作答是死路，不是错误。
   */
  @Builder
  QuestionsUnansweredRow() {
    Column({ space: 3 }) {
      Text('❓ 未作答的提问——该回合已结束，没有收到回答')
        .fontSize(12)
        .fontColor(Tok.muted)
        .width('100%')
        .textAlign(TextAlign.Start)
      if (this.msg.text.length > 0) {
        Text(this.msg.text)
          .fontSize(13)
          .fontColor(Tok.tx2)
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .textAlign(TextAlign.Start)
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .margin({ left: 16, right: 16, top: 3, bottom: 3 })
    .backgroundColor(Tok.raised)
    .borderRadius(10)
    .border({ width: 1, color: Tok.hair, radius: 10 })
  }
}

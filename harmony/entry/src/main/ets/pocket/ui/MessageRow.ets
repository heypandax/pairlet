import { ChatMessage, SentFile } from '../data/Repository';
import { MdText } from './Markdown';
import { Tok, fmtSize, middleTrunc } from './Theme';

/**
 * 单条消息。深色主题，对齐 Android HistoryRows 的分层：
 * user = 右侧陶土橙气泡（图在上、文在下、文件 chip 殿后）；
 * assistant = 全宽正文（无气泡，终端感）；
 * thinking = 弱化斜体；tool = 浮起卡片 + 工具名 + ✓/✗；error = 危险色条。
 */
@Component
export struct MessageRow {
  @ObjectLink msg: ChatMessage;

  build() {
    Column() {
      if (this.msg.role === 'user') {
        this.UserBubble()
      } else if (this.msg.role === 'tool') {
        this.ToolCard()
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
}

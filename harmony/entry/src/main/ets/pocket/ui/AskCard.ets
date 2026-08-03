import { Repository } from '../data/Repository';
import { AskOption, AskQuestion, PermissionAsk } from '../protocol/Protocol';
import { Tok } from './Theme';

/**
 * 权限审批卡 / AskUserQuestion 提问卡（深色主题）。
 * 权限：允许一次 / 始终允许（有 rule 且非 one-off）/ 拒绝。
 * 提问：逐题单选，提交时 answers = {questionText: label}（对齐 PermissionVerdict.answers）。
 */
@Component
export struct AskCard {
  @Prop ask: PermissionAsk;
  @ObjectLink repo: Repository;
  @State picked: Map<string, string> = new Map();

  private pick(question: string, label: string): void {
    const next = new Map(this.picked);
    next.set(question, label);
    this.picked = next;
  }

  private allAnswered(): boolean {
    const qs = this.ask.questions;
    if (qs === null || qs.length === 0) return false;
    for (const q of qs) {
      if (!this.picked.has(q.question)) return false;
    }
    return true;
  }

  private submitAnswers(): void {
    const answers: Record<string, string> = {};
    this.picked.forEach((label: string, question: string) => {
      answers[question] = label;
    });
    this.repo.answerAsk('allow', false, answers);
  }

  build() {
    Column({ space: 10 }) {
      if (!this.ask.isQuestion) {
        // ── 权限审批 ──
        Text(this.ask.title.length > 0 ? this.ask.title : this.ask.tool)
          .fontSize(14)
          .fontColor(Tok.tx)
          .fontWeight(FontWeight.Medium)
        if (this.ask.inputPreview.length > 0) {
          Text(this.ask.inputPreview)
            .fontSize(12)
            .fontColor(Tok.tx2)
            .maxLines(4)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
            .padding(8)
            .backgroundColor(Tok.base)
            .borderRadius(8)
        }
        if (this.ask.danger) {
          Text(`⚠ 危险操作${this.ask.dangerNote !== null ? `：${this.ask.dangerNote}` : ''}`)
            .fontSize(12)
            .fontColor(Tok.danger)
        }
        Row({ space: 8 }) {
          Button('允许一次')
            .fontSize(13)
            .height(34)
            .fontColor('#FFFFFF')
            .backgroundColor(Tok.accent)
            .onClick(() => this.repo.answerAsk('allow'))
          if (this.ask.rule !== null && !this.ask.neverRemember) {
            Button('始终允许')
              .fontSize(13)
              .height(34)
              .fontColor(Tok.ok)
              .backgroundColor('#1A4FB477')
              .onClick(() => this.repo.answerAsk('allow', true))
          }
          Button('拒绝')
            .fontSize(13)
            .height(34)
            .fontColor(Tok.danger)
            .backgroundColor('#1AE5604D')
            .onClick(() => this.repo.answerAsk('deny'))
        }
      } else {
        // ── AskUserQuestion ──
        ForEach(this.ask.questions, (q: AskQuestion) => {
          Column({ space: 8 }) {
            Text(q.question)
              .fontSize(14)
              .fontColor(Tok.tx)
              .fontWeight(FontWeight.Medium)
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(q.options, (opt: AskOption) => {
                Text(opt.label)
                  .fontSize(12)
                  .fontColor(this.picked.get(q.question) === opt.label ? '#FFFFFF' : Tok.tx2)
                  .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                  .margin({ right: 6, bottom: 6 })
                  .backgroundColor(this.picked.get(q.question) === opt.label ? Tok.accent : Tok.raised)
                  .borderRadius(14)
                  .border({
                    width: 1,
                    color: this.picked.get(q.question) === opt.label ? Tok.accent : Tok.hair,
                    radius: 14,
                  })
                  .onClick(() => this.pick(q.question, opt.label))
              }, (opt: AskOption) => opt.label)
            }
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
        }, (q: AskQuestion) => q.question)

        Row({ space: 8 }) {
          Button('提交')
            .fontSize(13)
            .height(34)
            .fontColor('#FFFFFF')
            .backgroundColor(this.allAnswered() ? Tok.accent : Tok.raised)
            .enabled(this.allAnswered())
            .onClick(() => this.submitAnswers())
          Button('拒绝')
            .fontSize(13)
            .height(34)
            .fontColor(Tok.danger)
            .backgroundColor('#1AE5604D')
            .onClick(() => this.repo.answerAsk('deny'))
        }
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding(14)
    .backgroundColor(Tok.surface)
    .border({ width: { top: 1 }, color: Tok.hair })
  }
}

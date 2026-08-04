import { Repository } from '../data/Repository';
import { AskOption, AskQuestion, PermissionAsk } from '../protocol/Protocol';
import { Tok } from './Theme';

/**
 * 权限审批卡 / AskUserQuestion 提问卡（深色主题）。
 * 权限：允许一次 / 始终允许（有 rule 且非 one-off）/ 拒绝。
 * 提问：单选题逐题单选、多选题（multiSelect=true）可多选；提交时 answers =
 * {questionText: label(s)}，多选值为逗号连接的选项 labels（选项原序），对齐 mobile
 * QuestionCard.answerOf 的 `joinToString(", ")` 与 PermissionVerdict.answers 形状。
 */
@Component
export struct AskCard {
  @Prop ask: PermissionAsk;
  @ObjectLink repo: Repository;
  // 每题 → 已选 label 数组（单选长度 ≤1，多选可多个）。改后重赋触发刷新（ArkUI V1 观察语义）。
  @State selections: Map<string, string[]> = new Map();

  private isPicked(question: string, label: string): boolean {
    const arr = this.selections.get(question);
    return arr !== undefined && arr.includes(label);
  }

  /** 切换选中（对齐 mobile QuestionCard.toggle）：单选=替换/再点取消；多选=加入/移除。 */
  private toggle(question: string, label: string, multi: boolean): void {
    const cur = this.selections.get(question) ?? [];
    const next = new Map(this.selections);
    if (!multi) {
      next.set(question, cur.includes(label) ? [] : [label]);
    } else if (cur.includes(label)) {
      next.set(question, cur.filter((x: string) => x !== label));
    } else {
      next.set(question, [...cur, label]);
    }
    this.selections = next;
  }

  private allAnswered(): boolean {
    const qs = this.ask.questions;
    if (qs === null || qs.length === 0) return false;
    for (const q of qs) {
      const arr = this.selections.get(q.question);
      if (arr === undefined || arr.length === 0) return false;
    }
    return true;
  }

  private submitAnswers(): void {
    const answers: Record<string, string> = {};
    const qs = this.ask.questions;
    if (qs !== null) {
      for (const q of qs) {
        const sel = this.selections.get(q.question) ?? [];
        // 选项原序过滤已选，逗号（+空格）连接——多选值形如 "A, B"（对齐 answerOf 的 joinToString(", ")）
        const labels = q.options.map((o: AskOption) => o.label).filter((l: string) => sel.includes(l));
        answers[q.question] = labels.join(', ');
      }
    }
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
            Row({ space: 6 }) {
              Text(q.question)
                .fontSize(14)
                .fontColor(Tok.tx)
                .fontWeight(FontWeight.Medium)
                .layoutWeight(1)
              if (q.multiSelect) {
                Text('可多选')
                  .fontSize(11)
                  .fontColor(Tok.tx2)
              }
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(q.options, (opt: AskOption) => {
                Text(opt.label)
                  .fontSize(12)
                  .fontColor(this.isPicked(q.question, opt.label) ? '#FFFFFF' : Tok.tx2)
                  .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                  .margin({ right: 6, bottom: 6 })
                  .backgroundColor(this.isPicked(q.question, opt.label) ? Tok.accent : Tok.raised)
                  .borderRadius(14)
                  .border({
                    width: 1,
                    color: this.isPicked(q.question, opt.label) ? Tok.accent : Tok.hair,
                    radius: 14,
                  })
                  .onClick(() => this.toggle(q.question, opt.label, q.multiSelect))
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

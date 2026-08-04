import { common } from '@kit.AbilityKit';
import { ChatMessage, PendingFile, PendingImage, Repository } from '../data/Repository';
import { PermissionAsk } from '../protocol/Protocol';
import { MessageRow } from './MessageRow';
import { AskCard } from './AskCard';
import { CONTENT_MAX, Tok, fmtSize, middleTrunc } from './Theme';

interface ModeOption {
  key: string;
  label: string;
  desc: string;
}

/** 回合进行中的三点呼吸指示器（280ms 轮播）。 */
@Component
struct BusyDots {
  @State frame: number = 0;
  private timer: number = -1;

  aboutToAppear(): void {
    this.timer = setInterval(() => {
      this.frame = (this.frame + 1) % 3;
    }, 280);
  }

  aboutToDisappear(): void {
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  build() {
    Row({ space: 5 }) {
      ForEach([0, 1, 2], (i: number) => {
        Text('●')
          .fontSize(9)
          .fontColor(Tok.accent)
          .opacity(this.frame === i ? 1 : 0.25)
      }, (i: number) => `${i}`)
    }
  }
}

/**
 * 聊天页：消息流 + 审批卡 + 附件暂存条 + 附件抽屉 + 输入栏。
 * 深色主题，对齐 Android 的 composer 交互：＋ 开抽屉（照片/文件），
 * 文件上传中发送键禁用（等回执），落盘后随 prompt 以 @path 引用发出。
 * 宽屏（折叠屏展开/平板）：顶栏与底栏背景通栏、内容限宽 CONTENT_MAX 居中；
 * 消息流自动跟随滚动（用户在底部附近时）。
 */

@Component
export struct ChatView {
  @ObjectLink repo: Repository;
  @State draft: string = '';
  @State attachOpen: boolean = false;
  @State showModeSheet: boolean = false;
  private scroller: Scroller = new Scroller();
  @State nearBottom: boolean = true; // 贴底跟随 + 「回到底部」浮钮的可见性
  private followTimer = -1;
  private lastSeenCount = 0;
  private lastSeenTail = 0;

  private modeLabel(key: string): string {
    switch (key) {
      case 'auto': return '自动';
      case 'acceptEdits': return '自动编辑';
      case 'plan': return '计划';
      case 'bypassPermissions': return '绕过权限';
      default: return '询问';
    }
  }

  aboutToAppear(): void {
    this.scroller.scrollEdge(Edge.Bottom);
    this.followTimer = setInterval(() => this.followTick(), 350);
  }

  aboutToDisappear(): void {
    if (this.followTimer >= 0) {
      clearInterval(this.followTimer);
      this.followTimer = -1;
    }
  }

  /** 列表总行数：消息 + 执行中多出来的一行指示器。 */
  private totalItems(): number {
    return this.repo.messages.length + (this.repo.executing ? 1 : 0);
  }

  /** 只在内容实际变化（新消息 / 流式增长）且用户贴底时滚——绝不定时硬拽。
   *  消息从无到有（会话打开后历史到达）强制贴底一次：打开会话应落在最底下。 */
  private followTick(): void {
    const n = this.repo.messages.length;
    const tail = n > 0 ? this.repo.messages[n - 1].text.length : 0;
    if (n !== this.lastSeenCount || tail !== this.lastSeenTail) {
      const wasEmpty = this.lastSeenCount === 0;
      this.lastSeenCount = n;
      this.lastSeenTail = tail;
      if (wasEmpty && n > 0) this.nearBottom = true;
      if (this.nearBottom) {
        this.scroller.scrollEdge(Edge.Bottom);
      }
    }
  }

  private send(): void {
    const text = this.draft;
    this.draft = '';
    this.attachOpen = false;
    this.nearBottom = true;
    this.repo.sendPrompt(text);
  }

  private readyImages(): number {
    return this.repo.pendingImages.filter((p: PendingImage) => p.state === 'ready').length;
  }

  private landedFiles(): number {
    return this.repo.pendingFiles.filter((p: PendingFile) => p.state === 'landed').length;
  }

  private canSend(): boolean {
    if (this.repo.connState !== 'online') return false;
    if (this.repo.uploadsBusy()) return false;
    return this.draft.trim().length > 0 || this.readyImages() > 0 || this.landedFiles() > 0;
  }

  build() {
    Stack() {
      Column() {
        // ── 顶栏（背景通栏，内容限宽居中） ──
        Column() {
          Row() {
            Text('‹ 会话')
              .fontSize(14)
              .fontColor(Tok.accent)
              .padding({ left: 12, top: 10, bottom: 10, right: 12 })
              .onClick(() => this.repo.closeSession())
            // 权限模式 chip：点开底部选择层（下个回合生效，不打断当前回合）
            Row({ space: 3 }) {
              Text(this.modeLabel(this.repo.currentModeKey()))
                .fontSize(11)
                .fontColor(Tok.tx2)
              Text('▾')
                .fontSize(9)
                .fontColor(Tok.muted)
            }
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .backgroundColor(Tok.raised)
            .borderRadius(8)
            .border({ width: 1, color: Tok.hair, radius: 8 })
            .onClick(() => {
              this.showModeSheet = true;
            })
            Text(this.repo.connState === 'online' ? '' : '离线 · 重连中')
              .fontSize(12)
              .fontColor(Tok.danger)
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

      // ── 消息流（限宽居中，跟随滚动 + 回到底部浮钮） ──
      Stack({ alignContent: Alignment.BottomEnd }) {
        List({ scroller: this.scroller }) {
          ForEach(this.repo.messages, (m: ChatMessage) => {
            ListItem() {
              MessageRow({ msg: m })
            }
          }, (m: ChatMessage) => m.id)

          if (this.repo.executing) {
            ListItem() {
              Row({ space: 10 }) {
                BusyDots()
                Text(this.repo.activity.length > 0 ? `${this.repo.activity}…` : '等待响应…')
                  .fontSize(12)
                  .fontColor(Tok.muted)
              }
              .padding({ left: 16, top: 6, bottom: 6 })
            }
          }
        }
        .width('100%')
        .height('100%')
        .onScrollIndex((start: number, end: number) => {
          // 贴底判断含执行中指示器那一行，留一行容差：上翻两行以上才停跟随
          this.nearBottom = end >= this.totalItems() - 2;
        })

        // 回到底部浮钮：离开底部时出现，点了回底并恢复跟随
        if (!this.nearBottom) {
          Text('↓')
            .fontSize(18)
            .fontColor(Tok.tx)
            .width(40)
            .height(40)
            .textAlign(TextAlign.Center)
            .backgroundColor(Tok.raised)
            .borderRadius(20)
            .border({ width: 1, color: Tok.hair, radius: 20 })
            .shadow({ radius: 8, color: '#40000000', offsetY: 2 })
            .margin({ right: 16, bottom: 14 })
            .onClick(() => {
              this.nearBottom = true;
              this.scroller.scrollEdge(Edge.Bottom);
            })
        }
      }
      .layoutWeight(1)
      .width('100%')
      .constraintSize({ maxWidth: CONTENT_MAX })

      // ── 审批 / 提问卡（按 askId 重建以重置选择状态） ──
      ForEach(this.repo.currentAsk === null ? [] : [this.repo.currentAsk], (ask: PermissionAsk) => {
        Column() {
          AskCard({ ask: ask, repo: this.repo })
            .width('100%')
            .constraintSize({ maxWidth: CONTENT_MAX })
        }
        .width('100%')
        .alignItems(HorizontalAlign.Center)
      }, (ask: PermissionAsk) => ask.askId)

      // ── 底部区（暂存条 + 附件抽屉 + 输入栏；背景通栏，内容限宽） ──
      Column() {
        // 图片暂存条
        if (this.repo.pendingImages.length > 0) {
          Row({ space: 8 }) {
            ForEach(this.repo.pendingImages, (p: PendingImage) => {
              Stack({ alignContent: Alignment.TopEnd }) {
                if (p.preview !== null) {
                  Image(p.preview)
                    .width(56)
                    .height(56)
                    .objectFit(ImageFit.Cover)
                    .borderRadius(8)
                    .opacity(p.state === 'rejected' ? 0.35 : 1)
                    .border(p.state === 'rejected' ? { width: 1, color: Tok.danger, radius: 8 } : { width: 1, color: Tok.hair, radius: 8 })
                } else {
                  Column() {
                    Text('…')
                      .fontSize(16)
                      .fontColor(Tok.muted)
                  }
                  .width(56)
                  .height(56)
                  .justifyContent(FlexAlign.Center)
                  .backgroundColor(Tok.raised)
                  .borderRadius(8)
                }
                Text('✕')
                  .fontSize(10)
                  .fontColor('#FFFFFF')
                  .backgroundColor('#B3000000')
                  .borderRadius(9)
                  .width(18)
                  .height(18)
                  .textAlign(TextAlign.Center)
                  .margin({ top: -4, right: -4 })
                  .onClick(() => this.repo.removePendingImage(p.id))
              }
              .margin({ top: 6 })
            }, (p: PendingImage) => `${p.id}`)
          }
          .width('100%')
          .constraintSize({ maxWidth: CONTENT_MAX })
          .padding({ left: 12, right: 12, bottom: 4 })
        }

        // 文件暂存条
        if (this.repo.pendingFiles.length > 0) {
          Column({ space: 6 }) {
            ForEach(this.repo.pendingFiles, (p: PendingFile) => {
              Row({ space: 10 }) {
                Text(p.state === 'failed' ? '↻' : '📄')
                  .fontSize(16)
                  .fontColor(p.state === 'failed' ? Tok.danger : Tok.tx2)
                  .onClick(() => {
                    if (p.state === 'failed') this.repo.retryPendingFile(p.id);
                  })
                Column({ space: 3 }) {
                  Text(middleTrunc(p.name, 18, 12))
                    .fontSize(12)
                    .fontColor(p.state === 'failed' ? Tok.danger : Tok.tx)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (p.state === 'uploading') {
                    Progress({ value: p.progress * 100, total: 100, type: ProgressType.Linear })
                      .color(Tok.accent)
                      .width('100%')
                      .height(3)
                  } else {
                    Text(
                      p.state === 'failed' ? p.error
                        : p.state === 'landed' ? `✓ ${fmtSize(p.size)} · 已落盘`
                        : `排队中 · ${fmtSize(p.size)}`)
                      .fontSize(10)
                      .fontColor(p.state === 'failed' ? Tok.danger : Tok.muted)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                Text('✕')
                  .fontSize(12)
                  .fontColor(Tok.muted)
                  .padding(6)
                  .onClick(() => this.repo.removePendingFile(p.id))
              }
              .width('100%')
              .padding({ left: 10, right: 6, top: 7, bottom: 7 })
              .backgroundColor(p.state === 'failed' ? '#14E5604D' : Tok.raised)
              .borderRadius(10)
              .border({ width: 1, color: p.state === 'failed' ? '#40E5604D' : Tok.hair, radius: 10 })
            }, (p: PendingFile) => `${p.id}`)
          }
          .width('100%')
          .constraintSize({ maxWidth: CONTENT_MAX })
          .padding({ left: 12, right: 12, top: 8 })
        }

        // 附件抽屉
        if (this.attachOpen) {
          Column({ space: 10 }) {
            Row({ space: 10 }) {
              this.AttachOption('🖼', '照片', () => {
                this.attachOpen = false;
                this.repo.attachImages(getContext(this) as common.UIAbilityContext);
              })
              this.AttachOption('📎', '文件', () => {
                this.attachOpen = false;
                this.repo.attachFiles(getContext(this) as common.UIAbilityContext);
              })
            }
            .width('100%')
            Text('文件会分块传到电脑的工作区 inbox，agent 按 @路径 读取（单个 ≤200MB）')
              .fontSize(11)
              .fontColor(Tok.tx2)
              .width('100%')
              .padding({ left: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .constraintSize({ maxWidth: CONTENT_MAX - 24 })
          .padding({ left: 12, right: 12, top: 12, bottom: 12 })
          .margin({ top: 4, bottom: 8 })
          .backgroundColor(Tok.surface)
          .borderRadius(16)
          .border({ width: 1, color: Tok.hair, radius: 16 })
        }

        // 输入栏
        Row({ space: 8 }) {
          Text(this.attachOpen ? '×' : '＋')
            .fontSize(22)
            .fontColor(Tok.tx2)
            .width(36)
            .height(36)
            .textAlign(TextAlign.Center)
            .backgroundColor(Tok.raised)
            .borderRadius(18)
            .onClick(() => {
              this.attachOpen = !this.attachOpen;
            })
          TextInput({ placeholder: this.repo.executing ? '排队发送…' : '发消息…', text: this.draft })
            .layoutWeight(1)
            .fontSize(15)
            .fontColor(Tok.tx)
            .placeholderColor(Tok.muted)
            .backgroundColor(Tok.raised)
            .borderRadius(18)
            .padding({ left: 14, right: 14 })
            .onChange((v: string) => {
              this.draft = v;
            })
            .onSubmit(() => {
              if (this.canSend()) this.send();
            })
          if (this.repo.executing) {
            // 回合中：■ 打断与 ➤ 排队发送并存（daemon/CLI 会排队 mid-turn prompt）
            Text('■')
              .fontSize(15)
              .fontColor('#FFFFFF')
              .width(36)
              .height(36)
              .textAlign(TextAlign.Center)
              .backgroundColor(Tok.danger)
              .borderRadius(18)
              .onClick(() => this.repo.cancelTurn())
          }
          Text('➤')
            .fontSize(15)
            .fontColor(this.canSend() ? '#FFFFFF' : Tok.muted)
            .width(36)
            .height(36)
            .textAlign(TextAlign.Center)
            .backgroundColor(this.canSend() ? Tok.accent : Tok.raised)
            .borderRadius(18)
            .onClick(() => {
              if (this.canSend()) this.send();
            })
        }
        .width('100%')
        .constraintSize({ maxWidth: CONTENT_MAX })
        .padding({ left: 10, right: 10, top: 8, bottom: 8 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Center)
      .backgroundColor(Tok.surface)
      .border({ width: { top: 1 }, color: Tok.hair })
      }
      .width('100%')
      .height('100%')
      .backgroundColor(Tok.base)

      // ── 权限模式选择层 ──
      if (this.showModeSheet) {
        Column() {
          Blank()
            .layoutWeight(1)
            .onClick(() => {
              this.showModeSheet = false;
            })
          Column() {
            Text('权限模式 · 下个回合生效')
              .fontSize(13)
              .fontColor(Tok.muted)
              .padding({ left: 16, top: 14, bottom: 8 })
              .width('100%')
            ForEach([
              { key: 'default', label: '询问', desc: '每个危险操作都询问' },
              { key: 'auto', label: '自动', desc: '智能判断，安全操作自动通过' },
              { key: 'acceptEdits', label: '自动编辑', desc: '文件编辑自动通过，其余仍询问' },
              { key: 'plan', label: '计划', desc: '只读分析，先出计划再执行' },
              { key: 'bypassPermissions', label: '绕过权限', desc: '全部自动通过（危险，慎用）' },
            ], (opt: ModeOption) => {
              Row() {
                Column({ space: 2 }) {
                  Text(opt.label)
                    .fontSize(15)
                    .fontColor(Tok.tx)
                  Text(opt.desc)
                    .fontSize(11)
                    .fontColor(Tok.muted)
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                if (this.repo.currentModeKey() === opt.key) {
                  Text('✓')
                    .fontSize(15)
                    .fontColor(Tok.accent)
                }
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(this.repo.currentModeKey() === opt.key ? Tok.raised : Tok.surface)
              .onClick(() => {
                this.showModeSheet = false;
                this.repo.switchMode(opt.key);
              })
            }, (opt: ModeOption) => opt.key)
            Blank().height(10)
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
        .backgroundColor('#80000000')
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  AttachOption(glyph: string, label: string, onTap: () => void) {
    Column({ space: 8 }) {
      Text(glyph)
        .fontSize(24)
      Text(label)
        .fontSize(12)
        .fontColor(Tok.tx)
        .fontWeight(FontWeight.Medium)
    }
    .layoutWeight(1)
    .height(76)
    .justifyContent(FlexAlign.Center)
    .backgroundColor(Tok.raised)
    .borderRadius(14)
    .border({ width: 1, color: Tok.hair, radius: 14 })
    .onClick(onTap)
  }
}

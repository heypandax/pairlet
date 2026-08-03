import { common } from '@kit.AbilityKit';
import { ChatMessage, PendingFile, PendingImage, Repository } from '../data/Repository';
import { ChangedFile, PermissionAsk } from '../protocol/Protocol';
import { MessageRow } from './MessageRow';
import { AskCard } from './AskCard';
import { CONTENT_MAX, Tok, fmtSize, groupDigits, middleTrunc } from './Theme';

interface ModeOption {
  key: string;
  label: string;
  desc: string;
}

interface SheetOption {
  key: string;
  label: string;
  desc: string;
}

/** 呼吸点（顶栏"运行中"chip 用）：单点 0.3↔1 透明度脉冲（700ms，对齐 Android PulseDot）。 */
@Component
struct PulseDot {
  @Prop color: string = Tok.accent;
  @State dim: boolean = false;
  private timer: number = -1;

  aboutToAppear(): void {
    this.timer = setInterval(() => {
      this.dim = !this.dim;
    }, 700);
  }

  aboutToDisappear(): void {
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  build() {
    Text('●')
      .fontSize(8)
      .fontColor(this.color)
      .opacity(this.dim ? 0.3 : 1)
      .animation({ duration: 600, curve: Curve.EaseInOut })
  }
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

/** 底部弹层状态机：none | actions（快捷操作）| model | effort | mode | files（改动文件）| ctx（上下文详情） */
type SheetKind = 'none' | 'actions' | 'model' | 'effort' | 'mode' | 'files' | 'ctx';

/**
 * 聊天页 —— 对齐 Android：顶栏「‹ 标题 + host·目录·模型 副标题 + ⋮ 快捷操作」，
 * 输入栏模型 chip，「回到最新」悬浮 pill；消息流跟随滚动。
 * 快捷操作：切换模型 / 思考深度 / 权限模式 / 压缩上下文 / 简化 / 清空会话。
 */
@Component
export struct ChatView {
  @ObjectLink repo: Repository;
  @State draft: string = '';
  @State attachOpen: boolean = false;
  @State sheet: SheetKind = 'none';
  private scroller: Scroller = new Scroller();
  @State nearBottom: boolean = true; // 贴底跟随 + 「回到最新」pill 的可见性
  private followTimer = -1;
  private lastSeenCount = 0;
  private lastSeenTail = 0;

  private modeLabel(key: string): string {
    switch (key) {
      case 'auto': return '自动判断';
      case 'acceptEdits': return '自动编辑';
      case 'plan': return '计划';
      case 'bypassPermissions': return '绕过权限';
      default: return '询问';
    }
  }

  /** workdir 末段（副标题显示用）。 */
  private shortDir(): string {
    const wd = this.repo.currentWorkdir;
    if (wd.length === 0) return '';
    const parts = wd.split(/[\\/]/).filter((p: string) => p.length > 0);
    return parts.length > 0 ? parts[parts.length - 1] : wd;
  }

  private subtitle(): string {
    if (this.repo.connState !== 'online') return '离线 · 重连中';
    const host = this.repo.paired?.displayName() ?? '';
    const model = this.repo.model.length > 0 ? this.repo.model : '默认模型';
    return `${host} · ${this.shortDir()} · ${model}`;
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
        // ── 顶栏：‹ 标题+副标题 ⋮（对齐 Android） ──
        Column() {
          Row({ space: 10 }) {
            Text('‹')
              .fontSize(22)
              .fontColor(Tok.tx)
              .padding({ left: 10, right: 6, top: 8, bottom: 8 })
              .onClick(() => this.repo.closeSession())
            Column({ space: 1 }) {
              Text(this.repo.chatTitle)
                .fontSize(15)
                .fontColor(Tok.tx)
                .fontWeight(FontWeight.Medium)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(this.subtitle())
                .fontSize(10.5)
                .fontColor(this.repo.connState === 'online' ? Tok.muted : Tok.danger)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)
            // 「运行中」呼吸 chip（对齐 Android issue #52：重进进行中的会话，一眼看出活着）
            if (this.repo.executing) {
              Row({ space: 5 }) {
                PulseDot({ color: Tok.accent })
                Text('运行中')
                  .fontSize(11)
                  .fontColor(Tok.accent)
              }
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .backgroundColor(Tok.raised)
              .borderRadius(10)
            }
            Text('⋮')
              .fontSize(19)
              .fontColor(Tok.tx)
              .padding({ left: 12, right: 14, top: 8, bottom: 8 })
              .onClick(() => {
                this.sheet = 'actions';
              })
          }
          .width('100%')
          .constraintSize({ maxWidth: CONTENT_MAX })
          .alignItems(VerticalAlign.Center)
        }
        .width('100%')
        .alignItems(HorizontalAlign.Center)
        .backgroundColor(Tok.surface)
        .border({ width: { bottom: 1 }, color: Tok.hair })

        // ── 消息流（限宽居中，跟随滚动 + 回到最新 pill） ──
        Stack({ alignContent: Alignment.Bottom }) {
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

          // 回到最新 pill：离开底部时出现，点了回底并恢复跟随（对齐 Android 样式）
          if (!this.nearBottom) {
            Row({ space: 4 }) {
              Text('↓')
                .fontSize(12)
                .fontColor(Tok.accent)
              Text('回到最新')
                .fontSize(12)
                .fontColor(Tok.tx)
            }
            .padding({ left: 14, right: 14, top: 7, bottom: 7 })
            .backgroundColor(Tok.raised)
            .borderRadius(16)
            .border({ width: 1, color: Tok.hair, radius: 16 })
            .shadow({ radius: 8, color: '#40000000', offsetY: 2 })
            .margin({ bottom: 14 })
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
            .backgroundColor(Tok.raised)
            .borderRadius(16)
            .border({ width: 1, color: Tok.hair, radius: 16 })
          }

          // 输入栏：＋ / 模型 chip / 输入框 / （回合中）■ + ➤（对齐 Android composer）
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
            // 模型 chip（点开模型选择层，对齐 Android composer 的 model pill）
            Row({ space: 2 }) {
              Text(this.repo.model.length > 0 ? middleTrunc(this.repo.model, 8, 6) : '默认')
                .fontSize(11)
                .fontColor(Tok.tx2)
                .maxLines(1)
              Text('▾')
                .fontSize(8)
                .fontColor(Tok.muted)
            }
            .constraintSize({ maxWidth: 96 })
            .padding({ left: 9, right: 7, top: 5, bottom: 5 })
            .backgroundColor(Tok.raised)
            .borderRadius(12)
            .border({ width: 1, color: Tok.hair, radius: 12 })
            .onClick(() => {
              this.repo.fetchModels();
              this.sheet = 'model';
            })
            if (this.repo.contextWindow > 0 && this.repo.contextUsed > 0) {
              this.ContextGauge()
            }
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

      // ── 底部弹层（快捷操作 / 模型 / 思考深度 / 权限模式） ──
      if (this.sheet !== 'none') {
        Column() {
          Blank()
            .layoutWeight(1)
            .onClick(() => {
              this.sheet = 'none';
            })
          Column() {
            if (this.sheet === 'actions') {
              this.ActionsSheet()
            } else if (this.sheet === 'model') {
              this.ModelSheet()
            } else if (this.sheet === 'effort') {
              this.EffortSheet()
            } else if (this.sheet === 'files') {
              this.FilesSheet()
            } else if (this.sheet === 'ctx') {
              this.CtxSheet()
            } else {
              this.ModeSheet()
            }
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

      // ── 文件查看器（全屏覆盖，对齐 Android FileViewerScreen） ──
      if (this.repo.viewerPath.length > 0) {
        Column() {
          Row() {
            Text('‹')
              .fontSize(22)
              .fontColor(Tok.tx)
              .padding({ left: 10, right: 10, top: 10, bottom: 10 })
              .onClick(() => this.repo.closeFileViewer())
            Text(this.repo.viewerPath)
              .fontSize(12)
              .fontColor(Tok.tx2)
              .fontFamily('monospace')
              .layoutWeight(1)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .padding({ right: 12 })
          }
          .width('100%')
          .constraintSize({ maxWidth: CONTENT_MAX })
          .backgroundColor(Tok.surface)
          .border({ width: { bottom: 1 }, color: Tok.hair })

          if (this.repo.viewerError.length > 0) {
            Text(this.repo.viewerError)
              .fontSize(13)
              .fontColor(Tok.danger)
              .padding(20)
          } else if (this.repo.viewerText.length === 0) {
            Text('加载中…')
              .fontSize(13)
              .fontColor(Tok.muted)
              .padding(20)
          } else {
            Scroll() {
              Text(this.repo.viewerText)
                .fontSize(11.5)
                .fontColor(Tok.tx)
                .fontFamily('monospace')
                .padding({ left: 14, right: 14, top: 10, bottom: 20 })
            }
            .layoutWeight(1)
            .width('100%')
            .align(Alignment.TopStart)
          }
          if (this.repo.viewerMeta.length > 0) {
            Text(this.repo.viewerMeta)
              .fontSize(11)
              .fontColor(Tok.muted)
              .width('100%')
              .padding({ left: 14, right: 14, top: 6, bottom: 6 })
              .backgroundColor(Tok.surface)
              .border({ width: { top: 1 }, color: Tok.hair })
          }
        }
        .width('100%')
        .height('100%')
        .alignItems(HorizontalAlign.Center)
        .backgroundColor(Tok.base)
      }
    }
    .width('100%')
    .height('100%')
  }

  // ═══ 弹层内容 ═══

  @Builder
  SheetTitle(text: string) {
    Text(text)
      .fontSize(13)
      .fontColor(Tok.muted)
      .padding({ left: 16, top: 14, bottom: 6 })
      .width('100%')
  }

  /** 快捷操作主层（对齐 Android 的「快捷操作」卡片列表）。 */
  @Builder
  ActionsSheet() {
    this.SheetTitle('快捷操作')
    this.ActionRow('切换模型', this.repo.model.length > 0 ? this.repo.model : '默认', () => {
      this.repo.fetchModels(); // 打开选择层前拉目录（对齐 Android LaunchedEffect）
      this.sheet = 'model';
    })
    this.ActionRow('思考深度', this.repo.effort.length > 0 ? this.repo.effort : '默认', () => {
      this.sheet = 'effort';
    })
    this.ActionRow('模式', this.modeLabel(this.repo.currentModeKey()), () => {
      this.sheet = 'mode';
    })
    this.ActionRow('改动文件', `${this.repo.changedFiles.length > 0 ? this.repo.changedFiles.length : ''}`, () => {
      this.repo.fetchChangedFiles();
      this.sheet = 'files';
    })
    this.ActionRow('压缩上下文', '', () => {
      this.sheet = 'none';
      this.repo.sendCommand('/compact');
    })
    if (this.repo.hasSimplify()) {
      this.ActionRow('简化', '', () => {
        this.sheet = 'none';
        this.repo.sendCommand('/simplify');
      })
    }
    // 清空会话（危险色）
    Row() {
      Text('清空会话')
        .fontSize(15)
        .fontColor(Tok.danger)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 13, bottom: 13 })
    .border({ width: { top: 1 }, color: Tok.hair })
    .onClick(() => {
      this.sheet = 'none';
      this.repo.clearConversation();
    })
    Blank().height(10)
  }

  @Builder
  ActionRow(label: string, value: string, onTap: () => void) {
    Row() {
      Text(label)
        .fontSize(15)
        .fontColor(Tok.tx)
        .layoutWeight(1)
      if (value.length > 0) {
        Text(value)
          .fontSize(13)
          .fontColor(Tok.muted)
          .maxLines(1)
      }
      Text('›')
        .fontSize(15)
        .fontColor(Tok.muted)
        .padding({ left: 6 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 13, bottom: 13 })
    .border({ width: { top: 1 }, color: Tok.hair })
    .onClick(onTap)
  }

  /** 模型选择层（真实目录：网关 id 优先，其次标准别名；打开即拉取）。 */
  @Builder
  ModelSheet() {
    this.SheetTitle('切换模型 · 下个回合生效')
    if (this.repo.modelsError.length > 0) {
      Text(this.repo.modelsError)
        .fontSize(12)
        .fontColor(Tok.danger)
        .padding({ left: 16, bottom: 8 })
        .width('100%')
    }
    if (this.repo.modelChoices().length === 0) {
      Text('加载中…')
        .fontSize(13)
        .fontColor(Tok.muted)
        .padding({ left: 16, bottom: 10 })
        .width('100%')
    }
    ForEach(this.repo.modelChoices(), (id: string) => {
      this.OptionRow({ key: id, label: id, desc: '' }, this.repo.model === id, () => {
        this.sheet = 'none';
        this.repo.switchModel(id);
      })
    }, (id: string) => id)
    Blank().height(10)
  }

  /** 思考深度选择层（daemon 下发档位优先）。 */
  @Builder
  EffortSheet() {
    this.SheetTitle('思考深度 · 下个回合生效')
    this.OptionRow({ key: '', label: '默认', desc: '模型自行决定' }, this.repo.effort.length === 0, () => {
      this.sheet = 'none';
      this.repo.switchEffort('');
    })
    ForEach(this.repo.effortOptions(), (level: string) => {
      this.OptionRow({ key: level, label: level, desc: '' }, this.repo.effort === level, () => {
        this.sheet = 'none';
        this.repo.switchEffort(level);
      })
    }, (level: string) => level)
    Blank().height(10)
  }

  /** 权限模式选择层（原顶栏 chip 内容，挪进快捷操作）。 */
  @Builder
  ModeSheet() {
    this.SheetTitle('权限模式 · 下个回合生效')
    ForEach([
      { key: 'default', label: '询问', desc: '每个危险操作都询问' },
      { key: 'auto', label: '自动判断', desc: '智能判断，安全操作自动通过' },
      { key: 'acceptEdits', label: '自动编辑', desc: '文件编辑自动通过，其余仍询问' },
      { key: 'plan', label: '计划', desc: '只读分析，先出计划再执行' },
      { key: 'bypassPermissions', label: '绕过权限', desc: '全部自动通过（危险，慎用）' },
    ], (opt: ModeOption) => {
      this.OptionRow({ key: opt.key, label: opt.label, desc: opt.desc }, this.repo.currentModeKey() === opt.key, () => {
        this.sheet = 'none';
        this.repo.switchMode(opt.key);
      })
    }, (opt: ModeOption) => opt.key)
    Blank().height(10)
  }

  @Builder
  OptionRow(opt: SheetOption, selected: boolean, onTap: () => void) {
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
      if (selected) {
        Text('✓')
          .fontSize(15)
          .fontColor(Tok.accent)
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(selected ? Tok.raised : Tok.surface)
    .onClick(onTap)
  }

  /** 改动文件列表层（对齐 Android 的 changed-files：名称 + +增/−删 + 操作符，点开全文查看）。 */
  @Builder
  FilesSheet() {
    this.SheetTitle('改动文件 · 本会话')
    if (this.repo.changedFilesError.length > 0) {
      Text(this.repo.changedFilesError)
        .fontSize(13)
        .fontColor(Tok.muted)
        .padding({ left: 16, bottom: 10 })
        .width('100%')
    } else if (this.repo.changedFiles.length === 0) {
      Text('加载中…')
        .fontSize(13)
        .fontColor(Tok.muted)
        .padding({ left: 16, bottom: 10 })
        .width('100%')
    }
    List() {
      ForEach(this.repo.changedFiles, (f: ChangedFile) => {
        ListItem() {
          Row({ space: 8 }) {
            Text(f.op === 'create' ? '＋' : f.op === 'delete' ? '−' : '✎')
              .fontSize(13)
              .fontColor(f.op === 'create' ? Tok.ok : f.op === 'delete' ? Tok.danger : Tok.info)
              .width(20)
            Text(middleTrunc(f.path, 22, 14))
              .fontSize(13)
              .fontColor(Tok.tx)
              .fontFamily('monospace')
              .layoutWeight(1)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            if (f.adds >= 0 || f.dels >= 0) {
              Text(`${f.adds >= 0 ? `+${f.adds}` : ''}${f.dels >= 0 ? ` −${f.dels}` : ''}`)
                .fontSize(11)
                .fontColor(Tok.muted)
                .fontFamily('monospace')
            }
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 11, bottom: 11 })
        }
        .onClick(() => {
          this.sheet = 'none';
          this.repo.openChangedFile(f.path);
        })
      }, (f: ChangedFile) => f.path)
    }
    .width('100%')
    .constraintSize({ maxHeight: 320 })
    Blank().height(6)
  }

  /** 上下文占用圈圈（对齐 Android ContextGauge：30vp 无边框胶囊 + 环；≥80% 带百分比，≥95% 红）。 */
  @Builder
  ContextGauge() {
    Row({ space: 4 }) {
      Progress({ value: Math.min(this.repo.contextUsed / this.repo.contextWindow * 100, 100), total: 100, type: ProgressType.Ring })
        .width(15)
        .height(15)
        .color(this.gaugeColor())
        .backgroundColor(Tok.hair)
        .style({ strokeWidth: 2.2 })
      if (this.repo.contextUsed / this.repo.contextWindow >= 0.8) {
        Text(`${Math.floor(this.repo.contextUsed / this.repo.contextWindow * 100)}%`)
          .fontSize(10)
          .fontColor(this.gaugeColor())
          .fontFamily('monospace')
      }
    }
    .height(30)
    .padding({ left: 8, right: 8 })
    .backgroundColor(Tok.raised)
    .borderRadius(15)
    .onClick(() => {
      this.sheet = 'ctx'; // 点开看具体占用（对齐 Android 点 gauge 进详情）
    })
  }

  /** 上下文详情层：确切 token 数 + 占比条 + 压缩入口（对齐 Android session sheet 的 ContextBar）。 */
  @Builder
  CtxSheet() {
    this.SheetTitle('上下文占用')
    Row() {
      Text(`${groupDigits(this.repo.contextUsed)} / ${groupDigits(this.repo.contextWindow)} tokens`)
        .fontSize(15)
        .fontColor(Tok.tx)
        .fontFamily('monospace')
        .layoutWeight(1)
      Text(`${Math.floor(this.repo.contextUsed / this.repo.contextWindow * 100)}%`)
        .fontSize(15)
        .fontColor(this.gaugeColor())
        .fontFamily('monospace')
    }
    .width('100%')
    .padding({ left: 16, right: 16, bottom: 10 })
    Progress({ value: Math.min(this.repo.contextUsed / this.repo.contextWindow * 100, 100), total: 100, type: ProgressType.Linear })
      .color(this.gaugeColor())
      .backgroundColor(Tok.hair)
      .width('100%')
      .height(4)
      .padding({ left: 16, right: 16, bottom: 6 })
    Text('占用过高会让更早的对话被截断；压缩后保留要点继续。')
      .fontSize(11.5)
      .fontColor(Tok.muted)
      .padding({ left: 16, right: 16, bottom: 8 })
      .width('100%')
    this.ActionRow('压缩上下文', '/compact', () => {
      this.sheet = 'none';
      this.repo.sendCommand('/compact');
    })
    Blank().height(10)
  }

  private gaugeColor(): string {
    const r = this.repo.contextUsed / this.repo.contextWindow;
    if (r >= 0.95) return Tok.danger;
    if (r >= 0.8) return Tok.warn;
    return Tok.accent;
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
    .backgroundColor(Tok.base)
    .borderRadius(14)
    .border({ width: 1, color: Tok.hair, radius: 14 })
    .onClick(onTap)
  }
}

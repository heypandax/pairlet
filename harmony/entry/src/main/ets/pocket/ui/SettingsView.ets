import { Repository } from '../data/Repository';
import { CONTENT_MAX, ThemeMode, Tok } from './Theme';

interface ModeOption {
  key: string;
  label: string;
  desc: string;
}

/**
 * 设置页：外观（主题）/ 权限（本会话"始终允许"规则）/ 账号（当前电脑、解配）/ 关于。
 * 对齐 Android Settings 的分区结构（SectionLabel + 卡片行）。
 */
@Component
export struct SettingsView {
  @ObjectLink repo: Repository;

  build() {
    Column() {
      // 顶栏
      Column() {
        Row() {
          Text('‹ 返回')
            .fontSize(14)
            .fontColor(Tok.accent)
            .padding({ left: 12, top: 10, bottom: 10, right: 12 })
            .onClick(() => this.repo.backToProjects())
          Text('设置')
            .fontSize(17)
            .fontColor(Tok.tx)
            .fontWeight(FontWeight.Medium)
            .layoutWeight(1)
        }
        .width('100%')
        .constraintSize({ maxWidth: CONTENT_MAX })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Center)
      .backgroundColor(Tok.surface)
      .border({ width: { bottom: 1 }, color: Tok.hair })

      Scroll() {
        Column() {
          // ── 外观 ──
          this.SectionLabel('外观')
          Column() {
            Text('主题')
              .fontSize(14)
              .fontColor(Tok.tx2)
              .padding({ left: 16, top: 10, bottom: 8 })
              .width('100%')
            Row({ space: 8 }) {
              this.ThemeOption('dark', '深色')
              this.ThemeOption('light', '浅色')
              this.ThemeOption('system', '跟随系统')
            }
            .width('100%')
            .padding({ left: 16, right: 16, bottom: 14 })
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .backgroundColor(Tok.surface)
          .borderRadius(14)
          .border({ width: 1, color: Tok.hair, radius: 14 })

          // ── 新会话 ──
          this.SectionLabel('新会话')
          Column() {
            Text('默认权限模式')
              .fontSize(14)
              .fontColor(Tok.tx)
              .fontWeight(FontWeight.Medium)
              .padding({ left: 16, top: 12, bottom: 4 })
              .width('100%')
            Text('每次开新会话时应用；会话内可随时用顶栏的模式按钮临时切换。')
              .fontSize(11.5)
              .fontColor(Tok.muted)
              .padding({ left: 16, right: 16, bottom: 8 })
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
                    .fontSize(14)
                    .fontColor(Tok.tx)
                  Text(opt.desc)
                    .fontSize(11)
                    .fontColor(Tok.muted)
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                if (this.repo.defaultMode === opt.key) {
                  Text('✓')
                    .fontSize(14)
                    .fontColor(Tok.accent)
                }
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 10, bottom: 10 })
              .backgroundColor(this.repo.defaultMode === opt.key ? Tok.raised : Tok.surface)
              .onClick(() => this.repo.setDefaultMode(opt.key))
            }, (opt: ModeOption) => opt.key)
            Blank().height(6)
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .backgroundColor(Tok.surface)
          .borderRadius(14)
          .border({ width: 1, color: Tok.hair, radius: 14 })

          // ── 权限 ──
          this.SectionLabel('权限')
          Column() {
            Text('本会话"始终允许"的规则')
              .fontSize(14)
              .fontColor(Tok.tx)
              .fontWeight(FontWeight.Medium)
              .padding({ left: 16, top: 12, bottom: 4 })
              .width('100%')
            Text('会话内点过"始终允许"后，同类操作不再询问。规则随会话结束失效。')
              .fontSize(11.5)
              .fontColor(Tok.muted)
              .padding({ left: 16, right: 16, bottom: 8 })
              .width('100%')
            if (this.repo.allowRules.length === 0) {
              Text('当前没有已记下的规则')
                .fontSize(13)
                .fontColor(Tok.muted)
                .padding({ left: 16, top: 6, bottom: 14 })
                .width('100%')
            } else {
              ForEach(this.repo.allowRules, (rule: string) => {
                Row() {
                  Text(rule)
                    .fontSize(13)
                    .fontColor(Tok.tx)
                    .fontFamily('monospace')
                    .layoutWeight(1)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text('✕')
                    .fontSize(13)
                    .fontColor(Tok.muted)
                    .padding({ left: 8, right: 4, top: 4, bottom: 4 })
                    .onClick(() => this.repo.clearRule(rule))
                }
                .width('100%')
                .padding({ left: 16, right: 12, top: 10, bottom: 10 })
              }, (rule: string) => rule)
              Row() {
                Text('全部清除')
                  .fontSize(13)
                  .fontColor(Tok.danger)
              }
              .width('100%')
              .padding({ left: 16, top: 12, bottom: 14 })
              .border({ width: { top: 1 }, color: Tok.hair })
              .onClick(() => this.repo.clearAllRules())
            }
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .backgroundColor(Tok.surface)
          .borderRadius(14)
          .border({ width: 1, color: Tok.hair, radius: 14 })

          // ── 账号 ──
          this.SectionLabel('账号')
          Column({ space: 4 }) {
            Row() {
              Column({ space: 2 }) {
                Text(this.repo.paired?.displayName() ?? '未配对')
                  .fontSize(15)
                  .fontColor(Tok.tx)
                  .fontWeight(FontWeight.Medium)
                Text(this.repo.paired?.relay ?? '')
                  .fontSize(11)
                  .fontColor(Tok.muted)
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
              Text(`${this.repo.bindings.length} 台已配对`)
                .fontSize(12)
                .fontColor(Tok.tx2)
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 12, bottom: 8 })
            Row() {
              Text('解配当前电脑')
                .fontSize(14)
                .fontColor(Tok.danger)
            }
            .width('100%')
            .padding({ left: 16, top: 10, bottom: 14 })
            .border({ width: { top: 1 }, color: Tok.hair })
            .onClick(() => this.repo.unpair())
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .backgroundColor(Tok.surface)
          .borderRadius(14)
          .border({ width: 1, color: Tok.hair, radius: 14 })

          // ── 关于 ──
          this.SectionLabel('关于')
          Column({ space: 4 }) {
            Text('CC Pocket · HarmonyOS')
              .fontSize(14)
              .fontColor(Tok.tx)
            Text('零知识 E2E 中继 · 版本 0.1.0')
              .fontSize(11.5)
              .fontColor(Tok.muted)
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .padding({ left: 16, top: 12, bottom: 14 })
          .backgroundColor(Tok.surface)
          .borderRadius(14)
          .border({ width: 1, color: Tok.hair, radius: 14 })

          Blank().height(24)
        }
        .width('100%')
        .constraintSize({ maxWidth: CONTENT_MAX })
        .padding({ left: 16, right: 16 })
      }
      .layoutWeight(1)
      .width('100%')
      .align(Alignment.Top)
    }
    .width('100%')
    .height('100%')
    .alignItems(HorizontalAlign.Center)
    .backgroundColor(Tok.base)
  }

  @Builder
  SectionLabel(text: string) {
    Text(text)
      .fontSize(12)
      .fontColor(Tok.muted)
      .fontWeight(FontWeight.Medium)
      .width('100%')
      .padding({ left: 4, top: 18, bottom: 8 })
  }

  @Builder
  ThemeOption(mode: ThemeMode, label: string) {
    Text(label)
      .fontSize(13)
      .fontColor(this.repo.themeMode === mode ? '#FFFFFF' : Tok.tx2)
      .textAlign(TextAlign.Center)
      .layoutWeight(1)
      .padding({ top: 9, bottom: 9 })
      .backgroundColor(this.repo.themeMode === mode ? Tok.accent : Tok.raised)
      .borderRadius(10)
      .border({ width: 1, color: this.repo.themeMode === mode ? Tok.accent : Tok.hair, radius: 10 })
      .onClick(() => this.repo.setThemeMode(mode))
  }
}

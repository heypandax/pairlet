import { scanBarcode } from '@kit.ScanKit';
import { E2ECrypto } from '../crypto/E2ECrypto';
import { selfTestHandshake } from '../crypto/E2ESession';
import { Repository } from '../data/Repository';
import { Tok } from './Theme';

/** 配对页：扫码（主路径，对齐手机 App）/ 6 位码 → redeem → 自动连接。附开发期加密自检结果。 */
@Component
export struct PairingView {
  @ObjectLink repo: Repository;
  @State code: string = '';
  @State error: string = '';
  @State busy: boolean = false;

  aboutToAppear(): void {
    if (this.repo.selfTestLine.length === 0) {
      this.repo.selfTestLine = '自检中…';
      E2ECrypto.selfTest().then(async (failures) => {
        const all = failures.concat(await selfTestHandshake());
        this.repo.selfTestLine = all.length === 0
          ? '✓ 加密自检通过（HKDF / P-256 / ECDH / GCM / 全链路握手）'
          : `✗ 自检失败：${all.join(' | ')}`;
      });
    }
  }

  /** 系统扫码 UI（无需相机权限），识别 ccpocket://pair?... 或 ?code= 短链。 */
  private scan(): void {
    this.busy = true;
    this.error = '';
    scanBarcode.startScanForResult(getContext(this))
      .then((r) => this.repo.pairLink(r.originalValue))
      .then((err: string) => {
        this.busy = false;
        this.error = err;
      })
      .catch(() => {
        this.busy = false; // 用户取消 / 未识别
      });
  }

  build() {
    Column({ space: 18 }) {
      // "添加电脑"进入时提供返回（已有绑定不该被困在配对页）
      if (this.repo.bindings.length > 0) {
        Row() {
          Text('‹ 返回')
            .fontSize(14)
            .fontColor(Tok.accent)
            .padding({ left: 12, top: 10, bottom: 6, right: 12 })
            .onClick(() => this.repo.handleBack())
        }
        .width('100%')
      }

      Blank()

      Text(this.repo.bindings.length > 0 ? '添加电脑' : 'CC Pocket')
        .fontSize(30)
        .fontColor(Tok.tx)
        .fontWeight(FontWeight.Bold)
      Text('用手机驱动这台电脑上的 Claude Code')
        .fontSize(13)
        .fontColor(Tok.tx2)

      Button(this.busy ? '配对中…' : '扫码配对')
        .width('62%')
        .height(44)
        .fontSize(16)
        .fontColor('#FFFFFF')
        .backgroundColor(Tok.accent)
        .enabled(!this.busy)
        .onClick(() => this.scan())

      Text('或在电脑 daemon 上执行 pair，把 6 位配对码输到这里')
        .fontSize(13)
        .fontColor(Tok.tx2)
        .textAlign(TextAlign.Center)
        .padding({ left: 32, right: 32 })

      TextInput({ placeholder: '6 位配对码', text: this.code })
        .type(InputType.Number)
        .maxLength(6)
        .fontSize(24)
        .fontColor(Tok.tx)
        .placeholderColor(Tok.muted)
        .textAlign(TextAlign.Center)
        .width('62%')
        .backgroundColor(Tok.raised)
        .borderRadius(12)
        .padding({ top: 10, bottom: 10 })
        .onChange((v: string) => {
          this.code = v;
          this.error = '';
        })

      Button(this.busy ? '配对中…' : '配对')
        .width('62%')
        .height(44)
        .fontSize(16)
        .fontColor('#FFFFFF')
        .backgroundColor(this.code.trim().length === 6 ? Tok.accent : Tok.raised)
        .enabled(this.code.trim().length === 6 && !this.busy)
        .onClick(() => {
          this.busy = true;
          this.repo.pair(this.code).then((err) => {
            this.busy = false;
            this.error = err;
          });
        })

      if (this.error.length > 0) {
        Text(this.error)
          .fontSize(13)
          .fontColor(Tok.danger)
          .textAlign(TextAlign.Center)
          .padding({ left: 24, right: 24 })
      }

      if (this.repo.statusLine.length > 0) {
        Text(this.repo.statusLine)
          .fontSize(13)
          .fontColor(Tok.danger)
          .textAlign(TextAlign.Center)
          .padding({ left: 24, right: 24 })
      }

      Blank()

      Text(this.repo.selfTestLine)
        .fontSize(11)
        .fontColor(Tok.muted)
        .textAlign(TextAlign.Center)
        .padding({ left: 16, right: 16, bottom: 24 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Tok.base)
  }
}

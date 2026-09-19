# 2.1.1 发布与验收记录

状态：[2.1.1 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.1.1)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备。iOS 与 TestFlight 等待 Apple 审核；HarmonyOS 后续补发。

## 发布来源与检查

- 不可变标签 `v2.1.1`：`a0343f24d731fccb5c9d7ab7ae0874847ed4844f`。
- [主 CI](https://github.com/heypandax/pairlet/actions/runs/35463402196) 通过：全量服务端测试、桌面与 Android 编译，以及 Windows 检查。
- [iOS 原生预演](https://github.com/heypandax/pairlet/actions/runs/35463685068) 通过：unsigned archive、包内观测配置及本地符号检查。这不代替真机交互验收。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/35464907864) 从上述标签执行。Linux 桌面包被内容检查拦截，未上传失败包：Compose 的 deb/rpm 任务默认另建镜像，遗漏已完成的 classpath 压缩。
- Linux 专用打包修正 `fd4366252202c182ff8140ca4bb460b864253b5e` 显式让 deb/rpm 使用 `createDistributable` 的镜像。[Linux 桌面重建](https://github.com/heypandax/pairlet/actions/runs/35465413154) 使用发布手册允许的 `only_linux_desktop` 模式，固定使用该次 dispatch 的提交；未移动版本标签。
- Linux 双架构重建及 checksum job 已通过。两个架构均验证了 deb/rpm 载荷、deb 解压后的真实启动器和 AppImage，在 8 KB 管道条件下启动成功；只有通过检查的包才上传。
- 打包修正后的 [完整 CI](https://github.com/heypandax/pairlet/actions/runs/35465412244) 通过。
- [iOS 正式发布](https://github.com/heypandax/pairlet/actions/runs/35464910149) 全部 job 通过。ASC 回读：2.1.1／build 58，`WAITING_FOR_REVIEW`、`AFTER_APPROVAL`；中英文各 9 项文案及审核备注匹配，Messaging and Chat 为 Yes。尚未获 App Store 审核批准，不能写成已上架。
- TestFlight 将 build 58 关联至既有 Public Beta 组，并提交 Beta App Review；回读为 `WAITING_FOR_BETA_REVIEW`。[原公共链接](https://testflight.apple.com/join/8z26MWWr) 保持不变，新构建需等待审核通过后开放。
- HarmonyOS 按本次用户安排后续补发；没有可用签名构建机器，未派发该 job。

## 生产与安装包

- 配套 relay 已部署，进程持续运行、健康端点正常。部署时校正了 Windows 归档的 Unix 权限；首次失败由部署脚本自动回滚，修正后的部署成功。
- Windows 正式 daemon ZIP 的 GitHub SHA-256 与本地下载一致；隔离运行 `cc-pocket-daemon.exe --version` 返回 `pairlet version 2.1.1`，退出码 0。此检查未启动或注册 daemon 服务。
- 24 个发布资产通过完整性检查：包含五种 daemon、双架构 macOS DMG、Windows MSI、Android APK，以及双架构 Linux deb/rpm/AppImage 和版本别名；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.1.1/SHA256SUMS) 与 GitHub 的每个资产摘要一致，别名哈希相同。
- Release 于 2026-09-19 20:08 UTC 公开并设为 latest，公开 Windows 下载返回 HTTP 200。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/13cf9fdf47e6e4474cc8dc51e3f41327c0b27853) 与 [Scoop 更新](https://github.com/heypandax/scoop-bucket/commit/0cec582bc0126a8ec96359c745e1b2d6b9e1027c) 已回读确认版本和最终哈希。首次资产流程因 Linux 检查失败而跳过 Scoop，恢复流程按设计只构建 Linux，故在完整发布后补齐包管理器更新。
- 既有镜像同步服务完成且退出码 0；公开 latest.json 为 2.1.1，完整资产映射及校验清单一致，五种 daemon 下载均返回 HTTP 200 和预期文件大小。

## Issue 验收边界

| Issue | 已验证的范围 | 尚未验证的范围／处理原则 |
|---|---|---|
| #375 | 广播完成屏障、保留权限切断断言、发布提交全量 CI | 已关闭；出现新的竞态再按新日志跟进 |
| #384 | 长正文展开／收起、9 项 UI 回归 | 已关闭；iOS 商店状态单独记录 |
| #387 | 归属和结构校验、拒绝符号链接及越界清理；27 项 Linux 测试 | 已关闭；不批量清理无法确认归属的历史记录 |
| #385 | Debian Testing / WSL / Xvfb 下 8 KB 管道故障复现与候选修复；正式包内容检查 | 保留，等待原反馈机器升级验证 |
| #386 | ZCode 3.11.2 / CLI 0.16.5 安装布局、40 项测试及实际 CLI 往返 | 保留，等待原反馈者的版本／布局验证 |
| #388 | 初始化／新建／恢复阶段报错及排队消息结算 | 保留，Web 工作区可见性与真实双向接管仍未完成验收 |
| #389 | 登记 ACK、控制通道、重试与旧请求取消；生产 relay 配套更新 | 保留，APNs／FCM 实际送达、权限和 token 恢复仍需真机验证 |
| #390 | 原生 WebView NonCooperative 交互、iOS 归档 | 保留，五类网页样例仍需受影响设备验证 |

#352、第四批 #381／#342 不属于本次发布后的 issue 操作范围。

以上八项均已写回 GitHub 并回读状态：#375／#384／#387 为 closed，#385／#386／#388／#389／#390 保持 open；没有以构建成功代替剩余的用户环境验收。

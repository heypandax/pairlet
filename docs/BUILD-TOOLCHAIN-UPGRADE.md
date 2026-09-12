# 构建链升级记录

## 固定版本

| 组件 | 升级前 | 升级后 |
| --- | --- | --- |
| Gradle Wrapper | 8.13 | 9.4.1（校验官方 SHA-256） |
| Kotlin / Compose Compiler | 2.1.21 | 2.4.20 |
| Compose Multiplatform | 1.7.3 | 1.12.0 |
| Compose Material3 | 随 Compose 1.7.3 | 1.9.0（独立版本） |
| Android Gradle Plugin | 8.7.3 | 9.2.1 |
| kotlinx.coroutines | 1.9.0 | 1.11.0 |
| kotlinx.serialization | 1.7.3 | 1.11.0 |
| Google Services Plugin | 4.4.2 | 4.5.0 |
| Crashlytics Gradle Plugin | 3.0.2 | 3.0.8 |
| Foojay Resolver | 0.9.0 | 1.0.0 |
| Android compile SDK | 35 | 37 |

JDK 仍为 17，Android min SDK 26 / target SDK 35、iOS 最低版本 15.0 及各端应用标识保持原值。运行时的 Ktor、加密、终端和 Firebase SDK 不在这次构建链迁移中独立升级。Material Icons Extended 保留最后使用的 1.7.3。

版本依据：[Kotlin Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Compose 兼容说明](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)、[AGP 发布说明](https://developer.android.com/build/releases/about-agp)。AGP 9.2 对应 Gradle 9.4.1；使用项目 `./gradlew`，不依赖全局 `gradle`。

## 迁移要点

- 共享模块改用 `com.android.kotlin.multiplatform.library`，按 [Android KMP 插件迁移方式](https://developer.android.com/kotlin/multiplatform/plugin)新增 `:mobile:androidApp` 作为 Android 宿主。Manifest、Android res、Firebase 配置与签名配置属于宿主；Activity 和平台实现仍留在共享模块。
- Android 命令变为 `./gradlew :mobile:androidApp:assembleDebug` / `assembleRelease`；CI、发布脚本与使用文档同步更新。真实 `google-services.json` 放在 `mobile/androidApp/`，仍被 Git 忽略。
- Gradle 9 打包脚本使用注入的 `ExecOperations`；桌面应用的文件名、标识与签名步骤保留。
- Compose 现在为跨 Text 选择自动插入换行；DiffView 移除旧的手动分隔符，并用复制测试验证真实空行仍被保留。
- 桌面项目行固定悬停按钮占位，防止鼠标进入时“新建会话”按钮移动导致点击落空。
- UI 测试迁移至 [Compose v2 测试 API](https://blog.jetbrains.com/kotlin/2026/05/compose-multiplatform-1-11-0/)，使用排队调度器，显式推进冻结的测试时钟，并注入新版异步剪贴板。
- Kotlin 更严格的属性检查要求 SeedDesktopModel 的私有 setter 属性明确为 final。iOS 文件写入显式创建 NSString，避免不可成立的类型转换。

## 复现命令

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0'
cp mobile/androidApp/google-services.json.template mobile/androidApp/google-services.json
bash scripts/check-all.sh --no-daemon :mobile:androidApp:assembleDebug
./gradlew :mobile:composeApp:linkDebugFrameworkIosArm64 :mobile:composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :mobile:composeApp:createDistributable -Pcompose.desktop.packaging.checkJdkVendor=false
```

最后一个参数仅用于本机 Homebrew JDK 打包，与仓库 `scripts/release-desktop-macos.sh` 一致；CI 使用 Temurin。以上命令不会更新本机 daemon、安装真机或发布版本。

## 本地验证

环境：Apple Silicon macOS、JDK 17.0.15、Xcode 26.3、iOS 26.2 模拟器。

| 检查 | 结果 |
| --- | --- |
| protocol / observability / observability-sentry / daemon / relay JVM 测试 | 2,301 项，0 失败，其中 2 项按条件跳过 |
| App 完整 desktopTest | 1,460 项通过 |
| protocol / observability / App iOS Simulator 测试 | 495 项通过 |
| scripts / support Python 测试 | 81 项通过 |
| Android debug APK | 构建与签名验证通过；回读 applicationId、versionCode、min/target SDK；双语 Compose 资源及诊断配置均存在 |
| macOS createDistributable | 构建、签名、品牌兼容检查及配置资源验证通过；运行时含 java.net.http 与 JavaFX 所需 JDK 模块 |
| iOS arm64 / Simulator arm64 Debug Framework | 编译与链接通过 |
| Xcode iOS 宿主（generic iOS、Debug、关闭签名） | BUILD SUCCEEDED；Swift / Kotlin Framework 集成及资源打包通过 |

共 4,256 项 Gradle 测试：4,254 项通过，2 项跳过。平台打包使用开发测试配置；这些结果不代表 Windows/Intel Mac 实机、手机真机、商店发布或线上服务验收。项目中仍有不阻止构建的旧 API 弃用提示。

## 落地与清理

经过验证的改动已逐文件比对并同步到主工作区，主工作区的 Gradle 9.4.1 离线配置检查通过。原有本地 Firebase 配置已复制到 Android 宿主的新位置，旧文件继续保持 Git 忽略。

确认无进程使用后，删除 `~/.gradle/caches/8.13` 和 `~/.gradle/wrapper/dists/gradle-8.13-bin`，删除前合计占用 2,139,164 KiB（约 2.04 GiB）。9.5.1 缓存和下载包在本次最终盘点时已不存在；保留 9.4.1 与共享依赖缓存。此次构建会生成新版本所需的缓存，因此这不是整机可用空间的净变化。

测试日志、各模块测试计数、XML 结果压缩包及清理记录保存在本机 `build/reports/toolchain-upgrade/`（Git 忽略）。用于验证的临时工作区、模拟器与 Xcode 构建目录已清理。

## 开发设备同步

上述测试完成后，使用主工作区的真实本地配置，按桌面端 → Pandaa iPhone → 本机 daemon 的顺序执行开发同步：

- `scripts/update-local-desktop.sh`：更新 `/Applications/CC Pocket.app` 并重新启动；同步时确认安装包与构建产物的主 JAR 哈希一致，包含 Kotlin 2.4.20，应用版本为 2.0.0。
- `scripts/install-pandaa.sh`：完成签名构建、直接安装并启动 Pandaa iPhone 上的 2.0.0（build 19）；通过 devicectl 确认应用进程运行。禁用了此次无需使用的 OTA 回退。
- `scripts/update-local-daemon.sh`：更新 launchd 管理的本机服务；同步时确认恰好一个 daemon 进程、存在 relay 连接，且安装目录全部 70 个 JAR 与构建产物一致。

同步日志与回读结果保存在 `build/reports/toolchain-upgrade/device-sync/`。这些检查证明开发安装与启动成功，尚不包含完整真机业务回归、商店发布或云端 relay 部署。

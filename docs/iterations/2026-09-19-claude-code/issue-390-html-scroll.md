# #390：iOS HTML 附件预览可滚动

来源：[Issue #390](https://github.com/heypandax/pairlet/issues/390)。批次 3；建议 P2。状态：待原生验收——样例与草案补丁已就位，未在 iOS 编译，未在真机复现。

## 目标与事实

在手机打开长 HTML 附件后，用户能从顶部滚动至文档底部；源码／预览切换、关闭、返回聊天仍正常。

报告的 daemon 版本为 2.1.0，客户端选项为 iPhone／iPad，电脑 macOS Apple Silicon；准确 App build、设备、样例 HTML 未提供。不要直接把问题归因于 daemon 或假定全部 HTML 都无法滚动。

当前结构：`HtmlFileBody` 在有界 Column 的剩余高度内放平台预览；iOS 用 `UIKitView` 承载 `WKWebView`；公共 `htmlPreviewDocument` 创建固定满屏 wrapper，外层 `overflow:hidden`，内部是 `sandbox="allow-scripts"` 的 srcdoc iframe。外层禁止滚动是当前设计，不能单凭它判断根因；内部 iframe 是否有滚动范围、原生触摸是否到达 WebView，都需确认。

入口：

- [HtmlPreview.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/HtmlPreview.kt)：wrapper、CSP、iframe、源码切换。
- [HtmlPreview.ios.kt](../../../mobile/composeApp/src/iosMain/kotlin/dev/ccpocket/app/ui/HtmlPreview.ios.kt)：原生宿主、生命周期、load 去重。
- [HtmlPreview.android.kt](../../../mobile/composeApp/src/androidMain/kotlin/dev/ccpocket/app/ui/HtmlPreview.android.kt)、[HtmlPreview.desktop.kt](../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/ui/HtmlPreview.desktop.kt)：共用 wrapper 的回归面。
- [HtmlPreviewRenderingTest.kt](../../../mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/ui/HtmlPreviewRenderingTest.kt)、[HtmlPreviewPolicyTest.kt](../../../mobile/composeApp/src/commonTest/kotlin/dev/ccpocket/app/ui/HtmlPreviewPolicyTest.kt)。

## 先建立最小样例

| 样例 | 用途 |
|---|---|
| 无脚本的 100 段正文，首尾有唯一文字 | 证明普通长文可滚动到底 |
| iframe 内有独立 overflow 容器 | 区分嵌套滚动与整页滚动 |
| 加载后追加内容、图片改变高度 | 检验动态高度与布局更新 |
| 100vh／固定定位布局 | 区分用户 HTML 自身限制与预览宿主 |
| 中文、长代码、表格、横向溢出 | 检验垂直／水平手势与缩放 |

优先用纯本地、无外部网络资源的样例；若用户原文件可取得，再用它验证同一问题。样例进入可复现测试目录，现场私有 HTML 不提交。

## 诊断与补丁选择

1. 在当前 iOS App 的实际附件入口重现，记录 viewport、WKWebView frame、scrollView contentSize／offset，以及手势发生时谁收到事件。对照同样 HTML 的受控原生 WebView；确认是内容不溢出、触摸被 Compose 宿主消费，还是 iframe 手势分发异常。
2. 核对项目当前 Compose 版本所支持的 UIKit interop 交互配置，按实测选择；不要照抄其他版本 API，也不要未经验证全局拦截触摸。
3. 若为宿主触摸交接问题，在此 HtmlPreview 的原生边界修复交互策略，保留周围按钮／关闭手势可用。只改 HTML 预览，不扩到 #352 的通用 iPad 输入。
4. 若为 wrapper／iframe 高度或滚动容器问题，调整负责滚动的层级，使 viewport 有界且内部文档完整可达。禁止给整个聊天列表套新滚动容器，不按内容无限增高整个聊天 item。
5. 若只有特定 HTML 的 `overflow:hidden` 等作者样式导致不可滚动，明确文档自身行为；不强行重写所有用户 CSS。提供源码仍可查看，避免破坏正常交互页面。
6. 核对 recomposition／旋转／按钮切换是否意外 reload 并重置滚动；当前相同 document 不重复 load 的语义要保留。切换到另一个文件时不能显示上一个文档。

## 必须保留的隔离边界

现有 srcdoc iframe 使用不透明 sandbox origin，只允许脚本；不添加 allow-same-origin 来换取方便的滚动测量，不暴露 native JS bridge、file/content URL 或项目文件读取。保留 CSP、非持久 WebKit data store 和失败回退源码行为。

如确需变更共用 wrapper，先列清新旧脚本、导航、网络及本地资源权限，再验证原有隔离测试。不要通过移除 iframe 或直接赋予同源权限把预览变成宿主页面。

## 验收

- 在至少一台专用 iPhone 的真实 HTML 附件预览里，手指能滚动到尾部唯一标记并返回顶部；记录设备、iOS、App build。
- 短页不异常抖动；长页、嵌套滚动、动态追加内容均按其文档布局工作。
- 源码／预览、关闭／重开、横竖屏切换正常，不误触外层聊天、没有空白或反复 reload。
- iPad 普通触屏可做回归，但不纳入 #352 妙控键盘／输入法问题。
- 共用 wrapper 改动须回归 Android 与桌面 HTML 渲染和隔离；JavaFX 的 headless 跳过明确记为未执行。
- 单测证明 wrapper 策略，原生实测证明触摸滚动，二者不可互相替代。无 iOS 环境时停在待原生验收，不凭桌面 WebKit 成功宣称修复。

## 结果记录（实施后填写）

- 样例、原生复现、滚动故障层：五个样例落在 `mobile/composeApp/src/desktopTest/resources/htmlpreview/`（长正文、嵌套滚动、动态增高、`100vh` 固定布局、中文／长代码／宽表格），纯本地无外部资源，标记统一带 `-390` 后缀，说明见同目录 `README.md`。**没有原生复现**——本机是 Windows，无 iOS 环境、无 iPhone。故障层只能排除、不能确认：wrapper 一层已被证据排除（`html`→`body`→`iframe` 的 `height:100%` 链每一级都有确定高度，iframe 无 `scrolling="no"`，因此确实留有滚动范围；且 iOS 13 起 WebKit 已关闭 frame flattening 并于 2022 年删除相关代码，iframe 在 WKWebView 里有独立的异步滚动视口）；宿主抢手势一层也被源码排除（文件查看器是整屏路由，`ChatScreen` 提前 `return`，聊天 `LazyColumn` 根本没被组合，祖先链全是 `Box`／`Column`，无 `verticalScroll`／Pager／sheet／`nestedScroll`／拖拽 `pointerInput`，`UIKitView` 拿到的是有界确定高度）。剩下最可能的是 interop 触摸交接（Compose 默认 `Cooperative` 先扣住落在原生视图上的触摸），以及用户 HTML 自身把自己钉死（样例四就是这种情况）——两者都只能在真机上分辨。
- 采用的宿主／容器改动及权限对照：只改 `HtmlPreview.ios.kt` 一处，给 `UIKitView` 加 `properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative)`，让触摸直接进 `WKWebView`。**共用 wrapper 未改，权限增量为零**：srcdoc iframe 仍只有 `allow-scripts`，没有 `allow-same-origin`，没有 native JS bridge，没有 file／content URL，CSP、非持久 `WKWebsiteDataStore`、失败回退源码、相同 document 不重复 load 全部原样保留；聊天列表没有套新滚动容器，聊天 item 没有按内容增高。代价是预览区域内的手势 Compose 不再能拦截——当前没有覆盖该区域的手势（iOS 的 `SystemBackHandler` 是空实现，导航靠 ← 按钮），预览／源码按钮和返回按钮都在该视图之外，不受影响。
- iOS 真机、Android／桌面回归、提交：iOS **未编译、未真机验收**（Windows 无 Kotlin/Native iOS 工具链，Compose 的 iOS klib 本机也没有缓存，`UIKitInteropProperties` 的确切签名未经编译器校验）。Android **未回归**（未改共用 wrapper，也未改 Android 实现）。桌面：`:mobile:composeApp:desktopTest --tests '*HtmlPreviewRenderingTest' --tests '*HtmlPreviewPolicyTest' --tests '*HtmlPreviewScrollSampleTest'` 通过（`BUILD SUCCESSFUL in 3m 54s`），`:mobile:composeApp:compileKotlinDesktop` 通过；JavaFX 的 headless 跳过本次未触发，测试在有显示器的本机真实跑了 WebKit。**未提交、未推送。**
- 未验收项及客户端发布状态：待验收项——iOS 侧编译、真机用五个样例逐条滑到尾部标记、短页不抖动、源码／预览与关闭重开、横竖屏、iPad 触屏回归（不含 #352 的妙控键盘／输入法）。补丁停在草案：`NonCooperative` 是基于「wrapper 与宿主两层均被排除」推出的唯一剩余宿主侧抓手，不是实测确认的根因；若真机上问题依旧，下一步应先在真机记录 `WKWebView.scrollView` 的 `contentSize`／`contentOffset` 与触摸归属，再判断是否属于用户 HTML 自身行为。客户端未发布。

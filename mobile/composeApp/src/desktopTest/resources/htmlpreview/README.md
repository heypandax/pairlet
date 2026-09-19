# HTML 预览滚动样例（#390）

给 [Issue #390](https://github.com/heypandax/pairlet/issues/390)「iOS HTML 附件预览可滚动」用的最小样例。全部纯本地：不引用任何网络字体、脚本、图片或样式表，唯一的位图是内联 `data:` SVG。现场用户的私有 HTML 不放这里。

两种用法：

- 自动：`HtmlPreviewScrollTest`（`src/desktopTest/kotlin/dev/ccpocket/app/ui/`）读这些文件，把它们送进 `htmlPreviewDocument()` 包装后的真实 WebKit 引擎，测量 wrapper 这一层有没有给文档留出滚动范围。
- 手动：真机验收时把需要的文件当作普通 HTML 附件丢进会话目录，在手机上打开预览，用手指从顶部标记滑到底部标记。

| 文件 | 用途 | 期望行为 |
|---|---|---|
| `long-text.html` | 无脚本的 100 段正文 | 能从 `SCROLL-TOP-MARKER-390` 滑到 `SCROLL-BOTTOM-MARKER-390` |
| `nested-scroll.html` | 页面里套一个 `overflow:auto` 容器 | 在灰框上起手滑动灰框，在灰框外起手滑动整页 |
| `dynamic-growth.html` | 首屏很短，300 ms 后追加 60 段并有一张改变高度的图 | 首次布局之后才出现的滚动范围同样可达 |
| `viewport-fixed.html` | 作者自己写死 `100vh` + `overflow:hidden` + 固定头尾 | 文档级**本来就不该**滚动，只有 `#pane` 动；用来区分「宿主滚不动」和「文档自己不滚」 |
| `wide-content.html` | 中文正文、不换行的长代码、宽表格、1400px 宽块 | 垂直与水平手势各自生效，缩放正常 |

所有唯一标记都带 `-390` 后缀，方便在截图、录屏和断言里检索。

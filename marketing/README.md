# 网站与商店素材工具

这里公开维护发布所需、可以复现的素材生成管线。生成时使用真实产品 UI 和脚本化演示数据。

| 入口 | 生成目标 |
|---|---|
| [site/README.md](site/README.md) | 官网与根 README 使用的 `site/assets/product/` |
| [appstore/generate-assets.sh](appstore/generate-assets.sh) | `fastlane/screenshots/` 的商店截图 |
| [preview/README.md](preview/README.md) | `fastlane/previews/` 的 App Store 预览视频 |

定稿媒体是网站或发布工作流的输入，保留在 Git 中；中间帧、缓存、输出草稿在各管线的忽略目录。发布前检查 `python3 scripts/check-public-content.py` 和 `python3 scripts/check-appstore-content.py`。

旧 `marketing/video/` 是单次宣传活动的分镜、生活镜头与供应商工具，已停止跟踪，本地副本可继续使用。需要共享其通用能力时，应先整理为有独立入口、无私人账号与活动回执依赖的工具。归档与恢复见[仓库内容规则](../docs/REPOSITORY-CONTENT.md)。

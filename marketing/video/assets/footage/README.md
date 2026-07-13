# assets/footage —— AI 生活镜头（Seedance）

> 生成器：**Seedance**（可灵已移除，2026-07-13 裁定）。降级链：Seedance 多候选 → 换 Seedream 关键帧重试 → StageScene 图形卡。生活素材**不阻塞**主管线（开发/animatic 用 `--allow-placeholder` 占位；正式渲染缺素材直接报错）。

## 最小镜头集（第一版只做这 3 个）

```text
01-leave-desk         起身离开书桌（钩子段，用 1~1.5s）
02-elevator-realize   电梯/楼道里掏手机（钩子段，用 1~1.5s）
03-ending-life        收尾生活状态（outro 段，用 ~2s）
```

## 目录约定

```text
assets/footage/<scene-id>/
├── seedance-01.mp4 …      候选（≥2~3 个）
├── selected.mp4           人选定稿——管线只认这个文件名
└── generation.json        生成记录
```

`generation.json`：

```json
{
  "sceneId": "01-leave-desk",
  "primaryProvider": "seedance",
  "prompt": "…",
  "negativePrompt": "…",
  "referenceFiles": [],
  "selectedFile": "seedance-02.mp4",
  "notes": "动作自然，屏幕无可读文字"
}
```

## 素材规格（第九节）

- 9:16，≥1080×1920；生成 4～6 秒，最终只用 1～2.5 秒；30fps（管线抽帧时统一）
- 不用模型自带字幕/对白/原音；手机与电脑屏幕**不出现可读 UI**
- 视觉：真实纪录片感、低饱和、深蓝墨绿高级灰＋少量暖橙光；手部/背影/肩后视角、不正面露脸
- 禁：网红摆拍、赛博朋克、代码雨、豪华办公室、可识别品牌 Logo

## 挑选流程

1. 候选放入场景目录 → `./video.sh footage-contact-sheet` 出缩略对比页
2. 人眼挑选：手不变形、动作不反向、屏幕无乱码文字、镜头不过度电影化
3. 把选中的复制/链接为 `selected.mp4`，填 `generation.json`
4. `./video.sh validate storyboard-v3.json` 确认不再占位

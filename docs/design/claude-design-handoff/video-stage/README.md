cat > docs/design/claude-design-handoff/video-stage/README.md <<'EOF'
# video-stage —— 宣传视频舞台设计系统（Video Stage 1.0）

2026-07-12 经 claude.ai/design 主项目生成并导出。在线板：
`https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FVideo+Stage+1.0.html`

## 内容

- `Video Stage 1.0.html` + `video-stage.css` + `video-stage.js` —— 四屏：
  - **V1** 舞台解剖与字体系统（安全区标注：top 96/right 72/bottom 168 final px；
    花字 88px final、关键词 highlighter chip 陶土 20% 占字形下 46%；手机壳 528px final + 陶土 rim glow；
    背景 = 单 glow + 26px 点阵 4.5% 白 radial 渐隐，禁粒子/噪点/第二色相）
  - **V2** 五个舞台变体（真 UI 屏各一帧 + done 拍推送横幅）
  - **V3** 非手机场景（终端 cinematic / montage 双面板 / 收尾卡单 CTA）
  - **V4** 运动规格表（各元素入场偏移/时长/贝塞尔、转场 = 8 帧 dip-to-black、
    推送横幅 -120px 520ms 过冲曲线 hold 1600ms exit 360ms）

## 落地状态

**已实现**（同日按稿重做 `marketing/video/rig/` 与 `scenes/`，成片 `out/v2-realui-zh.mp4`）。
注意：V3·2 面板里设计师误写「Apple 私有中继」，实现时已纠正为「中转只见密文」（产品事实）。
EOF
git add marketing/video docs/design/claude-design-handoff/video-stage mobile/composeApp/src/desktopTest 2>/dev/null; git status --short | head -8
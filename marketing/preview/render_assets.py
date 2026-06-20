#!/usr/bin/env python3
"""
Render App-Preview overlay assets for one language: 6 caption pills + a logo end-card + a
task-complete notification banner. Output is 1320x2868 (native 6.9" frame); assemble.sh scales
the final video to the App Store size.

Usage:  python3 render_assets.py <en|zh> <out_dir>
Fonts are macOS system fonts. Edit TEXT below to update copy for a new release.
"""
import sys, os
from PIL import Image, ImageDraw, ImageFont

LANG = sys.argv[1] if len(sys.argv) > 1 else "en"
OUT  = sys.argv[2] if len(sys.argv) > 2 else "/tmp/ccp-assets"
os.makedirs(OUT, exist_ok=True)

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ICON = os.path.join(REPO, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png")
LATIN_B = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
SF      = "/System/Library/Fonts/SFNS.ttf"
HIRA    = "/System/Library/Fonts/Hiragino Sans GB.ttc"   # iOS-style Chinese sans

# ---- per-language copy (6 captions = the 6 scene subtitles, \n = line break) ----
TEXT = {
    "en": {
        "captions": [
            "Your AI coding agent,\nnow in your pocket.",
            "See what's running\non your computer.",
            "Stream progress\nin real time.",
            "Approve sensitive actions\nfrom anywhere.",
            "Send prompts, screenshots,\nand voice.",
            "Your computer keeps working.\nYou stay in control.",
        ],
        "tagline": "Your AI coding agent, in your pocket.",
        "banner": ("CC POCKET", "now", "Task complete", "Clean the build cache · done in 4s"),
        "cap_size": 72, "cap_lh": 92,
    },
    "zh": {
        "captions": [
            "你的 AI 编程助手\n现在就在口袋里",
            "电脑上正在运行什么\n随时一手掌握",
            "每一步进展\n实时尽收眼底",
            "敏感操作\n随时随地一键审批",
            "文字 · 截图 · 语音\n想怎么发就怎么发",
            "电脑持续工作\n掌控始终在你手中",
        ],
        "tagline": "你的 AI 编程助手，就在口袋里",
        "banner": ("CC POCKET", "现在", "任务已完成", "清理构建缓存 · 用时 4 秒"),
        "cap_size": 76, "cap_lh": 100,
    },
}[LANG]

W, H = 1320, 2868
def cjk(size):
    for idx in (1, 0):
        try: return ImageFont.truetype(HIRA, size, index=idx)
        except Exception: continue
    return ImageFont.truetype(HIRA, size)
# captions/banner use the CJK sans for zh (also renders Latin/punct cleanly); SF for en
def body(size): return cjk(size) if LANG == "zh" else ImageFont.truetype(SF, size)
def bold(size): return ImageFont.truetype(LATIN_B, size)

def rounded(img, rad):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, *img.size], rad, fill=255)
    out = img.convert("RGBA"); out.putalpha(m); return out

icon = Image.open(ICON).convert("RGBA")

# ===== captions =====
CY = 620; F = body(TEXT["cap_size"]); LH = TEXT["cap_lh"]
for i, cap in enumerate(TEXT["captions"], 1):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    lines = cap.split("\n"); widths = [d.textlength(s, font=F) for s in lines]
    bw, bh, padx, pady = max(widths), LH * len(lines), 56, 42
    box_w, box_h = bw + 2 * padx, bh + 2 * pady
    x0, y0 = (W - box_w) // 2, CY - box_h // 2
    d.rounded_rectangle([x0, y0, x0 + box_w, y0 + box_h], radius=46, fill=(10, 11, 13, 205))
    y = y0 + pady
    for s, w in zip(lines, widths):
        x = (W - w) // 2
        d.text((x + 2, y + 2), s, font=F, fill=(0, 0, 0, 150))
        d.text((x, y), s, font=F, fill=(245, 245, 247, 255))
        y += LH
    img.save(f"{OUT}/cap{i}.png")

# ===== logo end-card =====
card = Image.new("RGB", (W, H), (11, 12, 13)); d = ImageDraw.Draw(card)
big = rounded(icon.resize((340, 340)), 76); card.paste(big, ((W - 340) // 2, 1000), big)
f1 = bold(132); tw = d.textlength("CC Pocket", font=f1)
d.text(((W - tw) // 2, 1430), "CC Pocket", font=f1, fill=(245, 245, 247))
f2 = body(54); tw2 = d.textlength(TEXT["tagline"], font=f2)
d.text(((W - tw2) // 2, 1610), TEXT["tagline"], font=f2, fill=(150, 150, 154))
card.save(f"{OUT}/logo.png")

# ===== notification banner =====
app, now, title, sub = TEXT["banner"]
BW, BH = 1240, 250
ban = Image.new("RGBA", (BW, BH), (0, 0, 0, 0))
ban.alpha_composite(rounded(Image.new("RGBA", (BW, BH), (28, 28, 30, 242)), 56))
d = ImageDraw.Draw(ban)
ban.alpha_composite(rounded(icon.resize((150, 150)), 34), (44, 50))
d.text((222, 56), app, font=body(38), fill=(176, 176, 180, 255))
d.text((BW - 150, 56), now, font=body(36), fill=(150, 150, 154, 255))
d.text((222, 110), title, font=(bold(52) if LANG == "en" else body(56)), fill=(255, 255, 255, 255))
d.text((222, 178), sub, font=body(42), fill=(206, 206, 210, 255))
ban.save(f"{OUT}/banner.png")

print(f"[{LANG}] assets -> {OUT}: cap1..6, logo, banner")

# Pairlet icon

2026-10-03: the owner dropped the "Pocket" label. Every platform icon is back to the text-free chevron + cursor mark that shipped before the 2026-09-10 transition (restored from git, `bafa8a47^`). `pairlet-icon-1024.png` is that master; `python3 scripts/generate-brand-icons.py` (macOS) resizes it into the iOS, HarmonyOS, desktop and site targets and keeps the existing small ICNS/ICO representations. Android launcher files are the restored originals and are not regenerated. The iOS launch image keeps its artwork; only its text changed ("Pairlet" and the English tagline). A new logo is being explored separately (Claude Design board "Pairlet Logo").

Store screenshots, preview videos and site share images were not regenerated with this change.

## History: transition icon candidates (2026-09-10)

The supplied reference is `pairlet-pocket-preview.png`, SHA-256 `c51c3f3d081f7e99f7edbed111bdbf225b1db72456b8838a9c4fd0176188ff0c`. Its Pocket label was too small at 40–60 CSS pixels.

The selected local candidate is `pairlet-pocket-readable-v2.png`, edited with the built-in image generation tool. It enlarges the label while keeping the terminal/phone mark and warm-orange/charcoal direction. The exact prompt is in `readable-v2-prompt.txt`. Both remain candidates; this choice is a local readability assessment, not user or device acceptance.

Run `python3 scripts/generate-brand-icons.py` on macOS to reproduce size/container conversions. The macOS ICNS and Windows ICO keep original small no-text representations. `symbol.png` is the baseline desktop icon; favicon now uses that same no-text mark, replacing the previously different white-on-clay favicon. Tray drawing remains unchanged. Android retains the existing adaptive safe-zone inset.

`preview.html` shows light/dark wordmarks, 40–128 px main icons, 16–32 px miniatures and a 48/60/76 px label comparison. Device masking, display density and search behavior require separate acceptance. Store screenshots and both preview videos have been regenerated from current Compose scenes and the selected candidate; the scripted demo data is not a real customer/device session.

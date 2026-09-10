# Pairlet transition icon candidate

The supplied reference is `pairlet-pocket-preview.png`, SHA-256 `c51c3f3d081f7e99f7edbed111bdbf225b1db72456b8838a9c4fd0176188ff0c`. Its Pocket label was too small at 40–60 CSS pixels.

The selected local candidate is `pairlet-pocket-readable-v2.png`, edited with the built-in image generation tool. It enlarges the label while keeping the terminal/phone mark and warm-orange/charcoal direction. The exact prompt is in `readable-v2-prompt.txt`. Both remain candidates; this choice is a local readability assessment, not user or device acceptance.

Run `python3 scripts/generate-brand-icons.py` on macOS to reproduce size/container conversions. The macOS ICNS and Windows ICO keep original small no-text representations. `symbol.png` is the baseline desktop icon; favicon now uses that same no-text mark, replacing the previously different white-on-clay favicon. Tray drawing remains unchanged. Android retains the existing adaptive safe-zone inset.

`preview.html` shows light/dark wordmarks, 40–128 px main icons, 16–32 px miniatures and a 48/60/76 px label comparison. Device masking, display density and search behavior require separate acceptance. Store screenshots and both preview videos have been regenerated from current Compose scenes and the selected candidate; the scripted demo data is not a real customer/device session.

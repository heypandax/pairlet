# Pairlet icon

2026-10-03: the owner dropped the "Pocket" label. Every platform icon is back to the text-free chevron + cursor mark that shipped before the 2026-09-10 transition (restored from git, `bafa8a47^`). `pairlet-icon-1024.png` is that master; `python3 scripts/generate-brand-icons.py` (macOS) resizes it into the iOS, HarmonyOS, desktop and site targets and keeps the existing small ICNS/ICO representations. Android launcher files are the restored originals and are not regenerated. The iOS launch image keeps its artwork; only its text changed ("Pairlet" and the English tagline). A new logo is being explored separately (Claude Design board "Pairlet Logo").

Store screenshots, preview videos and site share images were not regenerated with this change.

## History: transition icon candidates (2026-09-10)

The Pocket-label candidates (the supplied reference `pairlet-pocket-preview.png`, SHA-256 `c51c3f3d081f7e99f7edbed111bdbf225b1db72456b8838a9c4fd0176188ff0c`, the enlarged-label `pairlet-pocket-readable-v2.png` with its prompt, and the `preview.html` size page) were removed from the repository after the label was dropped; they remain in git history.

Run `python3 scripts/generate-brand-icons.py` on macOS to reproduce size/container conversions. The macOS ICNS and Windows ICO keep original small no-text representations. `symbol.png` is the baseline desktop icon; favicon now uses that same no-text mark, replacing the previously different white-on-clay favicon. Tray drawing remains unchanged. Android retains the existing adaptive safe-zone inset.

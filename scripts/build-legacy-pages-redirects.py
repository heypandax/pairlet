#!/usr/bin/env python3
"""Prepare known-path redirects for the separate Pages repository; never deploy them.

Apply the generated cc-pocket/ subtree only after pairlet.org passes live acceptance.
GitHub Pages cannot issue HTTP 308; these HTML redirects retain query and fragment in JS.
"""
import argparse
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "build/pairlet-pages-redirects")
    args = parser.parse_args()
    output = args.out.resolve() / "cc-pocket"
    mappings = []
    for page in sorted((ROOT / "site").rglob("*.html")):
        relative = page.relative_to(ROOT / "site")
        path = relative.as_posix()
        if path.endswith("index.html"):
            path = path[:-len("index.html")]
        if path == "zh/":
            path = ""
        target = "https://pairlet.org/" + path
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(
            '<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<meta name="robots" content="noindex"><title>Pairlet</title>'
            f'<link rel="canonical" href="{html.escape(target, quote=True)}">'
            f'<script>location.replace({json.dumps(target)} + location.search + location.hash);</script>'
            '<p>Pairlet 网站已迁移。'
            f'<a href="{html.escape(target, quote=True)}">继续访问 · Continue</a></p></html>\n'
        )
        mappings.append({"oldPath": "/cc-pocket/" + relative.as_posix(), "newUrl": target})
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "redirect-map.json").write_text(json.dumps(mappings, ensure_ascii=False, indent=2) + "\n")
    print(f"Prepared {len(mappings)} known-path redirects under {output}; nothing deployed")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Static SEO/GEO checks for the GitHub Pages site (stdlib only)."""

from __future__ import annotations

import json
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
from xml.etree import ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"
BASE = "https://heypandax.github.io/cc-pocket/"
SKIP_SCHEMES = {"data", "javascript", "mailto", "tel"}


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.lang = ""
        self.in_title = False
        self.in_h1 = 0
        self.in_jsonld = False
        self.title_parts: list[str] = []
        self.h1_parts: list[str] = []
        self.h1_count = 0
        self.description = ""
        self.canonicals: list[str] = []
        self.alternates: dict[str, str] = {}
        self.jsonld_parts: list[str] = []
        self.references: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key: value or "" for key, value in attrs}
        if tag == "html":
            self.lang = values.get("lang", "")
        elif tag == "title":
            self.in_title = True
        elif tag == "h1":
            self.in_h1 += 1
            self.h1_count += 1
        elif tag == "meta" and values.get("name", "").lower() == "description":
            self.description = values.get("content", "").strip()
        elif tag == "link":
            rels = set(values.get("rel", "").lower().split())
            href = values.get("href", "")
            if "canonical" in rels:
                self.canonicals.append(href)
            if "alternate" in rels and values.get("hreflang"):
                self.alternates[values["hreflang"]] = href
        elif tag == "script" and values.get("type", "").lower() == "application/ld+json":
            self.in_jsonld = True

        for attribute in ("href", "src"):
            if values.get(attribute):
                self.references.append((attribute, values[attribute]))

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self.in_title = False
        elif tag == "h1" and self.in_h1:
            self.in_h1 -= 1
        elif tag == "script" and self.in_jsonld:
            self.in_jsonld = False

    def handle_data(self, data: str) -> None:
        if self.in_title:
            self.title_parts.append(data)
        if self.in_h1:
            self.h1_parts.append(data)
        if self.in_jsonld:
            self.jsonld_parts.append(data)

    @property
    def title(self) -> str:
        return " ".join("".join(self.title_parts).split())

    @property
    def h1(self) -> str:
        return " ".join("".join(self.h1_parts).split())

    @property
    def jsonld(self) -> str:
        return "".join(self.jsonld_parts).strip()


def canonical_for(path: Path) -> str:
    relative = path.relative_to(SITE).as_posix()
    if relative == "index.html":
        return BASE
    if relative.endswith("/index.html"):
        return BASE + relative[: -len("index.html")]
    return BASE + relative


def local_target(page: Path, raw: str) -> Path | None:
    parsed = urlsplit(raw)
    if parsed.scheme in SKIP_SCHEMES or (parsed.scheme and parsed.scheme not in {"http", "https"}):
        return None
    if parsed.netloc:
        if parsed.netloc != "heypandax.github.io" or not parsed.path.startswith("/cc-pocket/"):
            return None
        relative = unquote(parsed.path[len("/cc-pocket/") :])
        target = SITE / relative
    else:
        if not parsed.path:
            return page
        target = page.parent / unquote(parsed.path)
    if raw.endswith("/") or target.is_dir():
        target /= "index.html"
    return target.resolve()


def main() -> int:
    errors: list[str] = []
    pages: dict[Path, PageParser] = {}
    canonical_to_page: dict[str, Path] = {}
    titles: dict[str, Path] = {}
    descriptions: dict[str, Path] = {}

    for page in sorted(SITE.rglob("*.html")):
        parser = PageParser()
        parser.feed(page.read_text(encoding="utf-8"))
        pages[page] = parser
        label = page.relative_to(ROOT).as_posix()

        if not parser.lang:
            errors.append(f"{label}: missing html lang")
        if not parser.title:
            errors.append(f"{label}: missing title")
        elif parser.title in titles:
            errors.append(f"{label}: duplicate title also used by {titles[parser.title].relative_to(ROOT)}")
        else:
            titles[parser.title] = page
        if not parser.description:
            errors.append(f"{label}: missing meta description")
        elif parser.description in descriptions:
            errors.append(f"{label}: duplicate description also used by {descriptions[parser.description].relative_to(ROOT)}")
        else:
            descriptions[parser.description] = page
        if parser.h1_count != 1 or not parser.h1:
            errors.append(f"{label}: expected exactly one non-empty h1, found {parser.h1_count}")
        if len(parser.canonicals) != 1:
            errors.append(f"{label}: expected one canonical, found {len(parser.canonicals)}")
        else:
            canonical = parser.canonicals[0]
            expected = canonical_for(page)
            if canonical != expected:
                errors.append(f"{label}: canonical {canonical!r} should be {expected!r}")
            if canonical in canonical_to_page:
                errors.append(f"{label}: duplicate canonical also used by {canonical_to_page[canonical].relative_to(ROOT)}")
            canonical_to_page[canonical] = page
        if not parser.jsonld:
            errors.append(f"{label}: missing JSON-LD")
        else:
            try:
                json.loads(parser.jsonld)
            except json.JSONDecodeError as exc:
                errors.append(f"{label}: invalid JSON-LD: {exc}")

        for attribute, reference in parser.references:
            target = local_target(page, reference)
            if target is not None and not target.exists():
                errors.append(f"{label}: broken local {attribute}={reference!r}")

    sitemap = ET.parse(SITE / "sitemap.xml").getroot()
    ns = {"s": "http://www.sitemaps.org/schemas/sitemap/0.9"}
    sitemap_urls = {node.text.strip() for node in sitemap.findall("s:url/s:loc", ns) if node.text}
    canonical_urls = set(canonical_to_page)
    for missing in sorted(canonical_urls - sitemap_urls):
        errors.append(f"sitemap.xml: missing canonical {missing}")
    for extra in sorted(sitemap_urls - canonical_urls):
        errors.append(f"sitemap.xml: URL has no local canonical page {extra}")

    for page, parser in pages.items():
        source = parser.canonicals[0] if len(parser.canonicals) == 1 else ""
        for language, target in parser.alternates.items():
            if language == "x-default" or target not in canonical_to_page:
                continue
            target_parser = pages[canonical_to_page[target]]
            if source not in target_parser.alternates.values():
                errors.append(
                    f"{page.relative_to(ROOT)}: hreflang {language} target does not link back to {source}"
                )

    if errors:
        print(f"SEO check failed with {len(errors)} error(s):", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"SEO check passed: {len(pages)} HTML pages, {len(sitemap_urls)} sitemap URLs, all local links resolved.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

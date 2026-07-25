#!/usr/bin/env python3
"""Build the public, static CC Pocket manual from one bilingual content source."""

from __future__ import annotations

import html
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANUAL = ROOT / "site" / "manual"
CONTENT = MANUAL / "manual-content.json"
PUBLIC_BASE = "https://heypandax.github.io/cc-pocket/manual"
VERIFIED = "2026-07-24"


def text(value: str) -> str:
    return html.escape(value, quote=True)


def localized(value: dict[str, str], locale: str) -> str:
    return value[locale]


def page_head(*, title: str, description: str, canonical: str, locale: str, depth: int, page_type: str) -> str:
    prefix = "../" * depth
    lang = "zh-CN" if locale == "zh" else "en"
    og_locale = "zh_CN" if locale == "zh" else "en_US"
    x_default = (
        f"{PUBLIC_BASE}/"
        if canonical in {f"{PUBLIC_BASE}/en/", f"{PUBLIC_BASE}/zh/"}
        else canonical.replace(f"/{locale}/", "/en/")
    )
    return f"""<!DOCTYPE html>
<html lang="{lang}" data-theme="dark">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>{text(title)}</title>
<meta name="description" content="{text(description)}" />
<link rel="canonical" href="{canonical}" />
<link rel="alternate" hreflang="en" href="{canonical.replace(f'/{locale}/', '/en/')}" />
<link rel="alternate" hreflang="zh-CN" href="{canonical.replace(f'/{locale}/', '/zh/')}" />
<link rel="alternate" hreflang="x-default" href="{x_default}" />
<meta name="robots" content="index, follow, max-image-preview:large, max-snippet:-1" />
<meta property="og:type" content="{page_type}" />
<meta property="og:site_name" content="CC Pocket" />
<meta property="og:title" content="{text(title)}" />
<meta property="og:description" content="{text(description)}" />
<meta property="og:url" content="{canonical}" />
<meta property="og:image" content="https://heypandax.github.io/cc-pocket/manual/og-manual.png" />
<meta property="og:image:width" content="1200" />
<meta property="og:image:height" content="630" />
<meta property="og:locale" content="{og_locale}" />
<meta name="twitter:card" content="summary_large_image" />
<meta name="twitter:title" content="{text(title)}" />
<meta name="twitter:description" content="{text(description)}" />
<meta name="twitter:image" content="https://heypandax.github.io/cc-pocket/manual/og-manual.png" />
<link rel="icon" href="{prefix}favicon.svg" type="image/svg+xml" />
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet" />
<link rel="stylesheet" href="{prefix}styles.css" />
<link rel="stylesheet" href="{"../" * (depth - 1)}manual.css" />
</head>"""


def nav(locale: str, depth: int) -> str:
    site_root = "../" * depth
    manual_root = "../" * (depth - 1)
    alt = "zh" if locale == "en" else "en"
    alt_label = "中文" if locale == "en" else "English"
    return f"""
<body data-locale="{locale}">
<nav class="manual-nav">
  <div class="manual-wrap nav-inner">
    <a class="brand" href="{site_root}" aria-label="CC Pocket home"><span class="brand-mark" aria-hidden="true"></span><span class="wordmark mono">CC Pocket</span></a>
    <span class="nav-slash">/</span>
    <a class="manual-word" href="{manual_root}{locale}/">{"Manual" if locale == "en" else "用户手册"}</a>
    <div class="nav-actions">
      <a class="locale-link" href="{manual_root}{alt}/" hreflang="{"zh-CN" if alt == "zh" else "en"}">{alt_label}</a>
      <button class="theme-toggle" type="button" aria-label="{"Toggle theme" if locale == "en" else "切换主题"}">◐</button>
      <a class="github-link" href="https://github.com/heypandax/cc-pocket" rel="noopener">GitHub</a>
    </div>
  </div>
</nav>"""


def home_page(data: dict, locale: str) -> str:
    copy = data["home"][locale]
    canonical = f"{PUBLIC_BASE}/{locale}/"
    article_by_slug = {a["slug"]: a for a in data["articles"]}
    categories = []
    for category in data["categories"]:
        links = []
        for slug in category["articles"]:
            article = article_by_slug[slug]
            links.append(
                f'<a href="{slug}/"><span>{text(localized(article["title"], locale))}</span><span aria-hidden="true">→</span></a>'
            )
        categories.append(
            f"""<section class="category-card">
  <h2>{text(localized(category["title"], locale))}</h2>
  <p>{text(localized(category["description"], locale))}</p>
  <div class="category-links">{"".join(links)}</div>
</section>"""
        )
    recent = "".join(
        f"""<a class="updated-row" href="{a['slug']}/">
  <span><strong>{text(localized(a['title'], locale))}</strong><small>{text(localized(a['categoryLabel'], locale))}</small></span>
  <span class="verified">{"Verified" if locale == "en" else "已核验"} {VERIFIED}</span>
</a>"""
        for a in data["articles"][:5]
    )
    index_json = json.dumps(
        [
            {
                "title": localized(a["title"], locale),
                "summary": localized(a["summary"], locale),
                "category": localized(a["categoryLabel"], locale),
                "aliases": a["aliases"][locale],
                "url": f"{a['slug']}/",
            }
            for a in data["articles"]
        ],
        ensure_ascii=False,
    ).replace("</", "<\\/")
    return (
        page_head(
            title=copy["metaTitle"],
            description=copy["metaDescription"],
            canonical=canonical,
            locale=locale,
            depth=2,
            page_type="website",
        )
        + f"""
<script type="application/ld+json">
{{
  "@context":"https://schema.org",
  "@type":"CollectionPage",
  "name":{json.dumps(copy["title"], ensure_ascii=False)},
  "url":"{canonical}",
  "description":{json.dumps(copy["metaDescription"], ensure_ascii=False)},
  "inLanguage":"{"zh-CN" if locale == "zh" else "en"}",
  "isPartOf":{{"@id":"https://heypandax.github.io/cc-pocket/#website"}}
}}
</script>
"""
        + nav(locale, 2)
        + f"""
<main>
  <header class="manual-hero manual-wrap">
    <div class="breadcrumbs"><a href="../../">CC Pocket</a><span>/</span><span>{"Manual" if locale == "en" else "用户手册"}</span></div>
    <p class="eyebrow">{"USER MANUAL" if locale == "en" else "用户手册"}</p>
    <h1>{text(copy["title"])}</h1>
    <p class="hero-lede">{text(copy["lede"])}</p>
    <div class="manual-search">
      <label class="sr-only" for="manual-search-input">{text(copy["searchLabel"])}</label>
      <span aria-hidden="true">⌕</span>
      <input id="manual-search-input" type="search" placeholder="{text(copy["searchPlaceholder"])}" autocomplete="off" />
      <kbd>/</kbd>
    </div>
    <div id="manual-search-results" class="search-results" hidden aria-live="polite"></div>
    <div class="quick-links">
      <a href="schedule-a-prompt/">{text(copy["quickSchedule"])}</a>
      <a href="take-over-a-terminal-session/">{text(copy["quickTakeover"])}</a>
      <a href="fix-offline-computer/">{text(copy["quickOffline"])}</a>
    </div>
  </header>

  <section class="manual-section manual-wrap">
    <div class="section-title"><div><p class="eyebrow">{"START HERE" if locale == "en" else "从这里开始"}</p><h2>{text(copy["startTitle"])}</h2></div><span>{text(copy["startMeta"])}</span></div>
    <div class="start-grid">
      <a href="install-and-pair/"><b>1</b><span><strong>{text(copy["startInstall"])}</strong><small>{text(copy["startInstallSub"])}</small></span></a>
      <a href="install-and-pair/#pair"><b>2</b><span><strong>{text(copy["startPair"])}</strong><small>{text(copy["startPairSub"])}</small></span></a>
      <a href="choose-agent-and-model/"><b>3</b><span><strong>{text(copy["startSession"])}</strong><small>{text(copy["startSessionSub"])}</small></span></a>
    </div>
  </section>

  <section class="manual-section manual-wrap">
    <div class="section-title"><div><p class="eyebrow">{"BROWSE BY TASK" if locale == "en" else "按任务浏览"}</p><h2>{text(copy["browseTitle"])}</h2></div><span>{text(copy["browseMeta"])}</span></div>
    <div class="category-grid">{"".join(categories)}</div>
  </section>

  <section class="ai-callout manual-wrap">
    <div><p class="eyebrow">{"USING AN AI ASSISTANT?" if locale == "en" else "正在使用 AI 助手？"}</p><h2>{text(copy["aiTitle"])}</h2><p>{text(copy["aiBody"])}</p></div>
    <button class="manual-button secondary" type="button" data-copy-ai data-copy-url="{canonical}">{text(copy["aiButton"])}</button>
  </section>

  <section class="manual-section manual-wrap">
    <div class="section-title"><div><p class="eyebrow">{"RECENTLY VERIFIED" if locale == "en" else "最近核验"}</p><h2>{text(copy["recentTitle"])}</h2></div></div>
    <div class="updated-list">{recent}</div>
  </section>
</main>
<footer class="manual-footer"><div class="manual-wrap"><span>© 2026 CC Pocket · MIT</span><span><a href="../../features.html">{"Features" if locale == "en" else "功能"}</a><a href="https://github.com/heypandax/cc-pocket">GitHub</a></span></div></footer>
<script id="manual-search-index" type="application/json">{index_json}</script>
<script src="../manual.js"></script>
</body>
</html>"""
    )


def render_section(section: dict, locale: str) -> str:
    body = []
    for paragraph in section.get("paragraphs", {}).get(locale, []):
        body.append(f"<p>{paragraph}</p>")
    if "steps" in section:
        steps = []
        for i, step in enumerate(section["steps"], 1):
            code = step.get("code")
            code_block = f'<pre><code>{text(code)}</code><button type="button" data-copy-code="{text(code)}">{"Copy" if locale == "en" else "复制"}</button></pre>' if code else ""
            steps.append(
                f"""<li><span class="step-number">{i}</span><div><h3>{text(localized(step["title"], locale))}</h3><p>{localized(step["body"], locale)}</p>{code_block}</div></li>"""
            )
        body.append(f'<ol class="article-steps">{"".join(steps)}</ol>')
    bullets = section.get("bullets", {}).get(locale)
    if bullets:
        body.append("<ul>" + "".join(f"<li>{item}</li>" for item in bullets) + "</ul>")
    troubleshooting = section.get("troubleshooting")
    if troubleshooting:
        body.append(
            '<div class="troubleshooting">'
            + "".join(
                f"<details><summary>{text(localized(item['title'], locale))}</summary><p>{localized(item['body'], locale)}</p></details>"
                for item in troubleshooting
            )
            + "</div>"
        )
    note = section.get("note", {}).get(locale)
    if note:
        body.append(f'<aside class="article-note">{note}</aside>')
    return f'<section id="{text(section["id"])}"><h2>{text(localized(section["heading"], locale))}</h2>{"".join(body)}</section>'


def article_page(data: dict, article: dict, locale: str) -> str:
    title = localized(article["title"], locale)
    summary = localized(article["summary"], locale)
    platform = localized(
        article.get("platform", {"en": "All platforms", "zh": "全平台"}),
        locale,
    )
    canonical = f"{PUBLIC_BASE}/{locale}/{article['slug']}/"
    by_slug = {a["slug"]: a for a in data["articles"]}
    related = "".join(
        f'<a href="../{slug}/"><strong>{text(localized(by_slug[slug]["title"], locale))}</strong><span>{text(localized(by_slug[slug]["summary"], locale))}</span></a>'
        for slug in article["related"]
    )
    sections = "".join(render_section(section, locale) for section in article["sections"])
    prompt = (
        "Open this public CC Pocket manual page and answer my question using only the verified steps on the page."
        if locale == "en"
        else "打开这篇公开的 CC Pocket 用户手册，只依据页面中已核验的步骤回答我的问题。"
    )
    return (
        page_head(
            title=f"{title} — CC Pocket {'Manual' if locale == 'en' else '用户手册'}",
            description=summary,
            canonical=canonical,
            locale=locale,
            depth=3,
            page_type="article",
        )
        + f"""
<script type="application/ld+json">
{{
  "@context":"https://schema.org",
  "@type":"TechArticle",
  "headline":{json.dumps(title, ensure_ascii=False)},
  "description":{json.dumps(summary, ensure_ascii=False)},
  "url":"{canonical}",
  "dateModified":"{VERIFIED}",
  "inLanguage":"{"zh-CN" if locale == "zh" else "en"}",
  "isPartOf":{{"@type":"CollectionPage","url":"{PUBLIC_BASE}/{locale}/"}}
}}
</script>
"""
        + nav(locale, 3)
        + f"""
<main class="article-shell">
  <aside class="article-sidebar">
    <a href="../">← {"All guides" if locale == "en" else "全部指南"}</a>
    <span>{text(localized(article["categoryLabel"], locale))}</span>
  </aside>
  <article class="manual-article">
    <div class="breadcrumbs"><a href="../">{"Manual" if locale == "en" else "用户手册"}</a><span>/</span><span>{text(localized(article["categoryLabel"], locale))}</span></div>
    <div class="platform-tags"><span>{text(platform)}</span></div>
    <h1>{text(title)}</h1>
    <div class="short-answer"><span>{"SHORT ANSWER" if locale == "en" else "简短答案"}</span><p>{localized(article["shortAnswer"], locale)}</p></div>
    <div class="article-meta"><span>{"Last verified" if locale == "en" else "最近核验"} {VERIFIED}</span><span>·</span><span>{"Public URL · no sign-in" if locale == "en" else "公开链接 · 无需登录"}</span></div>
    <div class="article-actions">
      <button class="manual-button secondary" type="button" data-copy-url="{canonical}">{"Copy link" if locale == "en" else "复制链接"}</button>
      <button class="manual-button primary" type="button" data-copy-ai data-copy-url="{canonical}" data-copy-prompt="{text(prompt)}">{"Copy for AI" if locale == "en" else "复制给 AI"}</button>
    </div>
    {sections}
    <section class="related-articles"><h2>{"Related guides" if locale == "en" else "相关指南"}</h2><div>{related}</div></section>
    <section class="article-feedback"><strong>{"Still stuck?" if locale == "en" else "问题还没解决？"}</strong><span>{"Open an issue with the steps you tried." if locale == "en" else "把你尝试过的步骤写进 GitHub issue。"}</span><a href="https://github.com/heypandax/cc-pocket/issues/new">{"Open an issue" if locale == "en" else "新建 issue"} →</a></section>
  </article>
  <aside class="article-toc"><strong>{"On this page" if locale == "en" else "本页内容"}</strong>{"".join(f'<a href="#{text(s["id"])}">{text(localized(s["heading"], locale))}</a>' for s in article["sections"])}</aside>
</main>
<footer class="manual-footer"><div class="manual-wrap"><span>{canonical}</span><span><a href="../">{"Manual home" if locale == "en" else "手册首页"}</a><a href="https://github.com/heypandax/cc-pocket">GitHub</a></span></div></footer>
<script src="../../manual.js"></script>
</body>
</html>"""
    )


def root_page() -> str:
    return """<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>CC Pocket User Manual</title>
<meta name="description" content="Public task-based help for CC Pocket users and AI assistants." />
<link rel="canonical" href="https://heypandax.github.io/cc-pocket/manual/" />
<link rel="alternate" hreflang="en" href="https://heypandax.github.io/cc-pocket/manual/en/" />
<link rel="alternate" hreflang="zh-CN" href="https://heypandax.github.io/cc-pocket/manual/zh/" />
<link rel="alternate" hreflang="x-default" href="https://heypandax.github.io/cc-pocket/manual/" />
<meta name="robots" content="index, follow, max-image-preview:large, max-snippet:-1" />
<meta property="og:type" content="website" />
<meta property="og:site_name" content="CC Pocket" />
<meta property="og:title" content="CC Pocket User Manual" />
<meta property="og:description" content="Public, task-based help for CC Pocket users and AI assistants." />
<meta property="og:url" content="https://heypandax.github.io/cc-pocket/manual/" />
<meta property="og:image" content="https://heypandax.github.io/cc-pocket/manual/og-manual.png" />
<meta property="og:image:width" content="1200" />
<meta property="og:image:height" content="630" />
<meta name="twitter:card" content="summary_large_image" />
<meta name="twitter:title" content="CC Pocket User Manual" />
<meta name="twitter:description" content="Public, task-based help for CC Pocket users and AI assistants." />
<meta name="twitter:image" content="https://heypandax.github.io/cc-pocket/manual/og-manual.png" />
<script type="application/ld+json">{"@context":"https://schema.org","@type":"WebPage","name":"CC Pocket User Manual","url":"https://heypandax.github.io/cc-pocket/manual/","inLanguage":["en","zh-CN"],"isPartOf":{"@type":"WebSite","name":"CC Pocket","url":"https://heypandax.github.io/cc-pocket/"}}</script>
<link rel="icon" href="../favicon.svg" type="image/svg+xml" />
<link rel="stylesheet" href="../styles.css" />
<link rel="stylesheet" href="manual.css" />
<script>
  (function () {
    var locale = (navigator.language || "").toLowerCase().startsWith("zh") ? "zh" : "en";
    location.replace(locale + "/" + location.search + location.hash);
  }());
</script>
</head>
<body>
<main class="language-gate manual-wrap">
  <p class="eyebrow">CC POCKET</p>
  <h1>User manual · 用户手册</h1>
  <p>Public, task-based help that can be opened by people and AI assistants.</p>
  <div><a class="manual-button primary" href="en/">English</a><a class="manual-button secondary" href="zh/">中文</a></div>
</main>
</body>
</html>"""


def update_sitemap(data: dict) -> None:
    entries = [
        f"""  <url>
    <loc>{PUBLIC_BASE}/</loc>
    <lastmod>{VERIFIED}</lastmod>
    <xhtml:link rel="alternate" hreflang="en" href="{PUBLIC_BASE}/en/" />
    <xhtml:link rel="alternate" hreflang="zh-CN" href="{PUBLIC_BASE}/zh/" />
    <xhtml:link rel="alternate" hreflang="x-default" href="{PUBLIC_BASE}/" />
  </url>"""
    ]
    for locale in ("en", "zh"):
        lang = "zh-CN" if locale == "zh" else "en"
        other = "en" if locale == "zh" else "zh"
        other_lang = "en" if other == "en" else "zh-CN"
        urls = [""] + [f"{article['slug']}/" for article in data["articles"]]
        for suffix in urls:
            loc = f"{PUBLIC_BASE}/{locale}/{suffix}"
            other_loc = f"{PUBLIC_BASE}/{other}/{suffix}"
            default_loc = f"{PUBLIC_BASE}/en/{suffix}"
            entries.append(
                f"""  <url>
    <loc>{loc}</loc>
    <lastmod>{VERIFIED}</lastmod>
    <xhtml:link rel="alternate" hreflang="{lang}" href="{loc}" />
    <xhtml:link rel="alternate" hreflang="{other_lang}" href="{other_loc}" />
    <xhtml:link rel="alternate" hreflang="x-default" href="{default_loc}" />
  </url>"""
            )
    begin = "  <!-- MANUAL_START -->"
    end = "  <!-- MANUAL_END -->"
    block = begin + "\n" + "\n".join(entries) + "\n" + end
    sitemap_path = ROOT / "site" / "sitemap.xml"
    sitemap = sitemap_path.read_text()
    if begin in sitemap and end in sitemap:
        before, rest = sitemap.split(begin, 1)
        _, after = rest.split(end, 1)
        sitemap = before + block + after
    else:
        sitemap = sitemap.replace("</urlset>", block + "\n</urlset>")
    sitemap_path.write_text(sitemap)


def main() -> None:
    data = json.loads(CONTENT.read_text())
    if data.get("verifiedAt") != VERIFIED:
        raise ValueError(
            f"manual-content.json verifiedAt ({data.get('verifiedAt')!r}) must match builder VERIFIED ({VERIFIED})"
        )
    MANUAL.mkdir(parents=True, exist_ok=True)
    (MANUAL / "index.html").write_text(root_page())
    for locale in ("en", "zh"):
        locale_dir = MANUAL / locale
        locale_dir.mkdir(parents=True, exist_ok=True)
        (locale_dir / "index.html").write_text(home_page(data, locale))
        for article in data["articles"]:
            article_dir = locale_dir / article["slug"]
            article_dir.mkdir(parents=True, exist_ok=True)
            (article_dir / "index.html").write_text(article_page(data, article, locale))
    update_sitemap(data)
    subprocess.run(
        [
            "python3",
            str(ROOT / "scripts" / "support-kb.py"),
            "build-index",
            "--manual",
            str(CONTENT),
        ],
        check=True,
    )
    print(f"built {3 + len(data['articles']) * 2} manual pages and AI indexes")


if __name__ == "__main__":
    main()

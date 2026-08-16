#!/usr/bin/env python3
"""Browser smoke checks for the public site — desktop and 390px, English and Chinese.

Not part of the static gates (those are stdlib-only and run in CI); this one needs a real browser,
so it stays a manual/verification step next to the media pipeline.

    cd site && python3 -m http.server 8791 &
    python3.11 marketing/site/smoke.py http://127.0.0.1:8791/
"""

from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8791/"
results: list[tuple[bool, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((bool(ok), f"{label}{(' — ' + detail) if detail else ''}"))


def overflow(page) -> list[str]:
    return page.evaluate(
        """() => [...document.querySelectorAll('body *')]
             .filter(e => e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflowX === 'visible')
             .slice(0, 6)
             .map(e => e.tagName + '.' + (e.className || '').toString().split(' ')[0])"""
    )


def run(pw, reduced_motion: str = "no-preference") -> None:
    browser = pw.chromium.launch()

    # ── desktop, English ──
    ctx = browser.new_context(viewport={"width": 1440, "height": 900}, reduced_motion=reduced_motion)
    page = ctx.new_page()
    errors: list[str] = []
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(BASE, wait_until="networkidle")

    check(page.locator("h1").count() == 1, "one h1")
    check(page.locator("html").get_attribute("data-lang") == "en", "default language is English")
    check(page.locator("#loop-steps .loop-step").count() == 4, "control loop has 4 steps")
    check(page.locator(".matrix tbody tr[data-agent]:not([data-agent$='-warn'])").count() == 6,
          "matrix has 6 agent rows")
    check(page.locator(".matrix thead th").count() == 5, "matrix has agent + 4 capability columns")
    check(page.locator(".agent-chip").count() == 6, "hero shows 6 agent chips")

    # video: present, muted, playsinline, controls, and actually playing (motion allowed)
    vid = page.locator("#loop-video-en")
    check(vid.evaluate("v => v.muted"), "loop video is muted")
    check(vid.evaluate("v => v.hasAttribute('playsinline')"), "loop video is playsinline")
    check(vid.evaluate("v => v.controls"), "loop video exposes controls")
    page.locator("#loop").scroll_into_view_if_needed()
    page.wait_for_timeout(1500)
    playing = vid.evaluate("v => !v.paused && v.currentTime > 0")
    if reduced_motion == "reduce":
        check(not playing, "reduced motion: video does NOT autoplay")
        check(bool(vid.get_attribute("poster")), "reduced motion: poster present")
    else:
        check(playing, "video autoplays in view", f"currentTime={vid.evaluate('v => v.currentTime'):.2f}")

    # loop step click seeks and re-highlights
    activated = page.evaluate("""() => {
      document.querySelector('#loop-steps .loop-step:nth-child(4) .loop-btn').click();
      return document.querySelector('#loop-steps .loop-step:nth-child(4)').classList.contains('on');
    }""")
    check(activated, "clicking step 4 highlights it")
    # Python's SimpleHTTPServer does not advertise byte ranges, so Chromium reports a zero-length
    # seekable range even after buffering the whole MP4. Verify the seek only when the test server
    # supports it; production static hosting does.
    seekable = vid.evaluate("v => v.seekable.length > 0 && v.seekable.end(0) >= 8.3")
    page.wait_for_timeout(300)
    check((not seekable) or vid.evaluate("v => v.currentTime") >= 8.0,
          "clicking step 4 seeks when the server supports byte ranges")

    # OS-aware download selection + Install/Update
    check(page.locator(".os-tab.on").count() == 1, "exactly one OS tab is selected")
    page.locator(".os-tab[data-os='linux']").click()
    linux_panel = page.locator(".os-panel[data-os='linux']")
    check("on" in (linux_panel.get_attribute("class") or ""), "Linux panel switches on")
    check(linux_panel.locator(".linux-desktop").is_visible(), "Linux states there is no desktop package")
    check(linux_panel.locator("a[href*='cc-pocket-desktop-linux']").count() == 0,
          "no official Linux desktop download button")
    page.locator(".mode-tab[data-mode='update']").click()
    check(linux_panel.locator(".mode-block[data-mode='update'].on").count() == 1, "Update mode switches")

    # theme
    page.locator("#theme-btn").click()
    check(page.locator("html").get_attribute("data-theme") == "light", "theme toggles to light")
    page.locator("#theme-btn").click()
    check(page.locator("html").get_attribute("data-theme") == "dark", "theme toggles back to dark")

    # language: swaps every surface, including the media source, with no mixed content
    page.locator(".nav-right [data-setlang='zh']").click()
    page.wait_for_timeout(400)
    check(page.locator("html").get_attribute("data-lang") == "zh", "language toggles to Chinese")
    check(page.locator("#loop-video-zh").is_visible() and not page.locator("#loop-video-en").is_visible(),
          "Chinese video replaces the English one")
    check(page.locator("#loop-video-en").evaluate("v => v.paused"), "hidden English video is paused")
    mixed = page.evaluate(
        """() => [...document.querySelectorAll('.i18n-en')]
               .filter(e => e.offsetParent !== null).length"""
    )
    check(mixed == 0, "no English strings visible in Chinese mode", f"{mixed} visible")
    page.locator(".nav-right [data-setlang='en']").click()

    check(not errors, "no console errors (desktop)", "; ".join(errors[:3]))
    doc_over = page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")
    check(doc_over, "no horizontal page scroll at 1440px")
    ctx.close()

    # ── 390px, both languages ──
    for lang in ("en", "zh"):
        ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True,
                                  has_touch=True, reduced_motion=reduced_motion,
                                  user_agent="Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                                             "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148")
        page = ctx.new_page()
        merrors: list[str] = []
        page.on("console", lambda m: merrors.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: merrors.append(str(e)))
        page.add_init_script(f"try{{localStorage.setItem('ccp-lang','{lang}')}}catch(e){{}}")
        page.goto(BASE, wait_until="networkidle")

        check(page.locator("html").get_attribute("data-lang") == lang, f"390px[{lang}]: language applied")
        check(page.locator("#hamburger").is_visible(), f"390px[{lang}]: hamburger visible")
        page.locator("#hamburger").click()
        check("open" in (page.locator("#mobile-menu").get_attribute("class") or ""),
              f"390px[{lang}]: menu opens")
        check(page.locator("#hamburger").get_attribute("aria-expanded") == "true",
              f"390px[{lang}]: aria-expanded set")
        page.keyboard.press("Escape")
        check("open" not in (page.locator("#mobile-menu").get_attribute("class") or ""),
              f"390px[{lang}]: Escape closes the menu")

        # the matrix must still expose every limit without a second tap
        page.locator("#agents").scroll_into_view_if_needed()
        page.wait_for_timeout(300)
        hidden_states = page.evaluate(
            """() => [...document.querySelectorAll('.matrix td[data-state]')]
                   .filter(td => td.offsetParent === null).length"""
        )
        check(hidden_states == 0, f"390px[{lang}]: every matrix cell is visible", f"{hidden_states} hidden")
        check(page.locator(".matrix tbody tr[data-agent='deepseek'] td[data-state='limited']").is_visible(),
              f"390px[{lang}]: DeepSeek Limited state visible")

        # the download path must not require a horizontal table
        page.locator("#start").scroll_into_view_if_needed()
        page.wait_for_timeout(300)
        check(page.locator(".dl-card[data-store='ios'] .dl-btn").is_visible(),
              f"390px[{lang}]: phone gets a tappable store button (not a QR)")

        doc_over = page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")
        check(doc_over, f"390px[{lang}]: no horizontal page scroll")
        check(not merrors, f"390px[{lang}]: no console errors", "; ".join(merrors[:3]))
        ctx.close()

    browser.close()


def main() -> int:
    with sync_playwright() as pw:
        run(pw, "no-preference")
        # a second pass for the accessibility contract that only exists under reduced motion
        ctxless = []
        browser = pw.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1440, "height": 900}, reduced_motion="reduce")
        page = ctx.new_page()
        page.goto(BASE, wait_until="networkidle")
        page.locator("#loop").scroll_into_view_if_needed()
        page.wait_for_timeout(1500)
        vid = page.locator("#loop-video-en")
        check(vid.evaluate("v => v.paused"), "reduced motion: video does not autoplay")
        check(bool(vid.get_attribute("poster")), "reduced motion: poster is present")
        revealed = page.evaluate(
            """() => [...document.querySelectorAll('.reveal')]
                   .filter(e => parseFloat(getComputedStyle(e).opacity) < 0.9).length"""
        )
        check(revealed == 0, "reduced motion: reveal content is not trapped at opacity 0", f"{revealed} faded")
        ctx.close()
        browser.close()
        del ctxless

    failed = [label for ok, label in results if not ok]
    for ok, label in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {label}")
    print(f"\n{len(results) - len(failed)}/{len(results)} smoke checks passed.")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

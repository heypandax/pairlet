#!/usr/bin/env python3
"""Validate App Store metadata and deterministic screenshot deliverables."""

from pathlib import Path
import json
import shutil
import subprocess
import struct
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
METADATA = ROOT / "fastlane" / "metadata"
SCREENSHOTS = ROOT / "fastlane" / "screenshots"
PREVIEWS = ROOT / "fastlane" / "previews"
LOCALES = ("en-US", "zh-Hans")
# The iPad set lives one level down so the two device sets stay tellable apart. The name is ours to
# choose (deliver never descends into a language folder); "ipadPro129" is the string fastlane itself
# uses to disambiguate a 2048x2732 file between the 2nd- and 3rd-gen 12.9-inch slots
# (Deliver::AppScreenshot.resolve_ipadpro_conflict_if_needed), so it stays readable to fastlane eyes.
IPAD_DIR = "ipadPro129"
IPAD_SIZE = (2048, 2732)
LIMITS = {
    "name.txt": 30,
    "subtitle.txt": 30,
    "description.txt": 4000,
    "keywords.txt": 100,
    "promotional_text.txt": 170,
    "release_notes.txt": 4000,
}
URL_FIELDS = ("marketing_url.txt", "support_url.txt", "privacy_url.txt")
FORBIDDEN = (
    "Session Handoff",
    "Folder Share",
    "Stronger version if you decide",
    "若上架时主打",
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def png_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()[:24]
    if len(data) != 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        fail(f"not a valid PNG: {path}")
    return struct.unpack(">II", data[16:24])


def preview_properties(path: Path) -> dict:
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        fail("ffprobe is required to validate App Preview encoding")
    result = subprocess.run(
        [ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def main() -> None:
    public_text: list[str] = []
    for locale in LOCALES:
        directory = METADATA / locale
        for filename, limit in LIMITS.items():
            path = directory / filename
            if not path.is_file():
                fail(f"missing metadata: {path}")
            value = path.read_text(encoding="utf-8").strip()
            if not value:
                fail(f"empty metadata: {path}")
            if len(value) > limit:
                fail(f"{path} is {len(value)} characters; limit is {limit}")
            public_text.append(value)

        for filename in URL_FIELDS:
            path = directory / filename
            if not path.is_file():
                fail(f"missing metadata: {path}")
            value = path.read_text(encoding="utf-8").strip()
            url = urlsplit(value)
            if url.scheme != "https" or not url.netloc or any(c.isspace() for c in value):
                fail(f"{path} must contain one HTTPS URL")

        # 6.5-inch iPhone set — TOP-LEVEL pngs only. glob("*.png") does not recurse, which is exactly
        # what keeps the iPad subfolder checked below out of this set; do not make it "**/*.png".
        shots = sorted(path for path in (SCREENSHOTS / locale).glob("*.png") if path.is_file())
        if len(shots) != 6:
            fail(f"{locale} needs exactly 6 screenshots; found {len(shots)}")
        expected = [f"{index:02d}-" for index in range(1, 7)]
        if any(not shot.name.startswith(prefix) for shot, prefix in zip(shots, expected)):
            fail(f"{locale} screenshots must use stable 01- through 06- ordering")
        for shot in shots:
            if png_dimensions(shot) != (1242, 2688):
                fail(f"{shot} must be 1242x2688 for APP_IPHONE_65; got {png_dimensions(shot)}")

        # 12.9-inch iPad set (issue #334). App Store Connect will not accept a submission for the
        # universal binary without one, and it lives in a subfolder because `fastlane deliver` globs a
        # language folder non-recursively — so deliver never sees these and cannot guess a display type
        # from their pixel size (2048x2732 is ALSO the 2nd-gen 12.9 slot). sync-appstore-screenshots.rb
        # uploads them to APP_IPAD_PRO_3GEN_129 by name instead.
        ipad_dir = SCREENSHOTS / locale / IPAD_DIR
        ipad_shots = sorted(path for path in ipad_dir.glob("*.png") if path.is_file())
        if len(ipad_shots) != 6:
            fail(f"{locale}/{IPAD_DIR} needs exactly 6 iPad screenshots; found {len(ipad_shots)}")
        if any(shot.name[:3] != prefix for shot, prefix in zip(ipad_shots, expected)):
            fail(f"{locale}/{IPAD_DIR} screenshots must use stable 01- through 06- ordering")
        for shot in ipad_shots:
            if png_dimensions(shot) != IPAD_SIZE:
                fail(
                    f"{shot} must be {IPAD_SIZE[0]}x{IPAD_SIZE[1]} for APP_IPAD_PRO_3GEN_129; "
                    f"got {png_dimensions(shot)}"
                )

        # anything else nested under the locale is invisible to BOTH deliver and the sync script, so
        # it would ship as a silently missing device set rather than as an error
        strays = sorted(p.name for p in (SCREENSHOTS / locale).iterdir() if p.is_dir() and p.name != IPAD_DIR)
        if strays:
            fail(f"{locale} has screenshot subfolder(s) nothing uploads: {', '.join(strays)}")

        preview = PREVIEWS / locale / "app-preview.mov"
        if not preview.is_file() or preview.stat().st_size < 1_000_000:
            fail(f"missing or implausibly small App Preview: {preview}")
        if preview.read_bytes()[4:8] != b"ftyp":
            fail(f"not a QuickTime/MP4 container: {preview}")
        properties = preview_properties(preview)
        streams = properties["streams"]
        video = next((stream for stream in streams if stream["codec_type"] == "video"), None)
        audio = next((stream for stream in streams if stream["codec_type"] == "audio"), None)
        if not video or (
            video.get("codec_name") != "h264"
            or video.get("profile") != "High"
            or video.get("level") != 40
            or (video.get("width"), video.get("height")) != (886, 1920)
            or video.get("pix_fmt") != "yuv420p"
            or video.get("avg_frame_rate") != "30/1"
            or not 10_000_000 <= int(video.get("bit_rate", 0)) <= 12_000_000
        ):
            fail(f"{preview} does not meet the 6.5-inch H.264 App Preview video specification")
        if not audio or (
            audio.get("codec_name") != "aac"
            or audio.get("sample_rate") != "48000"
            or audio.get("channels") != 2
            or not 220_000 <= int(audio.get("bit_rate", 0)) <= 280_000
        ):
            fail(f"{preview} does not meet the stereo AAC App Preview audio specification")
        duration = float(properties["format"].get("duration", 0))
        if not 15 <= duration <= 30 or preview.stat().st_size > 500_000_000:
            fail(f"{preview} must be 15-30 seconds and no larger than 500 MB")

    joined = "\n".join(public_text)
    for phrase in FORBIDDEN:
        if phrase.casefold() in joined.casefold():
            fail(f"public metadata must not promote or expose draft text: {phrase}")

    print(
        f"App Store content OK: {len(LOCALES)} locales, "
        f"{len(LOCALES) * (len(LIMITS) + len(URL_FIELDS))} metadata fields, "
        "12 iPhone 6.5\" + 12 iPad 12.9\" screenshots, 2 previews"
    )


if __name__ == "__main__":
    main()

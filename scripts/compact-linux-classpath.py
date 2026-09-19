#!/usr/bin/env python3
"""Keep jpackage's Linux launcher IPC small without changing classpath order.

JDK-8380085: JDK 17's launcher assumes one pipe read transfers all launcher
data. A long expanded classpath can exceed an 8 KiB pipe and crash before JVM
startup. A standard manifest Class-Path moves that list out of native IPC.
Apply to the private app image, before packaging; never edit installed apps.
"""

import argparse
from pathlib import Path
from urllib.parse import quote, unquote
from zipfile import ZipFile, ZipInfo


CLASSPATH_JAR = "cc-pocket-classpath.jar"
COMPACT_ENTRY = f"app.classpath=$APPDIR/{CLASSPATH_JAR}"


def manifest_bytes(names):
    # URI encoding makes every character ASCII, including spaces/non-ASCII names.
    value = "Class-Path: " + " ".join(quote(name, safe="/") for name in names)
    if len(value) > 65535:
        raise ValueError("Class-Path exceeds the JAR manifest header limit")
    lines = ["Manifest-Version: 1.0", value[:70]]
    value = value[70:]
    while value:
        lines.append(" " + value[:69])
        value = value[69:]
    return ("\r\n".join(lines) + "\r\n\r\n").encode("ascii")


def validate_names(app_dir, names):
    if not names:
        raise ValueError("No application classpath entries")
    for name in names:
        path = Path(name)
        if (path.is_absolute() or ".." in path.parts or "\\" in name
                or "$" in name or ":" in name or path.suffix != ".jar"
                or name == CLASSPATH_JAR):
            raise ValueError(f"Unsupported application JAR path: {name}")
        if not (app_dir / path).is_file():
            raise ValueError(f"Missing application JAR: {name}")


def compact(image):
    app_dir = image / "lib" / "app"
    cfg = app_dir / "CC Pocket.cfg"
    lines = cfg.read_text(encoding="utf-8").splitlines()
    entries = [line for line in lines if line.startswith("app.classpath=")]
    jar = app_dir / CLASSPATH_JAR
    if entries == [COMPACT_ENTRY]:
        # An incremental packaging run must validate, not create a self-reference.
        with ZipFile(jar) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF")
        unfolded = manifest.decode("ascii").replace("\r\n ", "")
        headers = [line for line in unfolded.splitlines() if line.startswith("Class-Path: ")]
        if len(headers) != 1:
            raise ValueError("Invalid compact classpath manifest")
        names = [unquote(name) for name in headers[0][12:].split()]
        validate_names(app_dir, names)
        if manifest != manifest_bytes(names):
            raise ValueError("Unexpected compact classpath manifest")
        return len(names)
    prefix = "app.classpath=$APPDIR/"
    if any(not entry.startswith(prefix) for entry in entries):
        raise ValueError("Expected classpath JARs relative to $APPDIR")
    names = [entry[len(prefix):] for entry in entries]
    validate_names(app_dir, names)
    manifest = manifest_bytes(names)
    # Deterministic metadata keeps rebuilds reproducible. Original JARs, their
    # signatures, main class, JVM options, and resource paths remain untouched.
    with ZipFile(jar, "w") as archive:
        archive.writestr(ZipInfo("META-INF/MANIFEST.MF", (1980, 1, 1, 0, 0, 0)), manifest)
    replaced = False
    output = []
    for line in lines:
        if line.startswith("app.classpath="):
            if not replaced:
                output.append(COMPACT_ENTRY)
                replaced = True
        else:
            output.append(line)
    cfg.write_text("\n".join(output) + "\n", encoding="utf-8", newline="\n")
    return len(names)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path, help="private Linux jpackage app image")
    args = parser.parse_args()
    count = compact(args.image)
    print(f"Linux launcher: {count} ordered JARs via {CLASSPATH_JAR}")


if __name__ == "__main__":
    main()

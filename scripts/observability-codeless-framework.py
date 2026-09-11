#!/usr/bin/env python3
"""Recognize Xcode's empty framework placeholder; unknown formats fail closed."""
import re
import subprocess
import sys


def is_codeless_stub(commands):
    # otool prints one header + load commands for every architecture. Never accept a universal
    # binary merely because one slice is empty. Xcode's /dev/null placeholder targets iOS 100.0,
    # contains only an empty __text section, and has no symbol table entries. This is deliberately
    # narrower than 'small binary' or an SDK-name exemption; real stripped SDKs still need dSYMs.
    slices = re.split(r'^.+:\n(?=Load command 0\n)', commands, flags=re.MULTILINE)
    if len(slices) < 2 or slices[0].strip():
        return False
    for architecture in slices[1:]:
        sections = re.findall(r'^Section\n(.*?)(?=^Load command |^Section\n|\Z)',
                              architecture, flags=re.MULTILINE | re.DOTALL)
        if len(sections) != 1:
            return False
        fields = dict(re.findall(r'^\s*(sectname|segname|size)\s+(\S+)\s*$',
                                 sections[0], flags=re.MULTILINE))
        if fields.get('sectname') != '__text' or fields.get('segname') != '__TEXT':
            return False
        if not re.fullmatch(r'0x0+', fields.get('size', '')):
            return False
        if re.findall(r'^\s*nsyms\s+(\d+)\s*$', architecture, re.MULTILINE) != ['0']:
            return False
        if re.findall(r'^\s*minos\s+(\S+)\s*$', architecture, re.MULTILINE) != ['100.0']:
            return False
    return True


def main():
    if len(sys.argv) != 2:
        return 64
    try:
        result = subprocess.run(['otool', '-arch', 'all', '-l', sys.argv[1]],
                                capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return 1
    return 0 if is_codeless_stub(result.stdout) else 1


if __name__ == '__main__':
    sys.exit(main())

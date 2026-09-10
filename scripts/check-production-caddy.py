#!/usr/bin/env python3
"""Refuse production configs that would drop an existing public host.

This checks required site blocks, not Caddy syntax; caddy validate is still required.
"""
import re
import sys
from pathlib import Path

path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "deploy/Caddyfile"
required = {"pocket.ark-nexus.cc", "pairlet.org", "www.pairlet.org", "relay.pairlet.org"}
sites = set(re.findall(r"(?m)^[ \t]*([a-z0-9.-]+)[ \t]*\{[ \t]*$", path.read_text()))
missing = sorted(required - sites)
if missing:
    sys.exit("Refusing production Caddy config: missing " + ", ".join(missing) + ". Merge the current deploy/Caddyfile before deploying.")
print("Production Caddy hosts OK: " + ", ".join(sorted(required)))

#!/usr/bin/env python3
"""Keep the installable Skill self-contained; --check detects stale copies in CI."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COPIES = {
    "tools/phoneuse_client.py": "skills/phone-use/scripts/phoneuse_client.py",
    "docs/api.md": "skills/phone-use/references/api.md",
}


def sync(check=False):
    stale = []
    for source, target in COPIES.items():
        # Normalize line endings so Git's Windows checkout conversion is harmless.
        content = (ROOT / source).read_text(encoding="utf-8")
        path = ROOT / target
        if not path.exists() or path.read_text(encoding="utf-8") != content:
            stale.append(target)
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8", newline="\n")
    return stale


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    stale = sync(args.check)
    if stale and args.check:
        parser.exit(1, "Skill files are stale; run python tools/sync_skill.py:\n" + "\n".join(stale) + "\n")

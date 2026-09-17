"""Check release versions and assemble explicitly selected public artifacts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile

import export_integrations
import sync_skill

ROOT = Path(__file__).resolve().parents[1]


def check(tag=None):
    package = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))
    version = package["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Release version must be major.minor.patch")
    if tag is not None and tag != f"v{version}":
        raise ValueError(f"Tag {tag!r} must match v{version}")
    for name in ("app/build.gradle.kts", "fixture/build.gradle.kts"):
        source = (ROOT / name).read_text(encoding="utf-8")
        if re.search(r'versionName\s*=\s*"([^"]+)"', source).group(1) != version:
            raise ValueError(f"Version mismatch: {name}")
    source = (ROOT / "tools/phoneuse_client.py").read_text(encoding="utf-8")
    if re.search(r'"serverInfo":\s*\{"name": "phone-use", "version": "([^"]+)"', source).group(1) != version:
        raise ValueError("Python MCP version mismatch")
    if package["license"] != "MIT" or not (ROOT / "LICENSE").read_text().startswith("MIT License"):
        raise ValueError("Missing MIT license metadata")
    if sync_skill.sync(check=True):
        raise ValueError("Run python tools/sync_skill.py")
    if not (ROOT / "docs/releases" / f"{version}.md").is_file():
        raise ValueError("Missing release notes")
    return version


def bundle(apk, output, tag=None):
    version = check(tag)
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("Provide the signed, verified release APK")
    # A fresh output directory prevents stale or private files entering the upload.
    if output.exists():
        raise ValueError(f"Output already exists; choose a fresh directory: {output}")
    with tempfile.TemporaryDirectory() as temporary:
        staging = Path(temporary)
        integrations = staging / "integrations"
        archives = export_integrations.export(integrations)
        public = staging / "public"
        public.mkdir()
        shutil.copyfile(apk, public / f"PhoneUse-{version}.apk")
        for archive in archives:
            shutil.copyfile(archive, public / f"{archive.stem}-{version}.zip")
        shutil.copyfile(ROOT / "LICENSE", public / "LICENSE")
        artifacts = sorted(public.iterdir())
        (public / "SHA256SUMS.txt").write_text("".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
            for path in artifacts), encoding="utf-8")
        shutil.copytree(public, output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "bundle"))
    parser.add_argument("--tag")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/release")
    args = parser.parse_args()
    if args.command == "check":
        print(check(args.tag))
    else:
        if args.apk is None:
            parser.error("bundle requires --apk (already signed and verified)")
        print(bundle(args.apk, args.output, args.tag))


if __name__ == "__main__":
    main()

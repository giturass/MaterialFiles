#!/usr/bin/env python3
"""Verify a release APK's signer, manifest and bundled Sora assets before upload."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def run(*command):
    result = subprocess.run(list(map(str, command)), check=True, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--expected-certificate-sha256", required=True)
    args = parser.parse_args()
    expected = args.expected_certificate_sha256.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        parser.error("Expected certificate fingerprint must be a SHA-256 digest")

    signature = run(args.build_tools / "apksigner", "verify", "--verbose", "--print-certs",
                    args.apk)
    certificates = {value.replace(":", "").lower() for value in re.findall(
        r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", signature)}
    if certificates != {expected}:
        raise RuntimeError("APK signer does not match the MDTerm release certificate")

    badging = run(args.build_tools / "aapt2", "dump", "badging", args.apk)
    package = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not package or package.group(1) != "me.zhanghai.android.files":
        raise RuntimeError("Unexpected APK application ID")
    if re.search(r"^application-debuggable\b", badging, re.MULTILINE):
        raise RuntimeError("A release APK must not be debuggable")
    tree = run(args.build_tools / "aapt2", "dump", "xmltree", args.apk,
               "--file", "AndroidManifest.xml")
    if "me.zhanghai.android.files.viewer.text.SoraEditorActivity" not in tree:
        raise RuntimeError("Sora editor activity is missing from the APK manifest")
    if "me.zhanghai.android.files.viewer.text.TextEditorActivity" in tree:
        raise RuntimeError("The old text editor activity is still registered")

    assets = Path(__file__).resolve().parents[1] / "app/src/main/assets/sora"
    provenance = json.loads((assets / "sources.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        abis = sorted({name.split("/")[1] for name in names
                       if name.startswith("lib/") and name.endswith(".so")})
        if "arm64-v8a" not in abis:
            raise RuntimeError("The APK is missing ARM64 native libraries")
        sources = sorted(path for path in assets.rglob("*") if path.is_file())
        for source in sources:
            name = "assets/sora/" + source.relative_to(assets).as_posix()
            if archive.read(name) != source.read_bytes():
                raise RuntimeError("Packaged language asset differs from source: " + name)
        for source in provenance["files"]:
            name = "assets/sora/" + source["path"]
            if hashlib.sha256(archive.read(name)).hexdigest() != source["sha256"]:
                raise RuntimeError("Pinned asset checksum mismatch: " + name)
        if "tables/CR_Space.bin" not in names:
            raise RuntimeError("JCodings runtime tables are missing")
        tables = sum(name.startswith("tables/") and name.endswith(".bin") for name in names)

    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    report = {
        "verified_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_sha": os.environ.get("GITHUB_SHA"),
        "apk": args.apk.name,
        "apk_bytes": args.apk.stat().st_size,
        "apk_sha256": digest,
        "certificate_sha256": expected,
        "application_id": package.group(1),
        "debuggable": False,
        "abis": abis,
        "sora_activity_registered": True,
        "old_editor_activity_removed": True,
        "source_assets_verified": len(sources),
        "pinned_asset_checksums_verified": len(provenance["files"]),
        "jcodings_runtime_tables": tables,
        "device_interaction_tested": False,
    }
    args.apk.with_suffix(".verification.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    args.apk.with_suffix(".sha256").write_text(
        f"{digest}  {args.apk.name}\n", encoding="utf-8")
    args.apk.with_suffix(".signature.txt").write_text(signature, encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Verify the built APK's native archive engines on Android and with official 7zz.

Requires an unminified APK, a JDK 17+, Android SDK D8, the build's Gradle cache and
official 7zz. Runs DEX and bundled JNI libraries with app_process, without installing
the APK or rendering Activities. Generated archives and logs stay under ~/tmp.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True

from archive_test_support import (ApkHarness, CONTENT, NAME, PASSWORD, add_apk_arguments,
                                  run_7zz)


def verify_native_outputs(seven_zip, directory):
    filenames = [(f"{encryption}-{preset}.7z", encryption)
                 for preset in range(3) for encryption in ("plain", "contents", "headers")]
    filenames += [("short-writes.7z", "headers"), ("empty.7z", "headers")]
    checks = 0
    for filename, encryption in filenames:
        archive = directory / filename
        password = [] if encryption == "plain" else ["-p" + PASSWORD]
        run_7zz(seven_zip, "t", *password, archive)
        listing = run_7zz(seven_zip, "l", "-slt", *password, archive).stdout
        # Official 7-Zip writes a zero-entry archive without a header/data block to encrypt.
        # The other encrypted fixtures include empty files and directories with hidden names.
        if encryption != "plain" and filename != "empty.7z":
            wrong = run_7zz(seven_zip, "t", "-pwrong-password", archive, successful=False)
            missing = run_7zz(seven_zip, "t", archive, successful=False)
            if wrong.returncode == 0 or missing.returncode == 0:
                raise AssertionError(filename + " accepted an incorrect/missing password")
            visible = run_7zz(seven_zip, "l", "-pwrong-password", archive, successful=False)
            if encryption == "headers":
                if visible.returncode == 0 or NAME in visible.stdout:
                    raise AssertionError(filename + " exposed encrypted names")
                raw = archive.read_bytes()
                if NAME.encode("utf-16le") in raw or NAME.encode() in raw:
                    raise AssertionError(filename + " contains a plaintext filename")
            elif visible.returncode or NAME not in visible.stdout:
                raise AssertionError(filename + " unexpectedly hid unencrypted names")
            if filename != "empty.7z" and "7zAES" not in listing:
                raise AssertionError(filename + " did not use the official AES codec")
        if filename != "empty.7z":
            for name, expected in [(NAME, CONTENT),
                                   ("large.bin", bytes((i * 37) & 255 for i in range(8192)) * 1024)]:
                extracted = subprocess.run([str(seven_zip), "x", "-so", *password,
                                            str(archive), name], capture_output=True,
                                           stdin=subprocess.DEVNULL, timeout=120)
                if extracted.returncode or extracted.stdout != expected:
                    raise AssertionError(filename + ": official 7zz extracted different " + name)
        checks += 1
        print("ok - official 7zz reads " + filename, flush=True)
    print(f"PASS: {checks} official 7zz writer interoperability checks", flush=True)


def verify_format_outputs(seven_zip, directory):
    manifest = json.loads((directory / "format-manifest.json").read_text())
    archives = manifest if isinstance(manifest, list) else manifest["archives"]
    for index, item in enumerate(archives):
        archive = directory / item["archive"]
        password = ["-p" + item["password"]] if item.get("password") else []
        run_7zz(seven_zip, "t", *password, archive)
        listing = run_7zz(seven_zip, "l", "-slt", *password, archive).stdout
        if item.get("password"):
            wrong = run_7zz(seven_zip, "t", "-pwrong-password", archive, successful=False)
            if wrong.returncode == 0:
                raise AssertionError(str(archive) + " accepted an incorrect password")
            if item["format"].lower() == "zip" and "AES-256" not in listing:
                raise AssertionError(str(archive) + " did not use ZIP AES-256")
        container = archive
        format_name = item["format"].lower()
        if format_name in ("tar.gz", "tar.bz2", "tar.xz", "tar_gz", "tar_bz2", "tar_xz"):
            container = directory / f"official-inner-{index}.tar"
            with container.open("wb") as output:
                extracted = subprocess.run([str(seven_zip), "x", "-so", str(archive)],
                                           stdout=output, stderr=subprocess.PIPE,
                                           stdin=subprocess.DEVNULL, timeout=120)
            if extracted.returncode:
                raise AssertionError(extracted.stderr.decode(errors="replace"))
            run_7zz(seven_zip, "t", container)
        for name, expected in item["members"].items():
            member = [] if item.get("raw") else [name]
            extracted = subprocess.run([str(seven_zip), "x", "-so", *password,
                                        str(container), *member], capture_output=True,
                                       stdin=subprocess.DEVNULL, timeout=120)
            digest = hashlib.sha256(extracted.stdout).hexdigest()
            expected_digest = expected["sha256"] if isinstance(expected, dict) else expected
            if extracted.returncode or digest != expected_digest:
                raise AssertionError(str(archive) + ": official extraction differs for " + name)
        print("ok - official 7zz reads " + item["archive"], flush=True)
    print(f"PASS: {len(archives)} official format interoperability checks", flush=True)


def main(default_suite="all"):
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    add_apk_arguments(parser, root, "materialfiles-archive-check")
    parser.add_argument("--suite", choices=("all", "native", "password", "volumes", "formats", "extraction"),
                        default=default_suite)
    parser.add_argument("--writer-fixtures", type=Path,
                        help="Also read encrypted 7z archives from the previous app writer")
    args = parser.parse_args()
    if args.suite in ("native", "formats", "all") and not args.seven_zip:
        parser.error("Install official 7zz or specify --seven-zip")
    harness = ApkHarness(args, parser, root,
                         need_fixtures=args.suite in ("all", "native", "password", "extraction"))
    if args.suite in ("all", "native"):
        output = harness.work / "native-output"
        arguments = [harness.fixture_dir, output]
        if args.writer_fixtures:
            arguments.append(args.writer_fixtures.resolve())
        harness.run("NativeSevenZipInteropTest", arguments, r"PASS: \d+ native 7z checks",
                    native_binding=True)
        verify_native_outputs(args.seven_zip, output)
    if args.suite in ("all", "password"):
        harness.run("ArchivePasswordFlowTest", [harness.fixture_dir],
                    r"PASS: \d+ APK password-flow checks")
    if args.suite in ("all", "extraction"):
        harness.run("ArchiveExtractionTest", [harness.fixture_dir, harness.work / "extraction"],
                    r"PASS: \d+ archive extraction destination checks",
                    extra_sources=("ArchiveVolumeTest",))
    if args.suite in ("all", "volumes"):
        harness.run("ArchiveVolumeTest", [harness.work / "volumes"],
                    r"PASS: \d+ .*volume.*checks")
    if args.suite in ("all", "formats"):
        output = harness.work / "formats"
        harness.run("ArchiveFormatTest", [output], r"PASS: \d+ archive format checks",
                    extra_sources=("ArchiveVolumeTest",))
        verify_format_outputs(args.seven_zip, output)


if __name__ == "__main__":
    main()

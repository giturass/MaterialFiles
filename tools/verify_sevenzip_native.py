#!/usr/bin/env python3
"""Run 7-Zip engine tests on Android without Gradle or an APK build.

First run build_archive_jbinding.py for the device ABI. This runner reuses its verified
engine objects, links a standalone NDK executable, then checks its archives with 7zz.
All build, archive and report outputs go under --work-dir (default: ~/tmp).
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

from build_archive_jbinding import (
    SOURCE_SHA256, TARGETS, compiler_flags, configuration_key, sha256, source_paths,
)


ZSTANDARD_FIXTURE_SHA256 = "605fdf1af683ce52b51d612c1ffb33e4bf1101490589e3876f0398d10720c909"


def native_abi():
    return {"aarch64": "arm64-v8a", "arm64": "arm64-v8a", "armv7l": "armeabi-v7a",
            "i686": "x86", "x86_64": "x86_64"}.get(platform.machine())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=Path.home() / ".local/share/android-ndk")
    parser.add_argument("--cxx", default="clang++")
    parser.add_argument("--abi", choices=TARGETS, default=native_abi())
    parser.add_argument("--build-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-sevenzip-build")
    parser.add_argument("--work-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-sevenzip-native-test")
    parser.add_argument("--sevenzip", default="7zz", help="Independent 7-Zip command-line binary")
    args = parser.parse_args()
    if not args.abi:
        parser.error("Cannot determine this device's ABI; specify --abi")
    compiler = shutil.which(args.cxx)
    sevenzip = shutil.which(args.sevenzip)
    if not compiler or not sevenzip:
        parser.error("The selected C++ compiler and independent 7zz must already be installed")

    root = Path(__file__).resolve().parents[1]
    vendor = root / "third_party/sevenzip"
    engine = args.build_dir.resolve() / "7zip-26.04"
    build = args.build_dir.resolve() / args.abi
    work_root = args.work_dir.resolve()
    toolchain = args.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64"
    resources = list((toolchain / "lib/clang").glob("[0-9]*"))
    if len(resources) != 1:
        parser.error("Expected one Clang resource directory in the Android NDK")
    if sha256(vendor / "7z2604-src.tar.xz") != SOURCE_SHA256:
        parser.error("Official 7-Zip source archive checksum mismatch")
    marker = engine / ".source-sha256"
    if not marker.is_file() or marker.read_text().strip() != SOURCE_SHA256:
        parser.error("Run build_archive_jbinding.py before running native tests")
    # The builder's extraction marker is not a checksum of the current tree. Check
    # the original files once before testing so local edits cannot change the engine.
    with tarfile.open(vendor / "7z2604-src.tar.xz") as original:
        for member in original:
            if member.isfile():
                actual = engine / member.name
                if not actual.is_file() or actual.read_bytes() != original.extractfile(member).read():
                    parser.error(f"Official engine source was modified: {actual}")

    target, triple = TARGETS[args.abi]
    common = compiler_flags(compiler, toolchain, resources[0], target, engine, vendor)
    # Share the builder's cache key so changed inputs cannot silently reuse old code.
    compiler_version = subprocess.check_output([compiler, "--version"], text=True)
    configuration = configuration_key(vendor, engine, common, compiler_version)
    objects = []
    for index, source in enumerate(source_paths(vendor, engine)):
        if not source.is_relative_to(engine):
            continue
        obj = build / f"{index:03d}-{source.name}.o"
        stamp = obj.with_suffix(".sha256")
        expected = hashlib.sha256((configuration + sha256(source) + "False").encode()).hexdigest()
        if not obj.is_file() or not stamp.is_file() or stamp.read_text() != expected:
            parser.error(f"Missing/stale engine object {obj}; rerun build_archive_jbinding.py")
        objects.append(obj)
    if not objects:
        parser.error("No engine objects were selected")

    fixture = root / "tests/fixtures/zip-zstandard.zip"
    if sha256(fixture) != ZSTANDARD_FIXTURE_SHA256:
        parser.error("Independent ZIP Zstandard fixture checksum mismatch")
    with zipfile.ZipFile(fixture) as archive:
        if len(archive.infolist()) != 2 or any(info.compress_type != 93 for info in archive.infolist()):
            parser.error("Independent ZIP fixture must contain two Zstandard method 93 entries")

    work_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=work_root))
    archives = work / "archives"
    archives.mkdir()
    env = os.environ.copy()
    env["TMPDIR"] = str(work)
    commands = []

    def run(command, name, binary=False, success=True):
        command = list(map(str, command))
        process = subprocess.run(command, env=env, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        record = {"name": name, "command": command, "exit_code": process.returncode}
        commands.append(record)
        stdout = process.stdout.decode(errors="replace") if not binary else ""
        stderr = process.stderr.decode(errors="replace")
        (work / (name + ".log")).write_text(stdout + stderr)
        (work / "commands.json").write_text(json.dumps(commands, indent=2, ensure_ascii=False) + "\n")
        expected_code = 0 if success else 2  # 7-Zip's fatal-error code for an incorrect password.
        if process.returncode != expected_code:
            raise RuntimeError(f"{name} returned {process.returncode}:\n{stdout[-4000:]}{stderr[-4000:]}")
        return process.stdout

    executable = work / "SevenZipNativeTest"
    libraries = toolchain / "sysroot/usr/lib" / triple
    runtime = [libraries / "libc++_static.a", libraries / "libc++abi.a"]
    if not all(path.is_file() for path in runtime):
        parser.error("Missing Android NDK static C++ runtime")
    command = common + [
        "-std=c++11", "-fexceptions", "-fno-rtti", "-Wall", "-Wextra", "-Werror",
        root / "tests/SevenZipNativeTest.cpp", *objects, "-pie", "-nostdlib++",
        "-Wl,--no-undefined,--gc-sections,--build-id=sha1,--exclude-libs,ALL",
        "-Wl,-z,relro,-z,now,-z,max-page-size=16384", *runtime, "-o", executable,
    ]
    print(f"Linking standalone native tests using {len(objects)} {args.abi} engine objects", flush=True)
    run(command, "native-build")
    native_output = run([executable, archives, fixture], "native-tests").decode()
    print(native_output, end="", flush=True)

    version_output = run([sevenzip, "-h"], "independent-version").decode()
    version_match = re.search(r"7-Zip.*?26\.04.*", version_output)
    if not version_match:
        raise RuntimeError("Independent decoder must be official 7-Zip 26.04")

    payload = bytes((i * 17 + i // 251) & 255 for i in range(65537))
    verified = []

    def listing_entries(text):
        entries = []
        entry = {}
        for line in text.splitlines():
            if not line.strip():
                if entry:
                    entries.append(entry)
                    entry = {}
            elif " = " in line:
                key, value = line.split(" = ", 1)
                entry[key] = value
        if entry:
            entries.append(entry)
        return entries

    def verify_archive(name, password=None, member=None, empty=False, expected=payload):
        archive = archives / name
        password_option = ["-p" + password] if password is not None else []
        run([sevenzip, "t", "-bd", "-bb0", "-y", *password_option, archive], name + "-integrity")
        extracted = run([sevenzip, "x", "-so", "-bd", "-bb0", "-y", *password_option,
                         archive, *([member] if member else [])], name + "-extract", binary=True)
        if extracted != (b"" if empty else expected):
            raise RuntimeError(f"Independent decoding changed the contents of {name}")
        if member and member != "payload":
            listing = run([sevenzip, "l", "-slt", "-ba", "-bd", *password_option, archive],
                          name + "-listing").decode()
            entries = listing_entries(listing)
            if not any(entry.get("Path") == "empty.txt" and entry.get("Size") == "0"
                       for entry in entries):
                raise RuntimeError(f"Independent decoder could not find the empty entry in {name}")
            if name == "encrypted.zip" and not any(
                    entry.get("Path") == member and entry.get("Method", "").startswith("AES-256 ")
                    for entry in entries):
                raise RuntimeError("Independent decoder did not report ZIP AES-256 encryption")
            extracted_empty = run([sevenzip, "x", "-so", "-bd", "-bb0", "-y", *password_option,
                                   archive, "empty.txt"], name + "-empty", binary=True)
            if extracted_empty:
                raise RuntimeError(f"Empty entry in {name} contains unexpected data")
        if password:
            run([sevenzip, "t", "-bd", "-bb0", "-y", "-pincorrect-test-password", archive],
                name + "-wrong-password", success=False)
        verified.append({"archive": name, "sha256": sha256(archive)})
        print(f"PASS independent 7zz: {name}", flush=True)

    for extension in ("7z", "zip", "tar"):
        verify_archive("plain." + extension, member="folder/中文.txt")
    for extension in ("xz", "gz", "bz2"):
        verify_archive("plain." + extension)
        verify_archive("empty." + extension, empty=True)
    verify_archive("encrypted.7z", "archive-test-密码", "folder/中文.txt")
    verify_archive("encrypted.zip", "archive-test-password", "folder/中文.txt")
    for dictionary in (4096, 65536):
        for extension in ("7z", "xz"):
            verify_archive(f"dictionary-{dictionary}.{extension}",
                           member="payload" if extension == "7z" else None)
    for extension in ("7z", "xz"):
        verify_archive("dictionary-log16." + extension,
                       member="payload" if extension == "7z" else None)

    report = {"engine_version": "26.04", "abi": args.abi, "source_sha256": SOURCE_SHA256,
              "engine_objects": len(objects), "native_tests": native_output.splitlines(),
              "independent_decoder": version_match.group(), "archives": verified,
              "zstandard_fixture_sha256": sha256(fixture), "commands": "commands.json"}
    report_path = work / "report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    (work_root / "latest-run.txt").write_text(str(work) + "\n")
    print(f"Native and independent decoding checks passed. Report: {report_path}", flush=True)


if __name__ == "__main__":
    main()

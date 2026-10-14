#!/usr/bin/env python3
"""Verify the APK's 7-Zip-JBinding archive operations on Android and with official 7zz.

Requires an unminified APK, a JDK 17+, Android SDK D8, the build's Gradle cache and
official 7zz. Runs DEX and bundled JNI libraries with app_process, without installing
the APK or rendering Activities. Logs, downloaded fixtures and outputs stay under ~/tmp.
Reader fixtures are pinned to upstream commits and verified before extraction.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True

from archive_test_support import (ApkHarness, add_apk_arguments, reader_fixtures, run_7zz)


def verify_zip_aes256(archive, item, listed):
    """Check actual local/central ZIP records as well as the independent decoder's method."""
    if item.get("splitSize"):
        base = str(archive)[:-4]
        volumes = []
        index = 1
        while (part := Path(base + f".{index:03d}")).is_file():
            volumes.append(part.read_bytes())
            index += 1
        data = b"".join(volumes)
    else:
        data = archive.read_bytes()

    def aes_field(extra, name):
        position = 0
        while position + 4 <= len(extra):
            tag, size = struct.unpack_from("<HH", extra, position)
            position += 4
            field = extra[position:position + size]
            if len(field) != size:
                raise AssertionError(str(archive) + ": truncated ZIP extra field for " + name)
            if tag == 0x9901:
                if size != 7:
                    raise AssertionError(str(archive) + ": invalid WinZip AES extra field for " + name)
                version, vendor, strength, method = struct.unpack("<H2sBH", field)
                if version not in (1, 2) or vendor != b"AE" or strength != 3 or method == 99:
                    raise AssertionError(str(archive) + ": ZIP entry is not WinZip AES-256: " + name)
                return version, method
            position += size
        raise AssertionError(str(archive) + ": encrypted ZIP entry has no AES-256 extra field: " + name)

    checked = 0
    with zipfile.ZipFile(io.BytesIO(data)) as container:
        for name, expected in item["members"].items():
            if not expected["size"]:
                continue
            entry = container.getinfo(name)
            if not entry.flag_bits & 1 or entry.compress_type != 99:
                raise AssertionError(str(archive) + ": encrypted ZIP entry is not AES: " + name)
            central_aes = aes_field(entry.extra, name)
            offset = entry.header_offset
            if data[offset:offset + 4] != b"PK\x03\x04":
                raise AssertionError(str(archive) + ": invalid local ZIP header for " + name)
            flags, method = struct.unpack_from("<HH", data, offset + 6)
            if not flags & 1 or method != 99:
                raise AssertionError(str(archive) + ": local ZIP header does not declare AES: " + name)
            name_size, extra_size = struct.unpack_from("<HH", data, offset + 26)
            start = offset + 30 + name_size
            if aes_field(data[start:start + extra_size], name) != central_aes:
                raise AssertionError(str(archive) + ": local and central AES metadata differ: " + name)
            if "AES-256" not in listed.get(name, {}).get("Method", ""):
                raise AssertionError(str(archive) + ": official 7zz did not recognize AES-256: " + name)
            checked += 1
    if not checked:
        raise AssertionError(str(archive) + ": no nonempty ZIP entry exercised AES-256")
    return {"zip_encryption": "AES-256", "aes256_entries": checked,
            "verified_local_and_central_aes_extra": True}


def verify_format_outputs(seven_zip, directory):
    manifest_path = directory / "format-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    archives = manifest["archives"]
    failures = []
    results = []
    for index, item in enumerate(archives):
        try:
            features = verify_format_output(seven_zip, directory, index, item)
            results.append({"archive": item["archive"], "passed": True, "features": features})
        except (AssertionError, RuntimeError, subprocess.TimeoutExpired,
                zipfile.BadZipFile, KeyError, struct.error) as failure:
            failures.append(item["archive"])
            results.append({"archive": item["archive"], "passed": False, "error": str(failure)})
            print("FAIL - " + str(failure), flush=True)
    by_archive = {item["archive"]: item for item in results}
    for preset in ("speed", "standard", "quality"):
        headers = by_archive.get("output/7z-headers-" + preset + ".7z")
        contents = by_archive.get("output/7z-contents-" + preset + ".7z")
        if not headers or not contents or not headers["passed"] or not contents["passed"]:
            continue
        if any(headers["features"][key] != contents["features"][key]
               for key in ("methods", "packed_stream_bytes")):
            headers["passed"] = False
            headers["error"] = "Header encryption changed the compression method or packed block length"
            failures.append(headers["archive"])
            print("FAIL - " + headers["archive"] + ": " + headers["error"], flush=True)
        else:
            headers["features"]["matches_content_only_compression"] = True
    report = {"reference_reader": next(line for line in run_7zz(seven_zip, "i").stdout.splitlines()
                                        if line.startswith("7-Zip")),
              "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
              "passed": len(archives) - len(failures), "failed": len(failures), "archives": results}
    report_path = directory / "official-verification.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print("Official interoperability report:", report_path.resolve(), flush=True)
    if failures:
        raise AssertionError(f"{len(failures)} / {len(archives)} official format checks failed: {failures}")
    print(f"PASS: {len(archives)} official format interoperability checks", flush=True)


def verify_format_output(seven_zip, directory, index, item):
    archive = directory / item["archive"]
    features = {"preset": item["preset"]}
    if item["format"] in ("bzip2", "tar.bz2"):
        # 7-Zip normalizes compression levels 1/5/7 to block sizes 100/900/900 KiB.
        expected_header = b"BZh1" if item["preset"] == "SPEED" else b"BZh9"
        with archive.open("rb") as source:
            header = source.read(4)
        if header != expected_header:
            raise AssertionError(str(archive) + ": BZip2 block size did not honor " + item["preset"])
        features["bzip2_header"] = header.decode("ascii")
    if item["format"] == "tar" and not item.get("splitSize") and archive.suffix.lower() != ".tar":
        # The APK also tests a renamed empty TAR. Official 7zz requires a .tar name
        # even with -tTar for the valid, all-zero 1024-byte empty container.
        reference = directory / f"official-named-{index}.tar"
        shutil.copyfile(archive, reference)
        archive = reference
    password = ["-p" + item["password"]] if item.get("password") else []
    run_7zz(seven_zip, "t", *password, archive)
    listing = run_7zz(seven_zip, "l", "-slt", *password, archive).stdout
    if item["format"] == "7z" and sum(member["size"] > 0 for member in item["members"].values()) > 1:
        if not re.search(r"^Solid = \+$", listing, re.MULTILINE):
            raise AssertionError(str(archive) + ": multiple nonempty members did not use solid compression")
        features["solid"] = True
    if item.get("password"):
        wrong = run_7zz(seven_zip, "t", "-pwrong-password", archive, successful=False)
        missing = run_7zz(seven_zip, "t", archive, successful=False)
        if wrong.returncode == 0 or missing.returncode == 0:
            raise AssertionError(str(archive) + " accepted an incorrect or absent password")
        if item["format"] == "7z" and "7zAES" not in listing:
            raise AssertionError(str(archive) + " did not use 7z AES encryption")
        visible = run_7zz(seven_zip, "l", "-pwrong-password", archive, successful=False)
        if item.get("encryptFileNames"):
            if visible.returncode == 0:
                raise AssertionError(str(archive) + " exposed encrypted file names")
            features["encrypted_headers"] = True
        elif visible.returncode:
            raise AssertionError(str(archive) + " unexpectedly encrypted file names")
    container = archive
    if item["format"] in ("tar.gz", "tar.bz2"):
        container = directory / f"official-inner-{index}.tar"
        with container.open("wb") as output:
            extracted = subprocess.run([str(seven_zip), "x", "-so", str(archive)],
                                       stdout=output, stderr=subprocess.PIPE,
                                       stdin=subprocess.DEVNULL, timeout=120)
        if extracted.returncode:
            raise AssertionError(extracted.stderr.decode(errors="replace"))
        run_7zz(seven_zip, "t", container)
        listing = run_7zz(seven_zip, "l", "-slt", "-ba", container).stdout
    listed = {}
    for block in re.split(r"\n\s*\n", listing.strip()):
        fields = dict(line.split(" = ", 1) for line in block.splitlines() if " = " in line)
        if "Path" in fields:
            listed[fields["Path"].rstrip("/")] = fields
    if not item.get("raw") and not set(item["members"]).issubset(listed):
        raise AssertionError(str(archive) + ": listed members are missing: "
                             + str(sorted(set(item["members"]) - set(listed))))
    if item["format"] == "zip" and item.get("password"):
        features.update(verify_zip_aes256(archive, item, listed))
    if item["format"] == "7z":
        features["methods"] = sorted({listed[name].get("Method", "")
                                       for name, member in item["members"].items() if member["size"]})
        features["packed_stream_bytes"] = sum(int(listed[name].get("Packed Size", "") or "0")
                                               for name in item["members"])
    for name, expected in item["members"].items():
        fields = listed.get(name, {})
        if "mode" in expected:
            bits = re.search(r"[dl-][rwxstST-]{9}", fields.get("Mode", "") + " " + fields.get("Attributes", ""))
            if not bits or bits.group()[1:] != stat.filemode(expected["mode"])[1:]:
                raise AssertionError(str(archive) + ": stored file permissions differ for " + name)
        if expected.get("symlink") and fields.get("Symbolic Link"):
            if expected["symlink"] != fields["Symbolic Link"]:
                raise AssertionError(str(archive) + ": symbolic-link target differs for " + name)
            continue
        member = [] if item.get("raw") else [name]
        extracted = subprocess.run([str(seven_zip), "x", "-so", *password,
                                    str(container), *member], capture_output=True,
                                   stdin=subprocess.DEVNULL, timeout=120)
        if extracted.returncode or hashlib.sha256(extracted.stdout).hexdigest() != expected["sha256"]:
            raise AssertionError(str(archive) + ": official extraction differs for " + name)
    for name in item["directories"]:
        fields = listed.get(name, {})
        if fields.get("Folder") != "+" and not fields.get("Attributes", "").startswith("D"):
            raise AssertionError(str(archive) + ": directory is missing: " + name)
    print("ok - official 7zz reads " + item["archive"], flush=True)
    return features


def main(default_suite="all"):
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    add_apk_arguments(parser, root, "materialfiles-archive-check")
    parser.add_argument("--suite", choices=("all", "reader", "password", "volumes", "formats", "extraction"),
                        default=default_suite)
    parser.add_argument("--reader-fixtures", type=Path, help="Reuse a generated reader-manifest.json")
    parser.add_argument("--verify-format-outputs", type=Path,
                        help="Independently verify an existing directory containing format-manifest.json")
    parser.add_argument("--prepare-reader-fixtures", action="store_true",
                        help="Prepare pinned reader fixtures and reference hashes, without running the APK")
    args = parser.parse_args()
    if args.prepare_reader_fixtures:
        args.suite = "reader"
    if args.verify_format_outputs:
        args.suite = "formats"
    if args.suite in ("reader", "formats", "all") and not args.seven_zip:
        parser.error("Install official 7zz or specify --seven-zip")
    if args.verify_format_outputs:
        verify_format_outputs(args.seven_zip, args.verify_format_outputs)
        return
    harness = ApkHarness(args, parser, root,
                         need_fixtures=args.suite in ("all", "reader", "password", "extraction"))
    if args.suite in ("all", "volumes"):
        harness.run("ArchiveVolumeTest", [harness.work / "volumes"],
                    r"PASS: \d+ .*volume.*checks")
    if args.suite in ("all", "formats"):
        output = harness.work / "formats"
        harness.run("ArchiveFormatTest", [output], r"PASS: \d+ archive format checks",
                    extra_sources=("ArchiveVolumeTest",), timeout=600)
        verify_format_outputs(args.seven_zip, output)
    if args.suite in ("all", "password"):
        harness.run("ArchivePasswordFlowTest", [harness.fixture_dir],
                    r"PASS: \d+ APK password-flow checks")
    if args.suite in ("all", "extraction"):
        harness.run("ArchiveExtractionTest", [harness.fixture_dir, harness.work / "extraction"],
                    r"PASS: \d+ archive extraction destination checks",
                    extra_sources=("ArchiveVolumeTest",))
    if args.suite in ("all", "reader"):
        manifest = args.reader_fixtures or reader_fixtures(args.seven_zip, harness.work / "reader-fixtures",
                                                          args.work_dir / "downloads", root, harness.fixture_dir)
        if args.prepare_reader_fixtures:
            print("Reader manifest:", manifest.resolve(), flush=True)
            return
        harness.run("ArchiveReaderTest", [manifest, harness.work / "reader-output"],
                    r"PASS: \d+ JBinding reader checks", extra_sources=("ArchiveVolumeTest",),
                    timeout=600)


if __name__ == "__main__":
    main()

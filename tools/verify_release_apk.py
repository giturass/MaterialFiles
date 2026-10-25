#!/usr/bin/env python3
"""Verify a release APK's signer, archive libraries, tracking absence and editor assets."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from verify_no_tracking import verify_no_tracking


ENGINE_VERSION = "26.04"
ENGINE_DATE = "2026-10-05"
JBINDING_VERSION = "26.03-2.4"
JBINDING_COMMIT = "7486f0dcfc602cd65c0ad7e187bbf0cc005bf6af"
PROPERTY_INTERFACE_VERSION = 1
PROPERTY_VERSION_SYMBOL = ("Java_me_zhanghai_android_files_provider_archive_archiver_"
                           "SevenZipArchiveProperties_nativeGetInterfaceVersion")
ENGINE_ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40),
               "x86": (1, 3), "x86_64": (2, 62)}
# Release compatibility matches app/build.gradle; ELF inspection still recognizes every ABI.
RELEASE_ABIS = {"arm64-v8a"}
RELEASE_MIN_SDK = 30


def verify_release_platform(badging, abis):
    """Require the advertised Android 11+ ARM64 release target."""
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'$", badging, re.MULTILINE)
    min_sdk = int(sdk.group(1)) if sdk else None
    if min_sdk != RELEASE_MIN_SDK:
        raise RuntimeError(f"The release APK must declare minSdk {RELEASE_MIN_SDK}; "
                           f"found {min_sdk if min_sdk is not None else 'no numeric minSdk'}")
    if set(abis) != RELEASE_ABIS:
        raise RuntimeError("The release APK must contain only the arm64-v8a native ABI; found "
                           + (", ".join(sorted(abis)) or "none"))
    return min_sdk


def verify_native_library(data, abi, label, markers):
    """Inspect a packaged ELF's architecture, load alignment and mapped version markers."""
    expected_class, expected_machine = ENGINE_ABIS[abi]
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:7] != bytes((expected_class, 1, 1)):
        raise RuntimeError("Invalid " + label + " ELF header for " + abi)
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type != 3 or machine != expected_machine:
        raise RuntimeError("Packaged " + label + " has the wrong architecture for " + abi)
    if expected_class == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        entry_format = "<IIQQQQQQ"
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
        entry_format = "<IIIIIIII"
    if not count or entry_size < struct.calcsize(entry_format) \
            or offset + entry_size * count > len(data):
        raise RuntimeError("Invalid " + label + " ELF program headers for " + abi)
    segments = []
    alignments = []
    for index in range(count):
        header = struct.unpack_from(entry_format, data, offset + index * entry_size)
        if header[0] != 1:  # PT_LOAD
            continue
        if expected_class == 2:
            _, _, start, address, _, length, _, alignment = header
        else:
            _, start, address, _, length, _, _, alignment = header
        if start + length > len(data):
            raise RuntimeError("Truncated " + label + " ELF load segment for " + abi)
        if alignment < 16384 or alignment & (alignment - 1) \
                or address % alignment != start % alignment:
            raise RuntimeError("Packaged " + label + " does not support 16 KiB pages: " + abi)
        segments.append(data[start:start + length])
        alignments.append(alignment)
    if not segments:
        raise RuntimeError("Packaged " + label + " has no ELF load segments: " + abi)
    # Search mapped bytes, so a version appearing only in debug information or
    # an appended metadata document cannot pass the packaged-engine check.
    for marker in markers:
        encoded = marker.encode("ascii") + b"\0"
        if not any(encoded in segment for segment in segments):
            raise RuntimeError("Packaged " + label + " is missing " + repr(marker) + " for " + abi)
    return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "load_alignment": alignments}


def verify_engine_library(data, abi):
    result = verify_native_library(data, abi, "7-Zip engine", (
        ENGINE_VERSION, ENGINE_DATE,
        "Java_net_sf_sevenzipjbinding_SevenZip_nativeGetVersionVersion",
        "Java_net_sf_sevenzipjbinding_SevenZip_nativeGetVersionDate"))
    result.update(version=ENGINE_VERSION, date=ENGINE_DATE,
                  version_jni_symbols_and_load_strings_checked=True)
    return result


def exports_function(data, name):
    """Check the dynamic symbol definition, not an undefined import or a string literal."""
    is_64 = data[4] == 2
    offset = struct.unpack_from("<Q" if is_64 else "<I", data, 40 if is_64 else 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 58 if is_64 else 46)
    section_format = "<IIQQQQIIQQ" if is_64 else "<IIIIIIIIII"
    symbol_format = "<IBBHQQ" if is_64 else "<IIIBBH"
    if not count or entry_size < struct.calcsize(section_format) \
            or offset + entry_size * count > len(data):
        raise RuntimeError("Invalid ELF section headers for JNI export verification")
    sections = [struct.unpack_from(section_format, data, offset + index * entry_size)
                for index in range(count)]
    for section in sections:
        if section[1] != 11:  # SHT_DYNSYM
            continue
        start, size, strings_index, width = section[4], section[5], section[6], section[9]
        if width < struct.calcsize(symbol_format) or size % width \
                or start + size > len(data) or strings_index >= count:
            raise RuntimeError("Invalid ELF dynamic symbol table")
        strings_header = sections[strings_index]
        strings_start, strings_size = strings_header[4], strings_header[5]
        if strings_header[1] != 3 or strings_start + strings_size > len(data):
            raise RuntimeError("Invalid ELF dynamic string table")
        strings = data[strings_start:strings_start + strings_size]
        for position in range(start, start + size, width):
            symbol = struct.unpack_from(symbol_format, data, position)
            name_offset = symbol[0]
            info, visibility, definition = symbol[1:4] if is_64 else symbol[3:6]
            if info >> 4 not in (1, 2) or info & 15 != 2 \
                    or visibility & 3 not in (0, 3) or definition == 0:
                continue
            if name_offset >= len(strings):
                raise RuntimeError("Invalid ELF dynamic symbol name")
            end = strings.find(b"\0", name_offset)
            if end < 0:
                raise RuntimeError("Unterminated ELF dynamic symbol name")
            if strings[name_offset:end] == name.encode("ascii"):
                return True
    return False


def verify_property_library(data, abi):
    result = verify_native_library(data, abi, "JBinding property bridge", (PROPERTY_VERSION_SYMBOL,))
    if not exports_function(data, PROPERTY_VERSION_SYMBOL):
        raise RuntimeError("JBinding property bridge does not export its interface version for " + abi)
    # The APK runtime test also calls this entry point and checks the returned version.
    result.update(required_interface_version=PROPERTY_INTERFACE_VERSION,
                  interface_version_jni_export_checked=True)
    return result


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
    resources = run(args.build_tools / "aapt2", "dump", "resources", args.apk)
    if "me.zhanghai.android.files.viewer.text.SoraEditorActivity" not in tree:
        raise RuntimeError("Sora editor activity is missing from the APK manifest")
    if "me.zhanghai.android.files.viewer.text.TextEditorActivity" in tree:
        raise RuntimeError("The old text editor activity is still registered")
    for component in ("MediaPlayerActivity", "MediaPlaybackService"):
        if "me.zhanghai.android.files.media." + component not in tree:
            raise RuntimeError("Media player component missing: " + component)
    if "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" not in tree:
        raise RuntimeError("Media playback foreground service permission is missing")

    assets = Path(__file__).resolve().parents[1] / "app/src/main/assets/sora"
    provenance = json.loads((assets / "sources.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(args.apk) as archive:
        tracking_checks = verify_no_tracking(archive, tree, resources)
        names = set(archive.namelist())
        abis = sorted({name.split("/")[1] for name in names
                       if name.startswith("lib/") and name.endswith(".so")})
        min_sdk = verify_release_platform(badging, abis)
        bass_vendor = Path(__file__).resolve().parents[1] / "third_party/bass"
        bass_sources = json.loads((bass_vendor / "sources.json").read_text())
        bass_libraries = {}
        for record in bass_sources:
            for source, checksum in record["files"].items():
                if not source.endswith(".so"):
                    continue
                name = "lib/arm64-v8a/" + source.rsplit("/", 1)[-1]
                if name not in names or hashlib.sha256(archive.read(name)).hexdigest() != checksum:
                    raise RuntimeError("Official BASS library missing or changed: " + name)
                bass_libraries[name] = checksum
        bass_notice = (bass_vendor.parent.parent / "app/src/main/res/raw/license_bass.txt").read_bytes()
        # AAPT2 may shorten resource file paths in an optimized release APK.
        if ":raw/license_bass" not in resources or not any(
                archive.read(name) == bass_notice for name in names
                if name.startswith("res/") and name.endswith(".txt")):
            raise RuntimeError("BASS offline license notice is missing")
        engine_libraries = {}
        property_libraries = {}
        for abi in abis:
            engine_name = f"lib/{abi}/lib7-Zip-JBinding.so"
            if engine_name not in names:
                raise RuntimeError("The APK is missing the JBinding archive engine for " + abi)
            engine_libraries[abi] = verify_engine_library(archive.read(engine_name), abi)
            property_name = f"lib/{abi}/libarchive_jbinding.so"
            if property_name not in names:
                raise RuntimeError("The APK is missing the JBinding archive property bridge for " + abi)
            property_libraries[abi] = verify_property_library(archive.read(property_name), abi)
        if not any((JBINDING_VERSION + "\0").encode("ascii") in archive.read(name)
                   for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)):
            raise RuntimeError("The APK is missing the pinned JBinding " + JBINDING_VERSION + " bridge")
        if any(name.rsplit("/", 1)[-1] in ("libsevenzip.so", "libarchive-jni.so", "libarchive.so")
               for name in names):
            raise RuntimeError("The APK still includes a replaced archive engine")
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
        "min_sdk": min_sdk,
        "abis": abis,
        "archive_engine": "official 7-Zip " + ENGINE_VERSION,
        "archive_engine_abis_checked": abis,
        "archive_engine_libraries": engine_libraries,
        "archive_binding": "borisbrodski/sevenzipjbinding:" + JBINDING_VERSION,
        "archive_binding_source_commit": JBINDING_COMMIT,
        "archive_binding_dex_version_checked": True,
        "archive_property_bridge": "libarchive_jbinding.so",
        "archive_property_bridge_abis_checked": abis,
        "archive_property_bridge_libraries": property_libraries,
        "sora_activity_registered": True,
        "old_editor_activity_removed": True,
        "media_player_components_registered": True,
        "media_playback_foreground_permission_checked": True,
        "bass_official_library_checksums": bass_libraries,
        "bass_offline_notice_checked": True,
        "source_assets_verified": len(sources),
        "pinned_asset_checksums_verified": len(provenance["files"]),
        "jcodings_runtime_tables": tables,
        "tracking_sdk_checks": tracking_checks,
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

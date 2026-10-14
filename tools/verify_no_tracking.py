#!/usr/bin/env python3
"""Reject Firebase, Crashlytics and Google Play Services in a built APK."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile


SDK_NAMESPACES = (
    "com.google.firebase", "com.google.android.gms", "com.crashlytics", "io.fabric.sdk"
)
SDK_RESOURCES = (
    "google_app_id", "gcm_defaultSenderId", "firebase_database_url", "google_api_key",
    "google_crash_reporting_api_key", "google_storage_bucket"
)


def dex_class_names(data):
    """Read class definitions, not optional provider names in AndroidX/HTTP strings."""
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise RuntimeError("Invalid DEX header")
    string_count, string_offset = struct.unpack_from("<II", data, 56)
    type_count, type_offset = struct.unpack_from("<II", data, 64)
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    for count, offset, width in ((string_count, string_offset, 4),
                                 (type_count, type_offset, 4),
                                 (class_count, class_offset, 32)):
        if offset + count * width > len(data):
            raise RuntimeError("Truncated DEX table")
    for index in range(class_count):
        type_index = struct.unpack_from("<I", data, class_offset + index * 32)[0]
        if type_index >= type_count:
            raise RuntimeError("Invalid DEX class type")
        string_index = struct.unpack_from("<I", data, type_offset + type_index * 4)[0]
        if string_index >= string_count:
            raise RuntimeError("Invalid DEX class name")
        offset = struct.unpack_from("<I", data, string_offset + string_index * 4)[0]
        for _ in range(5):
            if offset >= len(data):
                raise RuntimeError("Truncated DEX string")
            value = data[offset]
            offset += 1
            if not value & 0x80:
                break
        else:
            raise RuntimeError("Invalid DEX string length")
        end = data.find(b"\0", offset)
        if end < 0:
            raise RuntimeError("Unterminated DEX class name")
        yield data[offset:end].decode("utf-8", errors="replace")


def verify_no_tracking(archive, manifest, resources):
    for namespace in SDK_NAMESPACES:
        if namespace in manifest:
            raise RuntimeError("Tracking SDK entry in APK manifest: " + namespace)
    for permission in ("android.permission.ACCESS_ADSERVICES_AD_ID",
                       "android.permission.ACCESS_ADSERVICES_ATTRIBUTION"):
        if permission in manifest:
            raise RuntimeError("Advertising permission in APK manifest: " + permission)
    for resource in SDK_RESOURCES:
        if re.search(r"\b" + resource + r"\b", resources):
            raise RuntimeError("Service configuration in APK resources: " + resource)

    dex_names = []
    class_count = 0
    for name in archive.namelist():
        lower_name = name.lower()
        if any(marker in lower_name for marker in
               ("firebase", "crashlytics", "play-services", "google-services")):
            raise RuntimeError("Tracking SDK file in APK: " + name)
        if not re.fullmatch(r"classes(?:\d+)?\.dex", name):
            continue
        dex_names.append(name)
        data = archive.read(name)
        for class_name in dex_class_names(data):
            class_count += 1
            for namespace in SDK_NAMESPACES:
                if class_name.startswith("L" + namespace.replace(".", "/") + "/"):
                    raise RuntimeError("Tracking SDK class in " + name + ": " + class_name)
    if not dex_names:
        raise RuntimeError("The APK contains no DEX code to inspect")
    return {
        "firebase_and_play_services_absent": True,
        "manifest_checked": True,
        "service_resources_checked": True,
        "packaged_files_checked": len(archive.namelist()),
        "dex_files_checked": len(dex_names),
        "class_definitions_checked": class_count,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, default=Path("aapt2"))
    args = parser.parse_args()

    def dump(*arguments):
        return subprocess.run([str(args.aapt2), "dump", *arguments, str(args.apk)],
                              check=True, text=True, capture_output=True).stdout

    manifest = dump("xmltree", "--file", "AndroidManifest.xml")
    resources = dump("resources")
    with zipfile.ZipFile(args.apk) as archive:
        report = verify_no_tracking(archive, manifest, resources)
    report["apk"] = str(args.apk)
    report["apk_sha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Compile and exercise the APK signing core against its published dependencies.

The cache defaults to ~/tmp to match the Termux project rules. No Android SDK,
desktop apksigner executable, production key or network service is used by the
tests. Only the first dependency download needs network access.
"""

import argparse
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = {
    "apksig-9.4.1.jar": (
        "https://dl.google.com/dl/android/maven2/com/android/tools/build/"
        "apksig/9.4.1/apksig-9.4.1.jar"
    ),
    **{
        f"{module}-1.86.jar": (
            f"https://repo.maven.apache.org/maven2/org/bouncycastle/{module}/"
            f"1.86/{module}-1.86.jar"
        )
        for module in ("bcprov-jdk15to18", "bcpkix-jdk15to18", "bcutil-jdk15to18")
    },
}
SHA256 = {
    "apksig-9.4.1.jar": "7ae2e5980c77d853e3513074ee7c822bbdcdcde1668889d17faa7fe8bc8aa821",
    "bcprov-jdk15to18-1.86.jar": "fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81",
    "bcpkix-jdk15to18-1.86.jar": "cf70d32101e53c3f92d55ba5c5f1329af51a9a44057fd21e81e20cfd6cd64953",
    "bcutil-jdk15to18-1.86.jar": "e72ef8a0fcad295207529b268ee02eb72acc7c83ec2e3a9df90cbed9380e3990",
}
FIXTURE_BASE = (
    "https://android.googlesource.com/platform/tools/apksig/+/"
    "184702d9d18877edf9e5296c4e191cf0aa2b5fbb/src/test/resources/com/android/apksig/"
)
FIXTURES = ("golden-aligned-in.apk", "golden-aligned-v1v2v3-out.apk")
FIXTURE_SHA256 = {
    "golden-aligned-in.apk": "0e896ce038fb093e1342f65e815ffe45c121ea0a61ebc46bdc48b775866a6185",
    "golden-aligned-v1v2v3-out.apk": "ba7828ba42a3b68bd3acff78773e41d6a62aabe6317538671441c568748d9cd7",
}


def fetch(url, destination):
    partial = destination.with_suffix(destination.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=45) as response:
            with partial.open("wb") as output:
                shutil.copyfileobj(response, output, length=128 * 1024)
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-apk-tools")
    parser.add_argument("--dependencies-only", action="store_true")
    args = parser.parse_args()
    deps = args.work_dir / "deps"
    deps.mkdir(parents=True, exist_ok=True)
    jars = []
    for name, url in ARTIFACTS.items():
        jar = deps / name
        if not jar.is_file():
            print(f"Downloading {name}", flush=True)
            fetch(url, jar)
        with jar.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if digest != SHA256[name]:
            raise SystemExit(f"Dependency checksum mismatch: {jar}")
        print(f"{name}: SHA-256 {digest}", flush=True)
        jars.append(jar)
    if args.dependencies_only:
        return
    fixtures = args.work_dir / "fixtures"
    fixtures.mkdir(exist_ok=True)
    for name in FIXTURES:
        fixture = fixtures / name
        if not fixture.is_file():
            print(f"Downloading AOSP fixture {name}", flush=True)
            with urllib.request.urlopen(FIXTURE_BASE + name + "?format=TEXT", timeout=45) as response:
                fixture.write_bytes(base64.b64decode(response.read(), validate=True))
        if hashlib.sha256(fixture.read_bytes()).hexdigest() != FIXTURE_SHA256[name]:
            raise SystemExit(f"AOSP fixture checksum mismatch: {fixture}")
    for executable in ("javac", "java", "keytool"):
        if not shutil.which(executable):
            raise SystemExit(f"Missing {executable}; install a JDK 21 or newer.")
    classes = args.work_dir / "classes"
    classes.mkdir(exist_ok=True)
    sources = sorted((ROOT / "app/src/main/java/me/zhanghai/android/files/apk/core")
                     .glob("*.java"))
    # The Android Apktool orchestration has a separate on-device suite. Keep this host suite on
    # the signing, binary-manifest and semantic-rewriter workers, without loading Android libraries.
    sources.append(ROOT / "app/src/main/java/me/zhanghai/android/files/apk/ApkManifestReader.java")
    tests = sorted(path for path in (ROOT / "tests").glob("Apk*Test.java")
                   if not path.name.endswith("AndroidTest.java"))
    if not sources or not tests:
        raise SystemExit("The APK signing sources or tests are missing.")
    classpath = os.pathsep.join(map(str, jars))
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(classes), *map(str, sources), *map(str, tests)], check=True)
    for test in tests:
        runs = args.work_dir / "test-runs"
        runs.mkdir(parents=True, exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix=test.stem + "-", dir=runs))
        print(f"Running {test.stem}", flush=True)
        subprocess.run(["java", "-Xmx512m", "-cp",
                        os.pathsep.join((str(classes), classpath)), test.stem, str(work), str(fixtures)],
                       check=True)


if __name__ == "__main__":
    main()

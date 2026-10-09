#!/usr/bin/env python3
"""Compile the real Java backend and verify its output with Commons Compress and official 7zz."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, default=Path.home() / "tmp/materialfiles-7z-check")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--seven-zip", default=shutil.which("7zz") or shutil.which("7z"))
    args = parser.parse_args()
    if not args.seven_zip:
        parser.error("Install official 7-Zip (7zz) to run the interoperability checks")
    root = Path(__file__).resolve().parents[1]
    args.work_dir.mkdir(parents=True, exist_ok=True)
    classes = args.work_dir / "classes"
    classes.mkdir(exist_ok=True)
    coordinates = [
        "org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar",
        "org/tukaani/xz/1.10/xz-1.10.jar",
        "commons-io/commons-io/2.20.0/commons-io-2.20.0.jar",
        "org/apache/commons/commons-lang3/3.18.0/commons-lang3-3.18.0.jar",
        "commons-codec/commons-codec/1.19.0/commons-codec-1.19.0.jar",
    ]
    jars = []
    for coordinate in coordinates:
        url = "https://repo.maven.apache.org/maven2/" + coordinate
        jar = args.work_dir / coordinate.rsplit("/", 1)[1]
        checksum_url = url + ".sha1"
        with urllib.request.urlopen(checksum_url, timeout=30) as response:
            expected = response.read().decode().split()[0]
        if not jar.exists():
            with urllib.request.urlopen(url, timeout=30) as response:
                jar.write_bytes(response.read())
        if hashlib.sha1(jar.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Maven checksum mismatch: " + str(jar))
        jars.append(str(jar))
    classpath = os.pathsep.join(jars)
    java = str(args.java_home / "bin/java") if args.java_home else "java"
    javac = str(args.java_home / "bin/javac") if args.java_home else "javac"
    sources = [
        root / "app/src/main/java/org/apache/commons/compress/archivers/sevenz/AndroidSevenZEncryption.java",
        root / "app/src/main/java/me/zhanghai/android/files/provider/archive/archiver/EncryptedSevenZOutputFile.java",
        root / "tests/EncryptedSevenZInteropTest.java",
    ]
    subprocess.run([javac, "--release", "11", "-cp", classpath, "-d", str(classes), *map(str, sources)], check=True)
    fixtures = args.work_dir / "fixtures"
    subprocess.run([java, "-Xmx96m", "-cp", str(classes) + os.pathsep + classpath,
                    "EncryptedSevenZInteropTest", str(fixtures)], check=True)
    password = "测试-password-🔐"
    name = "文档/秘密名称.txt"
    content = "加密压缩互操作测试\nhello 7z\n".encode()
    for filename in ["contents.7z", "headers.7z", "empty.7z", "sequential.7z"]:
        archive = fixtures / filename
        result = subprocess.run([args.seven_zip, "t", "-p" + password, str(archive)],
                                capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        wrong = subprocess.run([args.seven_zip, "t", "-pwrong-password", str(archive)],
                               capture_output=True, timeout=60)
        assert wrong.returncode != 0, filename + " accepted an incorrect password"
        missing = subprocess.run([args.seven_zip, "t", str(archive)], stdin=subprocess.DEVNULL,
                                 capture_output=True, timeout=60)
        assert missing.returncode != 0, filename + " accepted a missing password"
        if filename != "empty.7z":
            extracted = subprocess.run([args.seven_zip, "x", "-so", "-p" + password,
                                        str(archive), name], capture_output=True, check=True, timeout=60)
            assert extracted.stdout == content, filename + " content differs after extraction"
            growing = subprocess.run([args.seven_zip, "x", "-so", "-p" + password,
                                      str(archive), "growing"], capture_output=True, check=True, timeout=60)
            assert growing.stdout == content, "Growing source was not encrypted correctly"
    listed = subprocess.run([args.seven_zip, "l", "-pwrong-password", str(fixtures / "contents.7z")],
                            capture_output=True, text=True, check=True, timeout=60)
    assert name in listed.stdout, "Content-only encryption hides the names unexpectedly"
    hidden = subprocess.run([args.seven_zip, "l", "-pwrong-password", str(fixtures / "headers.7z")],
                            capture_output=True, text=True, timeout=60)
    assert hidden.returncode != 0 and name not in hidden.stdout, "Encrypted headers exposed names"
    visible_without_password = subprocess.run([args.seven_zip, "l", str(fixtures / "contents.7z")],
                                              stdin=subprocess.DEVNULL, capture_output=True,
                                              text=True, check=True, timeout=60)
    assert name in visible_without_password.stdout, "Content-only names need a password"
    hidden_without_password = subprocess.run([args.seven_zip, "l", str(fixtures / "headers.7z")],
                                             stdin=subprocess.DEVNULL, capture_output=True,
                                             text=True, timeout=60)
    assert hidden_without_password.returncode != 0 and name not in hidden_without_password.stdout
    large = subprocess.run([args.seven_zip, "x", "-so", "-p" + password,
                            str(fixtures / "headers.7z"), "large.bin"], capture_output=True,
                           check=True, timeout=60)
    block = bytes((i * 37) & 255 for i in range(8192))
    assert large.stdout == block * 2048, "Streamed file differs after extraction"
    print("7zz checks passed: passwords, file-name visibility, Unicode and streamed extraction.")
    print("Verified fixtures:", fixtures)


if __name__ == "__main__":
    main()

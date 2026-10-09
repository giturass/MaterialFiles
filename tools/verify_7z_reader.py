#!/usr/bin/env python3
"""Run the real compiled Kotlin 7z reader against archives made by official 7zz.

First run :app:compileDebugKotlin (or assembleDebug). This tool reads that output
and the dependency cache, compiles a JVM harness, and runs it with a 96 MiB heap.
It substitutes only android.system.OsConstants and the file-provider boundary;
the reader, exceptions, entry model and permission conversion are actual app code.
Android document-provider opening, native libarchive and UI are outside this test.

Example:
  python3 tools/verify_7z_reader.py --gradle-home "$GRADLE_USER_HOME" \
    --android-jar "$ANDROID_HOME/platforms/android-37.2/android.jar" \
    --seven-zip /path/to/7zz --writer-fixtures ~/tmp/materialfiles-7z-check/fixtures
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


PASSWORD = "测试-password-🔐"
NAME = "文档/秘密名称.txt"
CONTENT = "加密压缩互操作测试\nhello 7z\n".encode()

# The Android SDK's android.jar contains throwing method stubs, including S_IS*.
# These are the platform POSIX masks; no application/archive classes are stubbed.
OS_CONSTANTS = """package android.system;
public final class OsConstants {
    public static final int S_IFMT = 0170000, S_IFSOCK = 0140000, S_IFLNK = 0120000,
        S_IFREG = 0100000, S_IFBLK = 0060000, S_IFDIR = 0040000, S_IFCHR = 0020000,
        S_IFIFO = 0010000, S_ISUID = 04000, S_ISGID = 02000, S_ISVTX = 01000,
        S_IRUSR = 0400, S_IWUSR = 0200, S_IXUSR = 0100, S_IRWXU = 0700,
        S_IRGRP = 0040, S_IWGRP = 0020, S_IXGRP = 0010, S_IRWXG = 0070,
        S_IROTH = 0004, S_IWOTH = 0002, S_IXOTH = 0001, S_IRWXO = 0007;
    public static boolean S_ISDIR(int mode) { return (mode & S_IFMT) == S_IFDIR; }
    public static boolean S_ISCHR(int mode) { return (mode & S_IFMT) == S_IFCHR; }
    public static boolean S_ISBLK(int mode) { return (mode & S_IFMT) == S_IFBLK; }
    public static boolean S_ISREG(int mode) { return (mode & S_IFMT) == S_IFREG; }
    public static boolean S_ISFIFO(int mode) { return (mode & S_IFMT) == S_IFIFO; }
    public static boolean S_ISLNK(int mode) { return (mode & S_IFMT) == S_IFLNK; }
    public static boolean S_ISSOCK(int mode) { return (mode & S_IFMT) == S_IFSOCK; }
}
"""


def cached_artifact(gradle_home, group, artifact, version, extension="jar"):
    directory = gradle_home / "caches/modules-2/files-2.1" / group / artifact
    if version is None:
        versions = sorted(directory.iterdir(), key=lambda p: tuple(map(int, p.name.split("."))))
        version = versions[-1].name
    matches = list((directory / version).glob(f"*/{artifact}-{version}.{extension}"))
    if len(matches) != 1:
        raise RuntimeError(f"Expected cached {group}:{artifact}:{version} ({extension}); "
                           "build the app or pass the correct --gradle-home")
    return matches[0]


def run_7zz(seven_zip, *arguments, cwd=None, successful=True):
    result = subprocess.run([seven_zip, *arguments], cwd=cwd, capture_output=True,
                            text=True, stdin=subprocess.DEVNULL, timeout=60)
    if successful and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result


def fixtures(seven_zip, directory):
    source = directory / "source"
    source.mkdir()
    (source / "文档").mkdir(mode=0o750)
    (source / NAME).write_bytes(CONTENT)
    (source / NAME).chmod(0o640)
    (source / "empty").touch(mode=0o600)
    (source / "empty-directory").mkdir(mode=0o750)
    (source / "link").symlink_to(NAME)
    (source / "executable.sh").write_bytes(b"#!/bin/sh\nexit 0\n")
    (source / "executable.sh").chmod(0o750)
    (source / "large.bin").write_bytes(bytes((i * 37) & 255 for i in range(8192)) * 128)
    for path in source.rglob("*"):
        os.utime(path, (1700000000, 1700000000), follow_symlinks=False)
    for name, method, headers in [("headers", "LZMA2:d=4m", "on"),
                                  ("contents", "LZMA2:d=4m", "off"),
                                  ("copy", "Copy", "off")]:
        archive = directory / f"official-{name}.7z"
        result = run_7zz(seven_zip, "a", "-t7z", "-m0=" + method, "-ms=on", "-snl",
                        "-mhe=" + headers, "-p" + PASSWORD, str(archive), ".", cwd=source)
        if name == "headers":
            print(next(line for line in result.stdout.splitlines() if line.startswith("7-Zip")),
                  flush=True)
        run_7zz(seven_zip, "t", "-p" + PASSWORD, str(archive))
        wrong = run_7zz(seven_zip, "t", "-pwrong-password", str(archive), successful=False)
        if wrong.returncode == 0:
            raise AssertionError("Official 7zz accepted incorrect password for " + name)
        listing = run_7zz(seven_zip, "l", "-pwrong-password", str(archive), successful=False)
        if headers == "on":
            if listing.returncode == 0 or NAME in listing.stdout:
                raise AssertionError("Official encrypted header exposed file names")
        elif listing.returncode or NAME not in listing.stdout:
            raise AssertionError("Official content encryption unexpectedly hid file names")


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, default=Path.home() / "tmp/materialfiles-7z-reader")
    parser.add_argument("--gradle-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--app-classes", type=Path, default=root /
                        "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    parser.add_argument("--seven-zip", default=shutil.which("7zz"))
    parser.add_argument("--writer-fixtures", type=Path,
                        help="Also test the encrypted archives generated by tools/verify_7z.py")
    args = parser.parse_args()
    if not args.seven_zip:
        parser.error("Install official 7-Zip (7zz), or specify --seven-zip")
    reader = Path("me/zhanghai/android/files/provider/archive/archiver/SevenZArchiveReader")
    compiled_reader = args.app_classes / reader.with_suffix(".class")
    source_reader = root / "app/src/main/java" / reader.with_suffix(".kt")
    if not compiled_reader.is_file() or compiled_reader.stat().st_mtime < source_reader.stat().st_mtime:
        parser.error("SevenZArchiveReader.class is missing or older than its source; "
                     "run :app:compileDebugKotlin first")
    if not args.android_jar.is_file():
        parser.error("--android-jar must point to the Android SDK platform android.jar")
    args.work_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir)).resolve()
    classes = work / "classes"
    classes.mkdir()
    kotlin_version = re.search(r"kotlin_version\s*=\s*'([^']+)'",
                               (root / "build.gradle").read_text()).group(1)
    coordinates = [
        ("org.jetbrains.kotlin", "kotlin-stdlib", kotlin_version),
        ("org.apache.commons", "commons-compress", "1.28.0"),
        ("org.tukaani", "xz", "1.10"),
        ("commons-io", "commons-io", None),
        ("org.apache.commons", "commons-lang3", "3.18.0"),
        ("commons-codec", "commons-codec", "1.19.0"),
    ]
    jars = [cached_artifact(args.gradle_home, *coordinate) for coordinate in coordinates]
    for group, version in [("me.zhanghai.android.retrofile", "1.2.0"),
                           ("me.zhanghai.android.libarchive", "1.1.7")]:
        aar = cached_artifact(args.gradle_home, group, "library", version, "aar")
        destination = work / f"{group}-{version}.jar"
        with zipfile.ZipFile(aar) as archive:
            destination.write_bytes(archive.read("classes.jar"))
        jars.append(destination)
    classpath = os.pathsep.join(map(str, [classes, args.app_classes, args.android_jar, *jars]))
    stub = work / "OsConstants.java"
    stub.write_text(OS_CONSTANTS)
    java = str(args.java_home / "bin/java") if args.java_home else "java"
    javac = str(args.java_home / "bin/javac") if args.java_home else "javac"
    subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(classes), str(stub), str(root / "tests/SevenZArchiveReaderInteropTest.java")],
                   check=True)
    fixture_dir = work / "fixtures"
    fixture_dir.mkdir()
    fixtures(args.seven_zip, fixture_dir)
    print("Compiled application classes:", args.app_classes, flush=True)
    print("Reproducible fixture directory:", fixture_dir, flush=True)
    command = [java, "-Xmx96m", "-cp", classpath, "SevenZArchiveReaderInteropTest", str(fixture_dir)]
    if args.writer_fixtures:
        command.append(str(args.writer_fixtures))
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=180)
    (work / "results.log").write_text(result.stdout)
    print(result.stdout, end="", flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()

"""Shared official fixtures and Android APK harness runner for archive regression tests."""

import hashlib
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import zipfile

from verify_sora_editor import cached_artifact, run


PASSWORD = "测试-password-🔐"
NAME = "文档/秘密名称.txt"
CONTENT = "加密压缩互操作测试\nhello 7z\n".encode()


def run_7zz(seven_zip, *arguments, cwd=None, successful=True):
    result = subprocess.run([str(seven_zip), *map(str, arguments)], cwd=cwd, capture_output=True,
                            text=True, stdin=subprocess.DEVNULL, timeout=120)
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
                        "-mhe=" + headers, "-p" + PASSWORD, archive, ".", cwd=source)
        if name == "headers":
            print(next(line for line in result.stdout.splitlines() if line.startswith("7-Zip")),
                  flush=True)
        run_7zz(seven_zip, "t", "-p" + PASSWORD, archive)
        wrong = run_7zz(seven_zip, "t", "-pwrong-password", archive, successful=False)
        if wrong.returncode == 0:
            raise AssertionError("Official 7zz accepted incorrect password for " + name)
        listing = run_7zz(seven_zip, "l", "-pwrong-password", archive, successful=False)
        if headers == "on":
            if listing.returncode == 0 or NAME in listing.stdout:
                raise AssertionError("Official encrypted header exposed file names")
        elif listing.returncode or NAME not in listing.stdout:
            raise AssertionError("Official content encryption unexpectedly hid file names")


def add_apk_arguments(parser, root, work_name):
    parser.add_argument("--apk", type=Path,
                        default=root / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--work-dir", type=Path, default=Path.home() / "tmp" / work_name)
    parser.add_argument("--fixtures", type=Path, help="Reuse official fixtures from a previous run")
    parser.add_argument("--seven-zip", default=shutil.which("7zz"))
    parser.add_argument("--gradle-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path)
    parser.add_argument("--app-process", type=Path, default=Path("/system/bin/app_process"))


class ApkHarness:
    def __init__(self, args, parser, root, need_fixtures=True):
        self.args = args
        self.root = root
        for name in ("apk", "android_jar", "app_process"):
            if not getattr(args, name).is_file():
                parser.error("Missing --" + name.replace("_", "-") + ": " + str(getattr(args, name)))
        if need_fixtures and args.fixtures is None and not args.seven_zip:
            parser.error("Specify --fixtures or install official 7zz (or pass --seven-zip)")
        if args.d8_jar is None:
            build_tools = re.search(r"buildToolsVersion\s*=\s*'([^']+)'",
                                    (root / "app/build.gradle").read_text()).group(1)
            args.d8_jar = args.android_jar.resolve().parents[2] / "build-tools" / build_tools / "lib/d8.jar"
        if not args.d8_jar.is_file():
            parser.error("SDK D8 was not found; specify --d8-jar")
        self.java = args.java_home / "bin/java" if args.java_home else shutil.which("java")
        self.javac = args.java_home / "bin/javac" if args.java_home else shutil.which("javac")
        if not self.java or not self.javac:
            parser.error("Use a JDK 17 or newer, or specify --java-home")
        retrofile = cached_artifact(args.gradle_home, "me.zhanghai.android.retrofile",
                                   "library", "1.2.0", "aar")
        args.work_dir.mkdir(parents=True, exist_ok=True)
        self.work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir)).resolve()
        print("Verification logs:", self.work, flush=True)
        self.fixture_dir = args.fixtures.resolve() if args.fixtures else self.work / "fixtures"
        if need_fixtures:
            if args.fixtures is None:
                self.fixture_dir.mkdir()
                fixtures(args.seven_zip, self.fixture_dir)
            for name in ("official-headers.7z", "official-contents.7z", "official-copy.7z", "source"):
                if not (self.fixture_dir / name).exists():
                    parser.error("Missing fixture: " + str(self.fixture_dir / name))
        self.retrofile_jar = self.work / "retrofile.jar"
        with zipfile.ZipFile(retrofile) as archive:
            self.retrofile_jar.write_bytes(archive.read("classes.jar"))
        self.apk = self.work / "application.apk"
        shutil.copyfile(args.apk, self.apk)
        self.apk.chmod(0o400)
        self.native = self.work / "lib"
        self.native.mkdir()
        abi = {"aarch64": "arm64-v8a", "armv7l": "armeabi-v7a",
               "x86_64": "x86_64", "i686": "x86"}.get(platform.machine())
        if not abi:
            parser.error("Unsupported Android CPU: " + platform.machine())
        with zipfile.ZipFile(self.apk) as archive:
            for name in archive.namelist():
                if name.startswith(f"lib/{abi}/") and name.endswith(".so"):
                    (self.native / Path(name).name).write_bytes(archive.read(name))
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        print("APK:", args.apk.resolve(), flush=True)
        print("APK SHA-256:", digest, flush=True)
        (self.work / "apk.sha256").write_text(digest + "  " + str(args.apk.resolve()) + "\n")

    def run(self, main_class, arguments, completion, native_binding=False, timeout=240,
            extra_sources=()):
        work = self.work / main_class
        work.mkdir()
        classes = work / "classes"
        classes.mkdir()
        sources = [self.root / "tests" / (main_class + ".java")]
        sources += [self.root / "tests" / (name + ".java") for name in extra_sources]
        if native_binding:
            # Compile-time type information only: the harness JAR excludes these classes.
            sources.append(self.root / "app/src/main/java/me/zhanghai/android/files/provider/"
                           "archive/archiver/NativeSevenZip.java")
            keep = work / "Keep.java"
            keep.write_text("package androidx.annotation; public @interface Keep {}\n")
            sources.append(keep)
        compile_env = os.environ.copy()
        compile_env["TMPDIR"] = str(work)
        classpath = os.pathsep.join(map(str, [self.args.android_jar, self.retrofile_jar]))
        run([self.javac, f"-J-Djava.io.tmpdir={work}", "--release", "8", "-encoding", "UTF-8",
             "-cp", classpath, "-d", classes, *sources], work, "javac", env=compile_env)
        harness_jar = work / "test.jar"
        compile_jar = work / "compile-only.jar"
        with zipfile.ZipFile(harness_jar, "w", zipfile.ZIP_DEFLATED) as harness, \
                zipfile.ZipFile(compile_jar, "w", zipfile.ZIP_DEFLATED) as support:
            for path in sorted(classes.rglob("*.class")):
                name = path.relative_to(classes).as_posix()
                destination = support if name.startswith(("me/", "androidx/")) else harness
                destination.write(path, name)
        dex_jar = work / "test-dex.jar"
        run([self.java, f"-Djava.io.tmpdir={work}", "-Xmx512m", "-cp", self.args.d8_jar,
             "com.android.tools.r8.D8", "--min-api", "23", "--lib", self.args.android_jar,
             "--classpath", self.retrofile_jar, "--classpath", compile_jar,
             "--output", dex_jar, harness_jar], work, "d8", env=compile_env)
        dex_jar.chmod(0o400)
        runtime_env = os.environ.copy()
        runtime_env["CLASSPATH"] = os.pathsep.join(map(str, [dex_jar, self.apk]))
        runtime_env["TMPDIR"] = str(work)
        runtime_env.pop("LD_LIBRARY_PATH", None)
        runtime_env.pop("LD_PRELOAD", None)
        output = run([self.args.app_process, "-Xcheck:jni", f"-Djava.library.path={self.native}",
                      "/system/bin", main_class, *arguments], work, "results",
                     timeout=timeout, env=runtime_env)
        print(output, end="", flush=True)
        if not re.search(completion, output):
            raise RuntimeError("Android runtime did not report test completion; see "
                               + str(work / "results.log"))
        return output

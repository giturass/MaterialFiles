#!/usr/bin/env python3
"""Check encrypted 7z extraction's password flow using an APK's real DEX on Android.

Runs an unminified APK's reader, archive filesystem, password-error translation and dialog Intent
without installing the APK or launching an Activity. The harness supplies physical
file access and resource strings; archive metadata comes from the APK's reader.
It checks missing/wrong passwords, a late Copy-codec CRC failure, the dialog's
ArchivePath operations and retrying extraction after storing the correct password.
It does not exercise directory discovery through native libarchive or render UI.

Example (with the same JDK environment as the project build):
  python3 tools/verify_7z_password_flow.py \
    --gradle-home ~/tmp/materialfiles-check/gradle \
    --android-jar /path/to/sdk/platforms/android-37.2/android.jar \
    --apk app/build/outputs/apk/debug/app-debug.apk \
    --fixtures ~/tmp/materialfiles-7z-reader/run-example/fixtures

Omit --fixtures to generate official 7zz fixtures. All generated files and logs
are retained in ~/tmp/materialfiles-7z-password-flow. An older APK can be supplied
to verify that the regression test fails before the fix.
"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True

from verify_7z_reader import cached_artifact, fixtures
from verify_sora_editor import run


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path,
                        default=root / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--work-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-7z-password-flow")
    parser.add_argument("--fixtures", type=Path,
                        help="Reuse a fixture directory made by verify_7z_reader.py")
    parser.add_argument("--seven-zip", default=shutil.which("7zz"))
    parser.add_argument("--gradle-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path)
    parser.add_argument("--app-process", type=Path, default=Path("/system/bin/app_process"))
    args = parser.parse_args()
    for name in ("apk", "android_jar", "app_process"):
        if not getattr(args, name).is_file():
            parser.error("Missing --" + name.replace("_", "-") + ": " + str(getattr(args, name)))
    if args.fixtures is None and not args.seven_zip:
        parser.error("Specify --fixtures or install official 7zz (or pass --seven-zip)")
    if args.d8_jar is None:
        build_tools = re.search(r"buildToolsVersion\s*=\s*'([^']+)'",
                                (root / "app/build.gradle").read_text()).group(1)
        args.d8_jar = args.android_jar.resolve().parents[2] / "build-tools" / build_tools / "lib/d8.jar"
    if not args.d8_jar.is_file():
        parser.error("SDK D8 was not found; specify --d8-jar")
    java = args.java_home / "bin/java" if args.java_home else shutil.which("java")
    javac = args.java_home / "bin/javac" if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("Use a JDK 17 or newer, or specify --java-home")
    try:
        retrofile = cached_artifact(args.gradle_home, "me.zhanghai.android.retrofile",
                                   "library", "1.2.0", "aar")
    except RuntimeError as error:
        parser.error(str(error))

    args.work_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir)).resolve()
    print("Verification logs:", work, flush=True)
    fixture_dir = args.fixtures.resolve() if args.fixtures else work / "fixtures"
    if args.fixtures is None:
        fixture_dir.mkdir()
        fixtures(args.seven_zip, fixture_dir)
    for name in ("official-headers.7z", "official-contents.7z", "official-copy.7z", "source"):
        if not (fixture_dir / name).exists():
            parser.error("Missing reader fixture: " + str(fixture_dir / name))

    # Only the harness is compiled here; every application/dependency class is loaded from the
    # supplied APK, including Commons Compress and the exact desugared Java library shipped in it.
    retrofile_jar = work / "retrofile.jar"
    with zipfile.ZipFile(retrofile) as archive:
        retrofile_jar.write_bytes(archive.read("classes.jar"))
    classes = work / "classes"
    classes.mkdir()
    compile_env = os.environ.copy()
    compile_env["TMPDIR"] = str(work)
    classpath = os.pathsep.join(map(str, [args.android_jar, retrofile_jar]))
    run([javac, f"-J-Djava.io.tmpdir={work}", "--release", "8", "-encoding", "UTF-8",
         "-cp", classpath, "-d", classes, root / "tests/ArchivePasswordFlowTest.java"],
        work, "javac", env=compile_env)
    harness_jar = work / "password-flow-test.jar"
    with zipfile.ZipFile(harness_jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes).as_posix())
    dex_jar = work / "password-flow-test-dex.jar"
    run([java, f"-Djava.io.tmpdir={work}", "-Xmx512m", "-cp", args.d8_jar,
         "com.android.tools.r8.D8", "--min-api", "24", "--lib", args.android_jar,
         "--classpath", retrofile_jar, "--output", dex_jar, harness_jar],
        work, "d8", env=compile_env)
    apk = work / "application.apk"
    shutil.copyfile(args.apk, apk)
    # Android requires dynamically loaded DEX to be read-only. Leave the original APK untouched.
    apk.chmod(0o400)
    dex_jar.chmod(0o400)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    print("APK:", args.apk.resolve(), flush=True)
    print("APK SHA-256:", digest, flush=True)
    (work / "apk.sha256").write_text(digest + "  " + str(args.apk.resolve()) + "\n")
    runtime_env = os.environ.copy()
    runtime_env["CLASSPATH"] = os.pathsep.join(map(str, [dex_jar, apk]))
    runtime_env["TMPDIR"] = str(work)
    runtime_env.pop("LD_LIBRARY_PATH", None)
    runtime_env.pop("LD_PRELOAD", None)
    output = run([args.app_process, "/system/bin", "ArchivePasswordFlowTest", fixture_dir],
                 work, "results", timeout=180, env=runtime_env)
    print(output, end="", flush=True)
    if "PASS: 7 APK password-flow checks" not in output:
        raise RuntimeError("Android runtime did not report test completion; see "
                           + str(work / "results.log"))


if __name__ == "__main__":
    main()

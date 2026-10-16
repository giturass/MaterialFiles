#!/usr/bin/env python3
"""Run APK signing checks on Android ART without installing or launching an app.

By default only the Java test harness is converted to DEX; its signing classes,
apksig, Bouncy Castle and runtime resources come from the actual built debug APK.
Use --standalone only for a preliminary run of freshly compiled core sources and
the pinned libraries. Reports distinguish these modes explicitly. Neither mode
tests UI behavior, app installation/startup, cloning, or an Android API 23 device.

Build the debug APK first and prepare the public fixtures/dependencies with
tools/verify_apk_signing.py. All generated keys are disposable public test data.
This script performs no downloads and stores diagnostics under ~/tmp.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

from verify_apk_signing import FIXTURES, SHA256


ROOT = Path(__file__).resolve().parents[1]
CORE_PACKAGE = Path("me/zhanghai/android/files/apk/core")
MANIFEST_CLASS = Path("me/zhanghai/android/files/apk/ApkManifestReader")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command, work, name, commands, env, timeout=240):
    command = list(map(str, command))
    commands.append(command)
    (work / "commands.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
    log = work / f"{name}.log"
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, text=True, env=env, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        log.write_text(output, encoding="utf-8")
        raise RuntimeError(f"{name} exceeded {timeout} seconds; see {log}") from error
    log.write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        print(result.stdout, end="", flush=True)
        raise RuntimeError(f"{name} failed with exit code {result.returncode}; see {log}")
    return result.stdout


def classes_jar(path, directory, files=None):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for entry in sorted(files or directory.rglob("*.class")):
            archive.write(entry, entry.relative_to(directory).as_posix())


def add_runtime_resources(dex, jars):
    # D8 writes code only. BC also reads resources through its own class loader.
    with zipfile.ZipFile(dex, "a", zipfile.ZIP_DEFLATED) as output:
        existing = set(output.namelist())
        for jar in jars:
            with zipfile.ZipFile(jar) as source:
                for entry in source.infolist():
                    name = entry.filename
                    if (entry.is_dir() or name.endswith((".class", ".dex")) or
                            (name.startswith("META-INF/") and
                             not name.startswith("META-INF/services/"))):
                        continue
                    data = source.read(entry)
                    if name in existing:
                        if output.read(name) != data:
                            raise RuntimeError("Conflicting dependency resource: " + name)
                        continue
                    output.writestr(name, data)
                    existing.add(name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-apk-art-check")
    parser.add_argument("--dependencies", type=Path,
                        default=Path.home() / "tmp/materialfiles-apk-tools/deps")
    parser.add_argument("--fixtures", type=Path,
                        default=Path.home() / "tmp/materialfiles-apk-tools/fixtures")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path)
    parser.add_argument("--apk", type=Path,
                        default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--app-classes", type=Path, default=ROOT /
                        "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
    parser.add_argument("--resource-symbols", type=Path, default=ROOT /
                        "app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt")
    parser.add_argument("--app-process", type=Path, default=Path("/system/bin/app_process"))
    parser.add_argument("--jks", type=Path,
                        help="Optional disposable JKS fixture produced by ApkSigningCoreTest")
    parser.add_argument("--standalone", action="store_true",
                        help="Explicitly test source DEX instead of the built APK (preliminary)")
    args = parser.parse_args()

    java = args.java_home / "bin/java" if args.java_home else shutil.which("java")
    javac = args.java_home / "bin/javac" if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("A JDK 17+ is required; specify --java-home")
    if not args.android_jar.is_file() or not args.app_process.is_file():
        parser.error("Use an Android SDK android.jar and run on Android/Termux")
    if args.d8_jar is None:
        text = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        version = re.search(r"buildToolsVersion\s*=\s*'([^']+)'", text).group(1)
        args.d8_jar = args.android_jar.resolve().parents[2] / "build-tools" / version / "lib/d8.jar"
    if not args.d8_jar.is_file():
        parser.error("D8 was not found; specify --d8-jar")
    jars = [args.dependencies / name for name in SHA256]
    for jar in jars:
        if not jar.is_file() or sha256(jar) != SHA256[jar.name]:
            parser.error(f"Missing or incorrect pinned dependency: {jar}; run verify_apk_signing.py")
    for name in FIXTURES:
        if not (args.fixtures / name).is_file():
            parser.error("Missing AOSP fixture " + name + "; run verify_apk_signing.py first")
    if args.jks and not args.jks.is_file():
        parser.error("--jks must point to the disposable host test fixture")

    sources = sorted((ROOT / "app/src/main/java" / CORE_PACKAGE).glob("*.java"))
    sources.append(ROOT / "app/src/main/java" / MANIFEST_CLASS.with_suffix(".java"))
    tests = [ROOT / "tests/ApkSigningAndroidTest.java"]
    runtime_sources = sources.copy()
    resource_ids = {}
    if not args.standalone:
        tests.append(ROOT / "tests/ApkOutputRecoveryAndroidTest.java")
        runtime_sources += [ROOT / "app/src/main/java/me/zhanghai/android/files/apk" / filename
                            for filename in ("ApkOperations.kt", "ApkOperationModels.kt")]
        if not args.apk.is_file():
            parser.error("Build :app:assembleDebug first, or explicitly choose --standalone")
        for source in sources:
            relative = source.relative_to(ROOT / "app/src/main/java")
            compiled = args.app_classes / relative.with_suffix(".class")
            if (not compiled.is_file() or compiled.stat().st_mtime < source.stat().st_mtime or
                    args.apk.stat().st_mtime < compiled.stat().st_mtime):
                parser.error("APK/core classes are missing or stale; build :app:assembleDebug first: "
                             + str(source))
        if any(args.apk.stat().st_mtime < source.stat().st_mtime for source in runtime_sources):
            parser.error("The APK predates its signing/output-recovery sources; rebuild it first")
        if not args.resource_symbols.is_file():
            parser.error("Missing runtime resource symbols; build :app:assembleDebug first")
        symbols = args.resource_symbols.read_text(encoding="utf-8")
        for kind, name in (("string", "apk_signing_title"), ("layout", "apk_signing_dialog")):
            match = re.search(r"^int " + kind + " " + name + r" (0x[0-9a-fA-F]+)$", symbols, re.M)
            if not match:
                parser.error("Runtime R.txt is missing " + name)
            resource_ids[name] = int(match[1], 16)

    args.work_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir)).resolve()
    print("ART validation mode:", "source DEX (preliminary)" if args.standalone else "built APK", flush=True)
    print("ART validation logs:", work, flush=True)
    commands = []
    report = {
        "mode": "source_dex" if args.standalone else "packaged_apk",
        "scope": "Android ART core signing/verification; no installation, startup, UI, cloning, or API 23 device test",
        "dependencies": {jar.name: sha256(jar) for jar in jars},
        "sources": {str(source.relative_to(ROOT)): sha256(source) for source in runtime_sources},
        "tests": {str(source.relative_to(ROOT)): sha256(source) for source in tests},
        "fixtures": {name: sha256(args.fixtures / name) for name in FIXTURES},
        "status": "running",
    }
    compile_env = os.environ.copy()
    compile_env["TMPDIR"] = str(work)
    try:
        api_classes = work / "api-classes" if args.standalone else args.app_classes
        if args.standalone:
            api_classes.mkdir()
            run([javac, f"-J-Djava.io.tmpdir={work}", "--release", "17", "-encoding", "UTF-8",
                 "-cp", os.pathsep.join(map(str, jars)), "-d", api_classes, *sources],
                work, "javac-core", commands, compile_env)
        harness_classes = work / "test-classes"
        harness_classes.mkdir()
        classpath = os.pathsep.join(map(str, [api_classes, args.android_jar, *jars]))
        run([javac, f"-J-Djava.io.tmpdir={work}", "--release", "17", "-encoding", "UTF-8",
             "-cp", classpath, "-d", harness_classes, *tests],
            work, "javac-harness", commands, compile_env)
        harness_jar = work / "apk-signing-tests.jar"
        classes_jar(harness_jar, harness_classes)
        api_jar = work / "apk-signing-api.jar"
        files = list((api_classes / CORE_PACKAGE).glob("*.class"))
        files += list((api_classes / MANIFEST_CLASS.parent).glob("ApkManifestReader*.class"))
        classes_jar(api_jar, api_classes, files)
        dex = work / "apk-signing-test-dex.jar"
        d8 = [java, f"-Djava.io.tmpdir={work}", "-Xmx768m", "-cp", args.d8_jar,
              "com.android.tools.r8.D8", "--min-api", "30", "--lib", args.android_jar,
              "--output", dex]
        if args.standalone:
            d8 += [harness_jar, api_jar, *jars]
        else:
            for entry in [api_jar, *jars]:
                d8 += ["--classpath", entry]
            d8.append(harness_jar)
        run(d8, work, "d8", commands, compile_env)
        if args.standalone:
            add_runtime_resources(dex, jars)
        dex.chmod(0o400)

        runtime_env = os.environ.copy()
        runtime_env.pop("LD_LIBRARY_PATH", None)
        runtime_env.pop("LD_PRELOAD", None)
        runtime_env["TMPDIR"] = str(work)
        runtime_env["CLASSPATH"] = str(dex)
        actual_apk = "-"
        if not args.standalone:
            runtime_apk = work / "materialfiles-under-test.apk"
            shutil.copyfile(args.apk, runtime_apk)
            runtime_apk.chmod(0o400)
            report["apk_sha256"] = sha256(runtime_apk)
            report["apk_source"] = str(args.apk.resolve())
            report["apk_bytes"] = runtime_apk.stat().st_size
            runtime_env["CLASSPATH"] = os.pathsep.join((str(runtime_apk), str(dex)))
            actual_apk = str(runtime_apk)
        report["runtime_classpath"] = runtime_env["CLASSPATH"]
        output = run([args.app_process, "/system/bin", "ApkSigningAndroidTest", work,
                      args.fixtures.resolve(), args.jks.resolve() if args.jks else "-", actual_apk],
                     work, "art-results", commands, runtime_env, timeout=360)
        print(output, end="", flush=True)
        android = re.search(r"ANDROID_RUNTIME api=(\d+) release=(\S+) vm=(\S+)", output)
        summary = re.search(r"ART APK signing: (\d+)/(\d+) checks passed", output)
        if not android or not summary or summary[1] != summary[2]:
            raise RuntimeError("ART did not report a complete, successful test run")
        suites = {"signing": {"passed": int(summary[1]), "checks": int(summary[2])}}
        report.update(android_api=int(android[1]), android_release=android[2], vm=android[3], suites=suites)
        if not args.standalone:
            ids = re.search(r"ART_RESOURCE_IDS title=([0-9a-f]+) layout=([0-9a-f]+)", output)
            if not ids or [int(ids[1], 16), int(ids[2], 16)] != list(resource_ids.values()):
                raise RuntimeError("APK DEX resource IDs differ from the real linked R.txt")
            report["resource_ids"] = {name: hex(value) for name, value in resource_ids.items()}
            recovery = work / "output-recovery"
            recovery.mkdir()
            recovery_output = run([args.app_process, "/system/bin", "ApkOutputRecoveryAndroidTest", recovery],
                                  work, "art-output-recovery", commands, runtime_env)
            print(recovery_output, end="", flush=True)
            recovered = re.search(r"ART output recovery: (\d+)/(\d+) checks passed", recovery_output)
            if not recovered or recovered[1] != recovered[2]:
                raise RuntimeError("ART output recovery did not report complete success")
            suites["output_recovery"] = {"passed": int(recovered[1]), "checks": int(recovered[2])}
        report.update(status="passed", passed=sum(value["passed"] for value in suites.values()),
                      checks=sum(value["checks"] for value in suites.values()))
    except BaseException as error:
        report.update(status="failed", error=str(error))
        raise
    finally:
        (work / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("ART report:", work / "report.json", flush=True)


if __name__ == "__main__":
    main()

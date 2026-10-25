#!/usr/bin/env python3
"""Test the packaged ARSCLib coexistence engine on Android ART.

An external --fixture-aapt2 builds only the controlled fixture. The production
engine runs in-process from the supplied APK; no native APK compiler is used.
Installation/startup/provider checks remain opt-in via --adb.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import zipfile

from verify_apk_signing import SHA256


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tests/fixtures/apk-coexistence"
VENDOR = ROOT / "third_party/apkeditor"
PACKAGE = "com.example.materialfiles.coexistence.fixture"
CLONE = PACKAGE + ".clone"
ABI_BY_MACHINE = {"aarch64": "arm64-v8a", "arm64": "arm64-v8a", "armv7l": "armeabi-v7a",
                  "armv8l": "armeabi-v7a", "x86_64": "x86_64", "amd64": "x86_64"}


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command, work, name, commands, env, timeout=240):
    command = list(map(str, command))
    commands.append({"name": name, "argv": command})
    (work / "commands.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
    log = work / f"{name}.log"
    print(f"Running {name}; log: {log}", flush=True)
    with log.open("wb") as output:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output,
                                   stderr=subprocess.STDOUT, env=env, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except BaseException:
            # A timeout must not leave the ART process or its aapt2 child running.
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                pass
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
            raise
    text = log.read_text(encoding="utf-8", errors="replace")
    if code:
        print(text[-65536:], end="", flush=True)
        raise RuntimeError(f"{name} failed with exit code {code}; see {log}")
    return text


def classes_jar(path, directory):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, allowZip64=False) as archive:
        for entry in sorted(directory.rglob("*.class")):
            archive.write(entry, entry.relative_to(directory).as_posix())


def build_fixture(aapt, java, javac, args, work, commands, compile_env, native_env, runtime_apk, report):
    source = work / "fixture-source"
    shutil.copytree(FIXTURE, source)
    generated = work / "fixture-generated"
    generated.mkdir()
    compiled = work / "fixture-resources.zip"
    resource_apk = work / "fixture-resources.apk"
    run([aapt, "compile", "--dir", source / "res", "-o", compiled],
        work, "fixture-resources-compile", commands, native_env)
    run([aapt, "link", "-I", args.android_jar, "--manifest", source / "AndroidManifest.xml",
         "--min-sdk-version", "30", "--target-sdk-version", "36", "--java", generated,
         "--output-text-symbols", work / "fixture-R.txt", "-o", resource_apk, compiled],
        work, "fixture-resources-link", commands, native_env)
    classes = work / "fixture-classes"
    classes.mkdir()
    sources = sorted((source / "src").rglob("*.java")) + sorted(generated.rglob("*.java"))
    run([javac, f"-J-Djava.io.tmpdir={work}", "--release", "8", "-encoding", "UTF-8",
         "-cp", args.android_jar, "-d", classes, *sources],
        work, "fixture-javac", commands, compile_env)
    jar = work / "fixture-classes.jar"
    classes_jar(jar, classes)
    dex = work / "fixture-dex"
    dex.mkdir()
    run([java, f"-Djava.io.tmpdir={work}", "-Xmx768m", "-cp", args.d8_jar,
         "com.android.tools.r8.D8", "--min-api", "30", "--lib", args.android_jar,
         "--output", dex, jar], work, "fixture-d8", commands, compile_env)
    dex_files = sorted(dex.glob("classes*.dex"))
    if not dex_files or dex_files[0].name != "classes.dex":
        raise RuntimeError("D8 did not produce fixture code")
    unsigned = work / "unsigned fixture.apk"
    shutil.copyfile(resource_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_STORED, allowZip64=False) as archive:
        for entry in dex_files:
            archive.write(entry, entry.name)
        for entry in sorted((source / "assets").rglob("*")):
            if entry.is_file():
                archive.write(entry, "assets/" + entry.relative_to(source / "assets").as_posix())
        native_payloads = []
        with zipfile.ZipFile(runtime_apk) as production:
            for entry in sorted(production.infolist(), key=lambda value: value.filename):
                if not re.fullmatch(r"lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/libhiddenapi\.so", entry.filename):
                    continue
                if entry.file_size <= 4 or entry.file_size > 1024 * 1024:
                    raise RuntimeError("Unexpected fixture native payload size: " + entry.filename)
                data = production.read(entry)
                if not data.startswith(b"\x7fELF"):
                    raise RuntimeError("Packaged fixture native payload is not ELF: " + entry.filename)
                archive.writestr(entry.filename, data)
                native_payloads.append({"name": entry.filename, "bytes": len(data),
                                        "sha256": hashlib.sha256(data).hexdigest()})
        if not native_payloads:
            raise RuntimeError("Actual APK has no small libhiddenapi.so payload for the preservation check")
    report["fixture_native_payloads"] = {
        "source_apk_sha256": sha256(runtime_apk), "entries": native_payloads,
        "execution": "not-run", "scope": "Opaque ELF bytes copied and compared; no JNI functional test",
    }
    return unsigned


def build_harness(java, javac, args, work, commands, env, apksig):
    tests = [ROOT / "tests/ApkCoexistenceAndroidTest.java"]
    classes = work / "harness-classes"
    classes.mkdir()
    run([javac, f"-J-Djava.io.tmpdir={work}", "--release", "17", "-encoding", "UTF-8",
         "-cp", os.pathsep.join(map(str, (args.app_classes, args.android_jar, apksig))),
         "-d", classes, *tests], work, "harness-javac", commands, env)
    harness = work / "coexistence-harness.jar"
    classes_jar(harness, classes)
    api = work / "production-api-compile-only.jar"
    classes_jar(api, args.app_classes)
    dex = work / "coexistence-test-dex.jar"
    run([java, f"-Djava.io.tmpdir={work}", "-Xmx768m", "-cp", args.d8_jar,
         "com.android.tools.r8.D8", "--min-api", "30", "--lib", args.android_jar,
         "--classpath", api, "--classpath", apksig, "--output", dex, harness],
        work, "harness-d8", commands, env)
    dex.chmod(0o400)
    return dex


def provider_json(output):
    marker = "json="
    if marker not in output:
        raise RuntimeError("Provider did not return its self-test JSON: " + output)
    result, _ = json.JSONDecoder().raw_decode(output.split(marker, 1)[1])
    if result.get("status") != "ok":
        raise RuntimeError("Provider self-test failed: " + output)
    return result


def validate_installed(args, work, commands, env, art, report):
    if args.adb is None:
        report["device_validation"] = {
            "status": "not-run", "installation": "not-run", "cold_start": "not-run",
            "provider_self_test": "not-run",
            "reason": "--adb was not supplied; ART success does not establish installation or app behavior",
        }
        print("NOT RUN: installation, cold start and provider self-test (--adb not supplied).", flush=True)
        return
    adb = [args.adb]
    if args.adb_serial:
        adb += ["-s", args.adb_serial]
    device = {"status": "running", "installation": "not-run", "cold_start": "not-run",
              "provider_self_test": "not-run", "cleanup": {}}
    report["device_validation"] = device
    originals = {PACKAGE: Path(art["original"]), CLONE: Path(art["clone"])}
    expected_hashes = {PACKAGE: art["original_sha256"], CLONE: art["clone_sha256"]}
    for package, apk in originals.items():
        if not apk.resolve().is_relative_to(work) or sha256(apk) != expected_hashes[package]:
            raise RuntimeError("Untrusted or changed install-test artifact: " + str(apk))
    owned = []

    def execute(arguments, name, timeout=180):
        return run([*adb, *arguments], work, "adb-" + name, commands, env, timeout)

    def package_path(package, name):
        text = execute(["shell", "pm", "path", package], name)
        paths = [line[8:] for line in text.splitlines() if line.startswith("package:")]
        if len(paths) != 1 or not paths[0].startswith("/"):
            raise RuntimeError("Expected exactly one installed fixture APK: " + text)
        return paths[0]

    try:
        device["serial"] = execute(["get-serialno"], "serial").strip()
        device["android_api"] = int(execute(["shell", "getprop", "ro.build.version.sdk"], "api").strip())
        if device["android_api"] < 30:
            raise RuntimeError("Install fixture requires Android API 30 or later")
        user = execute(["shell", "am", "get-current-user"], "current-user").strip()
        if not user.isdigit():
            raise RuntimeError("Cannot identify the Android user for fixture tests")
        device["user"] = int(user)
        packages = execute(["shell", "pm", "list", "packages", "-u"], "existing-packages")
        existing = set(re.findall(r"^package:([^\r\n]+)$", packages, re.M))
        if any(package in existing for package in originals):
            raise RuntimeError("Refusing to replace a pre-existing original/clone fixture package")
        installed_paths = {}
        for suffix, package in (("original", PACKAGE), ("clone", CLONE)):
            output = execute(["install", "--no-streaming", "--user", user, originals[package]], "install-" + suffix)
            if not re.search(r"^Success\s*$", output, re.M):
                raise RuntimeError("adb did not confirm fixture installation: " + output)
            owned.append(package)
            installed_paths[package] = package_path(package, "path-" + suffix)
        # Query both after both installs: the clone must not have replaced the original.
        for suffix, package in (("original", PACKAGE), ("clone", CLONE)):
            if package_path(package, "coinstalled-" + suffix) != installed_paths[package]:
                raise RuntimeError("Installed fixture path changed during the dual-install check")
        device["installation"] = "passed"
        for suffix, package in (("original", PACKAGE), ("clone", CLONE)):
            execute(["shell", "am", "force-stop", "--user", user, package], "stop-" + suffix)
            output = execute(["shell", "am", "start", "--user", user, "-W", "-n",
                              package + "/" + PACKAGE + ".FixtureActivity"], "start-" + suffix)
            if "Status: ok" not in output or "Error:" in output:
                raise RuntimeError("Cold activity start was not successful: " + output)
        results = {}
        for suffix, package in (("original", PACKAGE), ("clone", CLONE)):
            output = execute(["shell", "content", "call", "--user", user, "--uri",
                              "content://" + package + ".provider/data", "--method", "selfTest", "--arg", suffix],
                             "provider-" + suffix)
            result = provider_json(output)
            expected = {"package": package, "authority": package + ".provider",
                        "permission": package + ".permission.FIXTURE", "action": PACKAGE + ".action.SELF_TEST",
                        "value": suffix + "-value", "activityResource": "单体 APK 资源与组件验证"}
            if any(result.get(key) != value for key, value in expected.items()) or result.get("activityStarts", 0) < 1:
                raise RuntimeError("Installed fixture behavior differs from expected state: " + str(result))
            results[suffix] = result
        device["cold_start"] = "passed"
        for suffix, package in (("original", PACKAGE), ("clone", CLONE)):
            result = provider_json(execute(["shell", "content", "call", "--user", user, "--uri",
                                           "content://" + package + ".provider/data", "--method", "readState"],
                                          "isolation-" + suffix))
            if result.get("value") != suffix + "-value" or result.get("package") != package:
                raise RuntimeError("Original/clone provider data are not isolated")
        device.update(status="passed", provider_self_test="passed", results=results)
    except BaseException as error:
        device.update(status="failed", error=str(error))
        raise
    finally:
        for package in reversed(owned):
            suffix = "clone" if package == CLONE else "original"
            try:
                # Unique disposable keys make the exact APK digest an ownership check.
                path = package_path(package, "cleanup-path-" + suffix)
                pulled = work / ("cleanup-installed-" + suffix + ".apk")
                execute(["pull", path, pulled], "cleanup-pull-" + suffix)
                if sha256(pulled) != expected_hashes[package]:
                    raise RuntimeError("Installed APK changed; preserve it instead of uninstalling")
                if package_path(package, "cleanup-recheck-" + suffix) != path:
                    raise RuntimeError("Installed APK path changed; preserve it instead of uninstalling")
                output = execute(["uninstall", package], "uninstall-" + suffix)
                if not re.search(r"^Success\s*$", output, re.M):
                    raise RuntimeError("adb did not confirm uninstall: " + output)
                device["cleanup"][package] = "removed-script-installed-fixture"
            except BaseException as error:
                device["cleanup"][package] = "preserved: " + str(error)
                device["status"] = "failed"
        if device["status"] == "failed" and "error" not in device:
            raise RuntimeError("Device fixture cleanup could not be completed; see device_validation.cleanup")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, default=Path.home() / "tmp/materialfiles-apk-coexistence-check")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path)
    parser.add_argument("--dependencies", type=Path, default=Path.home() / "tmp/materialfiles-apk-tools/deps")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--app-classes", type=Path,
                        default=ROOT / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
    parser.add_argument("--resource-symbols", type=Path,
                        default=ROOT / "app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt")
    parser.add_argument("--app-process", type=Path, default=Path("/system/bin/app_process"))
    parser.add_argument("--abi", choices=("arm64-v8a", "armeabi-v7a", "x86_64"))
    parser.add_argument("--fixture-aapt2", type=Path, required=True,
                        help="External compiler for the test fixture only")
    parser.add_argument("--adb", type=Path, help="Opt in to actual installation/startup/provider checks with this adb")
    parser.add_argument("--adb-serial", help="Device serial used with the optional --adb checks")
    args = parser.parse_args()
    java = args.java_home / "bin/java" if args.java_home else shutil.which("java")
    javac = args.java_home / "bin/javac" if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("A JDK 17+ is required; specify --java-home")
    if not args.android_jar.is_file() or not args.app_process.is_file():
        parser.error("Use an Android SDK android.jar and execute on Android/Termux")
    if args.d8_jar is None:
        version = re.search(r"buildToolsVersion\s*=\s*'([^']+)'", (ROOT / "app/build.gradle").read_text())[1]
        args.d8_jar = args.android_jar.resolve().parents[2] / "build-tools" / version / "lib/d8.jar"
    if not args.d8_jar.is_file() or not args.apk.is_file() or not args.app_classes.is_dir():
        parser.error("Build :app:assembleDebug first and provide an existing D8 JAR")
    if args.adb and (not args.adb.is_file() or not os.access(args.adb, os.X_OK)):
        parser.error("--adb must name an existing executable; omit it for explicit not-run device checks")
    if args.adb_serial and not args.adb:
        parser.error("--adb-serial requires --adb")
    if args.fixture_aapt2 and not args.fixture_aapt2.is_file():
        parser.error("--fixture-aapt2 must name an existing local compiler")
    abi = args.abi or ABI_BY_MACHINE.get(os.uname().machine.lower())
    if abi is None:
        parser.error("Cannot select a supported fixture ABI; specify --abi")
    apksig = args.dependencies / "apksig-9.4.1.jar"
    if not apksig.is_file() or sha256(apksig) != SHA256[apksig.name]:
        parser.error("Missing pinned apksig compile API; prepare verify_apk_signing.py dependencies first")
    runtime_sources = sorted((ROOT / "app/src/main/java/me/zhanghai/android/files/apk").rglob("*.java"))
    runtime_sources += sorted((ROOT / "app/src/main/java/me/zhanghai/android/files/apk").glob("*.kt"))
    vendor_sources = list((VENDOR / "src/main/java").rglob("*.java"))
    runtime_sources += sorted(vendor_sources)
    if any(args.apk.stat().st_mtime < source.stat().st_mtime for source in runtime_sources):
        parser.error("The APK predates its coexistence/runtime sources; rebuild :app:assembleDebug")
    for source in runtime_sources:
        if source.suffix != ".java":
            continue
        relative = source.as_posix().split("/src/main/java/", 1)[1]
        compiled = args.app_classes / Path(relative).with_suffix(".class")
        if not compiled.is_file() or compiled.stat().st_mtime < source.stat().st_mtime:
            parser.error("Missing or stale production API class: " + str(compiled))
    if not args.resource_symbols.is_file():
        parser.error("Missing linked R.txt; rebuild the debug APK")
    match = re.search(r"^int string apk_coexistence_title (0x[0-9a-fA-F]+)$",
                      args.resource_symbols.read_text(), re.M)
    if not match:
        parser.error("Linked resources do not include the coexistence UI")
    expected_title = match[1]
    if not args.work_dir.resolve().is_relative_to((Path.home() / "tmp").resolve()):
        parser.error("All test work must be under ~/tmp")
    args.work_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir)).resolve()
    print("Coexistence test workspace:", work, flush=True)
    commands = []
    tests = [ROOT / "tests/ApkCoexistenceAndroidTest.java"]
    report = {"status": "running", "mode": "actual-packaged-apk", "apk_source": str(args.apk.resolve()),
              "apk_sha256": sha256(args.apk), "abi": abi, "apksig_compile_api_sha256": sha256(apksig),
              "sources": {str(path.relative_to(ROOT)): sha256(path) for path in runtime_sources},
              "tests": {str(path.relative_to(ROOT)): sha256(path) for path in tests},
              "fixture_sources": {str(path.relative_to(FIXTURE)): sha256(path)
                                  for path in sorted(FIXTURE.rglob("*")) if path.is_file()},
              "engine": "APKEditor / ARSCLib",
              "device_validation": {"status": "not-run", "installation": "not-run", "cold_start": "not-run",
                                    "provider_self_test": "not-run", "reason": "ART phase has not completed"}}
    compile_env = os.environ.copy()
    compile_env["TMPDIR"] = str(work)
    native_env = compile_env.copy()
    native_env.pop("LD_LIBRARY_PATH", None)
    native_env.pop("LD_PRELOAD", None)
    native_env["PATH"] = "/system/bin:/system/xbin"
    try:
        runtime_apk = work / "materialfiles-under-test.apk"
        shutil.copyfile(args.apk, runtime_apk)
        runtime_apk.chmod(0o400)
        fixture_aapt = args.fixture_aapt2.resolve()
        report["fixture_aapt2"] = {"path": str(fixture_aapt), "sha256": sha256(fixture_aapt)}
        fixture_env = compile_env
        unsigned = build_fixture(fixture_aapt, java, javac, args, work, commands, compile_env,
                                 fixture_env, runtime_apk, report)
        report["unsigned_fixture_sha256"] = sha256(unsigned)
        dex = build_harness(java, javac, args, work, commands, compile_env, apksig)
        runtime_env = native_env.copy()
        runtime_env["CLASSPATH"] = os.pathsep.join((str(runtime_apk), str(dex)))
        report["runtime_classpath"] = runtime_env["CLASSPATH"]
        output = run([args.app_process, "/system/bin", "ApkCoexistenceAndroidTest", work,
                      unsigned, runtime_apk, expected_title],
                     work, "art-coexistence", commands, runtime_env, timeout=600)
        print(output, end="", flush=True)
        art = json.loads((work / "art-summary.json").read_text(encoding="utf-8"))
        report["art"] = art
        if art["status"] != "passed" or art["passed"] != art["checks"]:
            raise RuntimeError("ART coexistence checks did not report complete success")
        validate_installed(args, work, commands, compile_env, art, report)
        report["status"] = "passed"
    except BaseException as error:
        report.update(status="failed", error=str(error))
        summary = work / "art-summary.json"
        if summary.is_file():
            try:
                report["art"] = json.loads(summary.read_text(encoding="utf-8"))
            except (OSError, ValueError) as summary_error:
                report["art_summary_error"] = str(summary_error)
        raise
    finally:
        (work / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Coexistence report:", work / "report.json", flush=True)


if __name__ == "__main__":
    main()

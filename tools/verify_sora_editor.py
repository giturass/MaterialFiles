#!/usr/bin/env python3
"""Verify the compiled Sora editor helpers on Android's real runtime from Termux.

First run :app:compileDebugKotlin (or assembleDebug). This script compiles the Java
harnesses against the actual application classes and cached Sora dependencies,
converts them to DEX with the SDK's D8, and runs them using /system/bin/app_process.
It uses real Android and Sora classes, without substituting text, undo or platform
APIs. Grammar tests read the bundled assets through Sora's file resolver boundary.
It does not test the editor's touch gestures, IME, UI or process-death recovery.

Example (with the same Java runtime environment used for the project build):
  python3 tools/verify_sora_editor.py --gradle-home "$GRADLE_USER_HOME" \
    --android-jar "$ANDROID_HOME/platforms/android-37.2/android.jar"

All generated files and logs are retained under ~/tmp/materialfiles-sora-check.
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


APP_PACKAGE = Path("me/zhanghai/android/files/viewer/text")
SUITES = {
    "document": ("SoraEditorDocument", "SoraEditorDocumentTest"),
    "languages": ("SoraEditorLanguages", "SoraEditorLanguagesTest"),
    "replacements": ("SoraEditorReplacements", "SoraEditorReplacementsTest"),
}


def cached_artifact(gradle_home, group, artifact, version=None, extension="jar"):
    directory = gradle_home / "caches/modules-2/files-2.1" / group / artifact
    if version is None:
        versions = [path for path in directory.glob("*")
                    if path.is_dir() and re.fullmatch(r"\d+(?:\.\d+)+", path.name)]
        if not versions:
            raise RuntimeError(f"No cached {group}:{artifact}; build the app first")
        version = max(versions, key=lambda path: tuple(map(int, path.name.split(".")))).name
    matches = list((directory / version).glob(f"*/{artifact}-{version}.{extension}"))
    if len(matches) != 1:
        raise RuntimeError(f"Expected cached {group}:{artifact}:{version} ({extension}); "
                           "build the app or pass the correct --gradle-home")
    return matches[0]


def run(command, work, name, timeout=120, env=None):
    log = work / f"{name}.log"
    try:
        result = subprocess.run(list(map(str, command)), stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=timeout,
                                stdin=subprocess.DEVNULL, env=env)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        log.write_text(output, encoding="utf-8")
        raise RuntimeError(f"{name} exceeded {timeout} seconds; see {log}") from error
    log.write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        print(result.stdout, end="", flush=True)
        raise RuntimeError(f"{name} failed with exit code {result.returncode}; see {log}")
    return result.stdout


def copy_runtime_resources(output_jar, input_jars):
    # D8 emits only DEX. JCodings and Kotlin also load data from their JARs at runtime.
    with zipfile.ZipFile(output_jar, "a", zipfile.ZIP_DEFLATED) as output:
        copied = set(output.namelist())
        for input_jar in input_jars:
            with zipfile.ZipFile(input_jar) as source:
                for entry in source.infolist():
                    name = entry.filename
                    if (entry.is_dir() or name.endswith((".class", ".dex")) or
                            (name.startswith("META-INF/") and
                             not name.startswith("META-INF/services/"))):
                        continue
                    data = source.read(entry)
                    if name in copied:
                        if output.read(name) != data:
                            raise RuntimeError("Conflicting dependency runtime resource: " + name)
                        continue
                    output.writestr(name, data)
                    copied.add(name)


def verify_assets(assets):
    directory = assets / "sora"
    manifest = json.loads((directory / "sources.json").read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        path = directory / entry["path"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise RuntimeError("Bundled asset checksum differs from sources.json: " + str(path))
    print("Verified bundled asset checksums:", len(manifest["files"]), flush=True)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-sora-check")
    parser.add_argument("--gradle-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path,
                        help="Defaults to the configured SDK build-tools lib/d8.jar")
    parser.add_argument("--app-classes", type=Path, default=root /
                        "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    parser.add_argument("--app-process", type=Path, default=Path("/system/bin/app_process"))
    parser.add_argument("--sora-version", default="0.24.6")
    parser.add_argument("--assets", type=Path, default=root / "app/src/main/assets")
    parser.add_argument("--suite", action="append", choices=SUITES,
                        help="Run a specific suite; may be repeated. Defaults to all suites.")
    args = parser.parse_args()

    suites = list(dict.fromkeys(args.suite or SUITES))
    app_classes = []
    for suite in suites:
        app_class = APP_PACKAGE / SUITES[suite][0]
        compiled = args.app_classes / app_class.with_suffix(".class")
        source = root / "app/src/main/java" / app_class.with_suffix(".kt")
        if not compiled.is_file() or compiled.stat().st_mtime < source.stat().st_mtime:
            parser.error(f"{app_class.name}.class is missing or older than its source; "
                         "run :app:compileDebugKotlin first")
        app_classes.append(app_class)
    if not args.android_jar.is_file():
        parser.error("--android-jar must point to the Android SDK platform android.jar")
    if not args.app_process.is_file():
        parser.error("Run this script on Android/Termux, or specify --app-process")
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
    kotlin_version = re.search(r"kotlin_version\s*=\s*'([^']+)'",
                               (root / "build.gradle").read_text()).group(1)
    try:
        sora = cached_artifact(args.gradle_home, "io.github.rosemoe", "editor",
                               args.sora_version, "aar")
        textmate = cached_artifact(args.gradle_home, "io.github.rosemoe", "language-textmate",
                                   args.sora_version, "aar")
        dependencies = [
            cached_artifact(args.gradle_home, "org.jetbrains.kotlin", "kotlin-stdlib", kotlin_version),
            cached_artifact(args.gradle_home, "androidx.collection", "collection-jvm"),
            cached_artifact(args.gradle_home, "androidx.annotation", "annotation-jvm"),
            cached_artifact(args.gradle_home, "com.google.code.gson", "gson"),
            cached_artifact(args.gradle_home, "org.jruby.jcodings", "jcodings"),
            cached_artifact(args.gradle_home, "org.jruby.joni", "joni"),
            cached_artifact(args.gradle_home, "org.snakeyaml", "snakeyaml-engine"),
            cached_artifact(args.gradle_home, "org.eclipse.jdt", "org.eclipse.jdt.annotation"),
        ]
    except RuntimeError as error:
        parser.error(str(error))
    if "languages" in suites:
        verify_assets(args.assets)

    args.work_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir)).resolve()
    classes = work / "classes"
    classes.mkdir()
    sora_jars = []
    for aar in [sora, textmate]:
        jar = work / aar.with_suffix(".jar").name
        with zipfile.ZipFile(aar) as archive:
            jar.write_bytes(archive.read("classes.jar"))
        sora_jars.append(jar)
    classpath = os.pathsep.join(map(str, [args.app_classes, args.android_jar,
                                        *sora_jars, *dependencies]))
    compile_env = os.environ.copy()
    compile_env["TMPDIR"] = str(work)
    sources = [root / "tests" / (SUITES[suite][1] + ".java") for suite in suites]
    runner = work / "SoraEditorTestRunner.java"
    runner.write_text("""import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Keep assertion failures on stderr instead of Android's logcat-only crash handler. */
public final class SoraEditorTestRunner {
    public static void main(String[] arguments) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        try {
            String[] suiteArguments = Arrays.copyOfRange(arguments, 1, arguments.length);
            Class.forName(arguments[0]).getMethod("main", String[].class)
                    .invoke(null, (Object) suiteArguments);
        } catch (InvocationTargetException exception) {
            fail(exception.getCause());
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void fail(Throwable error) {
        error.printStackTrace(System.err);
        System.exit(1);
    }
}
""", encoding="utf-8")
    run([javac, f"-J-Djava.io.tmpdir={work}", "--release", "17", "-encoding", "UTF-8",
         "-cp", classpath, "-d", classes, runner, *sources],
        work, "javac", env=compile_env)

    test_jar = work / "sora-test.jar"
    with zipfile.ZipFile(test_jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes).as_posix())
        for app_class in app_classes:
            compiled = args.app_classes / app_class.with_suffix(".class")
            archive.write(compiled, app_class.with_suffix(".class").as_posix())
            for path in sorted(compiled.parent.glob(app_class.name + "$*.class")):
                archive.write(path, (APP_PACKAGE / path.name).as_posix())

    dex_jar = work / "sora-test-dex.jar"
    run([java, f"-Djava.io.tmpdir={work}", "-Xmx512m", "-cp", args.d8_jar,
         "com.android.tools.r8.D8", "--min-api", "23", "--lib", args.android_jar,
         "--output", dex_jar, test_jar, *sora_jars, *dependencies],
        work, "d8", env=compile_env)
    copy_runtime_resources(dex_jar, [*sora_jars, *dependencies])
    # Recent Android versions require files loaded as executable DEX to be read-only.
    dex_jar.chmod(0o400)
    runtime_env = os.environ.copy()
    runtime_env["CLASSPATH"] = str(dex_jar)
    runtime_env["TMPDIR"] = str(work)
    # app_process must use platform libraries, not the JDK's Termux library path.
    runtime_env.pop("LD_LIBRARY_PATH", None)
    runtime_env.pop("LD_PRELOAD", None)
    print("Compiled application classes:", args.app_classes, flush=True)
    print("Sora dependency:", sora, flush=True)
    failures = []
    for suite in suites:
        arguments = [args.assets.resolve()] if suite == "languages" else []
        log_name = "results-" + suite
        try:
            output = run([args.app_process, "/system/bin", "SoraEditorTestRunner",
                          SUITES[suite][1], *arguments], work, log_name,
                         timeout=180 if suite == "languages" else 60, env=runtime_env)
            print(output, end="", flush=True)
            if "PASS:" not in output:
                raise RuntimeError("The runtime did not report test completion; see "
                                   + str(work / f"{log_name}.log"))
        except RuntimeError as error:
            failures.append(suite)
            print(str(error), flush=True)
    print("Verification logs:", work, flush=True)
    if failures:
        raise RuntimeError("Verification failed: " + ", ".join(failures))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build official 7-Zip, its JNI binding and the property adapter with an Android NDK sysroot.

This Termux helper uses the same pinned source archive and source list as app/CMakeLists.txt.
It does not require Gradle or CMake, and never links a Termux/shared C++ runtime into the APK.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile


TARGETS = {
    "arm64-v8a": ("aarch64-linux-android23", "aarch64-linux-android"),
    "armeabi-v7a": ("armv7a-linux-androideabi23", "arm-linux-androideabi"),
    "x86": ("i686-linux-android23", "i686-linux-android"),
    "x86_64": ("x86_64-linux-android23", "x86_64-linux-android"),
}
SOURCE_SHA256 = "9691944c0fe0d01bb49373a704fb983fd33bc98b1738695179dfbf99ac1734f6"
DEFINITIONS = (
    "_FILE_OFFSET_BITS=64", "_LARGEFILE_SOURCE", "_REENTRANT", "ENV_UNIX",
    "BREAK_HANDLER", "UNICODE", "_UNICODE", "UNIX_USE_WIN_FILE", "NDEBUG",
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def extract_source(archive, destination):
    if sha256(archive) != SOURCE_SHA256:
        raise RuntimeError("Official 7-Zip 26.04 source archive checksum mismatch")
    marker = destination / ".source-sha256"
    if marker.is_file() and marker.read_text().strip() == SOURCE_SHA256:
        return
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        for member in source:
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts:
                raise RuntimeError("Unsafe path in the pinned source archive")
            if not member.isfile():
                if not member.isdir():
                    raise RuntimeError("Unexpected link in the pinned source archive")
                continue
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.extractfile(member).read())
    marker.write_text(SOURCE_SHA256 + "\n")


def source_paths(vendor, engine):
    roots = {"SEVEN_ZIP_SRC": engine, "JBINDING_SRC": vendor / "jbinding-cpp"}
    entries = re.findall(r"^\s*\$\{(SEVEN_ZIP_SRC|JBINDING_SRC)\}/(\S+)",
                         (vendor / "sources.cmake").read_text(), re.MULTILINE)
    if not entries:
        raise RuntimeError("No native source files in sources.cmake")
    result = [roots[name] / relative for name, relative in entries]
    for path in result:
        if not path.is_file():
            raise RuntimeError(f"Missing native source: {path}")
    return result


def compiler_flags(compiler, toolchain, resource_dir, target, engine, vendor):
    includes = [engine, engine / "CPP", engine / "C", engine / "CPP/Windows",
                vendor / "jbinding-cpp"]
    flags = [
        compiler, f"--target={target}", f"--sysroot={toolchain / 'sysroot'}",
        f"-resource-dir={resource_dir}", "-fPIC", "-O2", "-g",
        "-fvisibility=hidden", "-fstack-protector-strong",
        "-ffunction-sections", "-fdata-sections",
        *[f"-D{value}" for value in DEFINITIONS], *[f"-I{path}" for path in includes],
    ]
    # The Termux driver adds its private library rpath; the official NDK driver does not.
    if not str(Path(compiler).resolve()).startswith(str(toolchain) + os.sep):
        flags.append("-fno-termux-rpath")
    return flags


def configuration_key(vendor, engine, common, compiler_version):
    # Include the extracted official headers as well as JNI headers, so editing either
    # cannot silently reuse objects from an earlier build or native test run.
    headers = sorted([*(vendor / "jbinding-cpp").rglob("*.h"), *engine.rglob("*.h")])
    header_hash = hashlib.sha256("".join(
        str(path) + sha256(path) for path in headers).encode()).hexdigest()
    return hashlib.sha256(
        json.dumps([SOURCE_SHA256, header_hash, common, compiler_version]).encode()).hexdigest()


def run(command, log, env):
    result = subprocess.run(list(map(str, command)), env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    log.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f"Native build failed; see {log}\n{result.stdout[-8000:]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=Path.home() / ".local/share/android-ndk")
    parser.add_argument("--cxx", default="clang++",
                        help="Termux clang++ or a host-compatible NDK clang++")
    parser.add_argument("--abi", choices=TARGETS, nargs="+", default=list(TARGETS))
    parser.add_argument("--jobs", type=int, default=min(4, os.cpu_count() or 1))
    parser.add_argument("--work-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-sevenzip-build")
    parser.add_argument("--output-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-check/jniLibs")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    compiler = shutil.which(args.cxx)
    if not compiler:
        parser.error("clang++ was not found; install clang or specify --cxx")
    toolchain = args.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64"
    resources = list((toolchain / "lib/clang").glob("[0-9]*"))
    if len(resources) != 1:
        parser.error("Expected one Clang resource directory in the Android NDK")
    root = Path(__file__).resolve().parents[1]
    vendor = root / "third_party/sevenzip"
    work = args.work_dir.resolve()
    engine = work / "7zip-26.04"
    extract_source(vendor / "7z2604-src.tar.xz", engine)
    sources = source_paths(vendor, engine)
    compiler_version = subprocess.check_output([compiler, "--version"], text=True)
    for abi in args.abi:
        target, triple = TARGETS[abi]
        build = work / abi
        build.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env["TMPDIR"] = str(build)
        libraries = toolchain / "sysroot/usr/lib" / triple
        runtime = [libraries / "libc++_static.a", libraries / "libc++abi.a"]
        for library in runtime:
            if not library.is_file():
                parser.error(f"Missing NDK static library: {library}")
        common = compiler_flags(compiler, toolchain, resources[0], target, engine, vendor)
        # Conservative header/flag invalidation, with per-source reuse across repeated runs.
        configuration = configuration_key(vendor, engine, common, compiler_version)

        def compile_source(index, source, strict=False):
            object_file = build / f"{index:03d}-{source.name}.o"
            stamp = object_file.with_suffix(".sha256")
            key = hashlib.sha256((configuration + sha256(source) + str(strict)).encode()).hexdigest()
            if object_file.is_file() and stamp.is_file() and stamp.read_text() == key:
                return object_file, False
            is_cpp = source.suffix == ".cpp"
            command = common + ["-x", "c++" if is_cpp else "c",
                                "-std=c++11" if is_cpp else "-std=gnu11"]
            if is_cpp:
                command += ["-fexceptions", "-fno-rtti"]
            if strict:
                command += ["-Wall", "-Wextra", "-Werror"]
            command += ["-c", source, "-o", object_file]
            run(command, object_file.with_suffix(".log"), env)
            stamp.write_text(key)
            return object_file, True

        print(f"Building 7-Zip 26.04 for {abi} ({len(sources)} source files)", flush=True)
        objects = [None] * len(sources)
        rebuilt = 0
        with ThreadPoolExecutor(max_workers=args.jobs) as executor:
            futures = {executor.submit(compile_source, i, source): i
                       for i, source in enumerate(sources)}
            for future in as_completed(futures):
                obj, changed = future.result()
                objects[futures[future]] = obj
                rebuilt += changed
        adapter, _ = compile_source(len(sources), root / "app/src/main/jni/archive_jbinding.cpp", True)
        output = args.output_dir.resolve() / abi
        output.mkdir(parents=True, exist_ok=True)
        for name, inputs in (("lib7-Zip-JBinding.so", objects),
                             ("libarchive_jbinding.so", [adapter])):
            # Explicit NDK static libraries avoid Termux's -static-libstdc++ behavior.
            temporary = build / name
            command = common + [
                "-shared", "-nostdlib++",
                "-Wl,--no-undefined,--gc-sections,--build-id=sha1,--exclude-libs,ALL",
                "-Wl,-z,relro,-z,now,-z,max-page-size=16384", f"-Wl,-soname,{name}",
                *inputs, *runtime, "-o", temporary,
            ]
            run(command, build / (name + ".link.log"), env)
            shutil.copyfile(temporary, output / name)
            print(f"Built {abi}: {output / name} SHA-256 {sha256(output / name)}", flush=True)
        print(f"{abi}: {rebuilt} engine/binding objects rebuilt", flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build the same JNI source list as CMake with an on-device Termux clang and NDK sysroot."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--compiler", default=shutil.which("clang"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
                        default="arm64-v8a")
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    source_root = Path(__file__).resolve().parent
    target, library_directory, runtime_directory = {
        "arm64-v8a": ("aarch64-linux-android23", "aarch64-linux-android", "aarch64"),
        "armeabi-v7a": ("armv7a-linux-androideabi23", "arm-linux-androideabi", "arm"),
        "x86": ("i686-linux-android23", "i686-linux-android", "i386"),
        "x86_64": ("x86_64-linux-android23", "x86_64-linux-android", "x86_64"),
    }[args.abi]
    toolchain = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    resource_directory = toolchain / "lib/clang/21"
    compiler_resources = Path(subprocess.check_output(
        [args.compiler, "-print-resource-dir"], text=True).strip())
    sysroot = toolchain / "sysroot"
    args.output.mkdir(parents=True, exist_ok=True)
    objects_directory = args.output / (args.abi + "-objects")
    objects_directory.mkdir(exist_ok=True)
    flags = [args.compiler, "--target=" + target, "--sysroot=" + str(sysroot),
             # Intrinsic headers must match the executing clang, while runtime libraries and
             # Android APIs still come from the NDK used by the app.
             "-resource-dir=" + str(compiler_resources), "-fno-termux-rpath", "-O2", "-g",
             "-fPIC", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
             "-fstack-protector-strong", "-ffunction-sections", "-fdata-sections",
             "-DZ7_COM_USE_ATOMIC", "-D_FILE_OFFSET_BITS=64"]
    # The official ARM byte-swap implementation uses GNU inline asm.
    c_flags = ["-std=gnu11"]
    cpp_flags = ["-std=gnu++17", "-fexceptions", "-frtti", "-nostdinc++", "-isystem",
                 str(sysroot / "usr/include/c++/v1")]
    if args.abi == "armeabi-v7a":
        flags += ["-march=armv7-a", "-mthumb", "-mfpu=neon", "-mfloat-abi=softfp"]
    fingerprint = hashlib.sha256(repr(flags + c_flags + cpp_flags).encode()).hexdigest()
    marker = objects_directory / "flags.sha256"
    same_flags = marker.exists() and marker.read_text() == fingerprint
    header_time = max(path.stat().st_mtime for path in source_root.rglob("*.h"))
    source_names = (source_root / "sevenzip_sources.list").read_text().splitlines()
    environment = dict(os.environ)
    environment["TMPDIR"] = str(args.output.resolve())

    def compile_source(source_name):
        source = source_root / source_name
        output = objects_directory / (source_name.replace("/", "__") + ".o")
        if same_flags and output.exists() and output.stat().st_mtime >= max(
                source.stat().st_mtime, header_time):
            return output
        command = flags + (cpp_flags if source.suffix == ".cpp" else c_flags)
        command += ["-c", str(source), "-o", str(output)]
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, env=environment)
        if completed.returncode:
            log = objects_directory / (source_name.replace("/", "__") + ".log")
            log.write_text(completed.stdout)
            raise RuntimeError(source_name + "\n" + completed.stdout[:5000] + "\nFull log: " + str(log))
        return output

    objects = []
    failures = []
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures = [executor.submit(compile_source, name) for name in source_names]
        for index, future in enumerate(as_completed(futures), 1):
            try:
                objects.append(future.result())
            except Exception as exception:
                failures.append(str(exception))
            if index % 20 == 0:
                print(f"Compiled {index}/{len(futures)} translation units", flush=True)
    if failures:
        for failure in failures:
            print(failure, flush=True)
        raise SystemExit(1)
    marker.write_text(fingerprint)
    library_root = sysroot / "usr/lib" / library_directory
    runtime_root = resource_directory / "lib/linux" / runtime_directory
    output = args.output / "libsevenzip.so"
    command = flags + ["-resource-dir=" + str(resource_directory), "-shared",
                       "-Wl,--no-undefined,--gc-sections,--build-id=sha1,--threads=1",
                       "-Wl,--exclude-libs,ALL,--wrap=pthread_create",
                       "-Wl,-z,relro,-z,now,-z,max-page-size=16384",
                       "-Wl,-soname,libsevenzip.so",
                       "-Wl,--version-script=" + str(source_root / "sevenzip.map")]
    command += [str(path) for path in sorted(objects)]
    command += ["-Wl,--start-group", str(library_root / "libc++_static.a"),
                str(library_root / "libc++abi.a"), str(runtime_root / "libunwind.a"),
                str(runtime_root / "libatomic.a"), "-Wl,--end-group", "-lm", "-ldl",
                "-o", str(output)]
    subprocess.run(command, check=True, env=environment)
    print(output, flush=True)


if __name__ == "__main__":
    main()

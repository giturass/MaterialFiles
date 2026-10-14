#!/usr/bin/env python3
"""Build the JBinding property adapter with Termux clang and the Android NDK sysroot."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


TARGETS = {
    "arm64-v8a": ("aarch64-linux-android23", "aarch64-linux-android"),
    "armeabi-v7a": ("armv7a-linux-androideabi23", "arm-linux-androideabi"),
    "x86": ("i686-linux-android23", "i686-linux-android"),
    "x86_64": ("x86_64-linux-android23", "x86_64-linux-android"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path,
                        default=Path.home() / ".local/share/android-ndk")
    parser.add_argument("--cxx", default="clang++")
    parser.add_argument("--abi", choices=TARGETS, nargs="+", default=["arm64-v8a"])
    parser.add_argument("--output-dir", type=Path,
                        default=Path.home() / "tmp/materialfiles-check/jniLibs")
    args = parser.parse_args()
    compiler = shutil.which(args.cxx)
    if not compiler:
        parser.error("Termux clang++ was not found; install clang or specify --cxx")
    toolchain = args.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64"
    resources = list((toolchain / "lib/clang").glob("[0-9]*"))
    if len(resources) != 1:
        parser.error("Expected one Clang resource directory in the Android NDK")
    source = Path(__file__).resolve().parents[1] / "app/src/main/jni/archive_jbinding.cpp"
    temporary_root = Path.home() / "tmp"
    temporary_root.mkdir(parents=True, exist_ok=True)
    for abi in args.abi:
        target, triple = TARGETS[abi]
        output = args.output_dir.resolve() / abi / "libarchive_jbinding.so"
        output.parent.mkdir(parents=True, exist_ok=True)
        libraries = toolchain / "sysroot/usr/lib" / triple
        for library in ("libc++_static.a", "libc++abi.a"):
            if not (libraries / library).is_file():
                parser.error(f"Missing NDK static library: {libraries / library}")
        with tempfile.TemporaryDirectory(prefix="archive-jbinding-build-", dir=temporary_root) as work:
            temporary = Path(work) / output.name
            env = os.environ.copy()
            env["TMPDIR"] = work
            # Termux's -static-libstdc++ selects -Bstatic -lc++_shared. Use the NDK's actual
            # static libraries explicitly so no Termux or shared C++ runtime enters the APK.
            command = [
                compiler, f"--target={target}", f"--sysroot={toolchain / 'sysroot'}",
                f"-resource-dir={resources[0]}", "-fno-termux-rpath", "-std=c++11",
                "-shared", "-fPIC", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
                "-fexceptions", "-fno-rtti", "-fvisibility=hidden", "-nostdlib++",
                "-fstack-protector-strong", "-ffunction-sections", "-fdata-sections",
                "-Wl,--no-undefined,--gc-sections,--build-id=sha1,--exclude-libs,ALL",
                "-Wl,-z,relro,-z,now,-z,max-page-size=16384",
                "-Wl,-soname,libarchive_jbinding.so", str(source),
                str(libraries / "libc++_static.a"), str(libraries / "libc++abi.a"),
                "-o", str(temporary),
            ]
            subprocess.run(command, env=env, check=True)
            temporary.replace(output)
        print(f"Built {abi}: {output}", flush=True)


if __name__ == "__main__":
    main()

# Aapt2 package build workflow

> **Apktool-Android note (read this first):**
> This directory is a vendored copy of [ReVanced/aapt2](https://github.com/ReVanced/aapt2)
> with three local edits, all under version control in our outer repo:
>
> 1. `patch.sh` is host-aware (`sed -i ''` on macOS vs. `sed -i` on Linux)
>    and calls `patch -p1` instead of `git apply` (`git apply` walked up
>    to the outer Apktool-Android `.git/` and silently skipped every
>    patch).
> 2. `build.sh` is host-aware: `NDK_HOST` is computed from `uname -s`
>    so the `llvm-strip` path resolves on macOS as well as Linux.
> 3. `patches/0002-aapt2-unrestrict-item-tag-value-format.patch` is
>    new (iBotPeaches PR #3130, `apktool-3.0.x`). It is appended to the
>    list `patch.sh` applies, AFTER ReVanced's existing patches.
>
> The submodules under `submodules/` are NOT committed in this repo;
> developers symlink them from a scratch clone of `ReVanced/aapt2`.
> See `aapt2-build/README.md` for the full refresh procedure.
>
> The rest of this README is the upstream ReVanced README, kept verbatim
> for reference.
>
> ---

[![Release workflow](../../actions/workflows/release.yml/badge.svg)](../../actions/workflows/release.yml)

This repository contains a workflow to build the aapt2 binaries for Android.

Currently, the submodules are pinned to platform-tools 35.0.2 of AOSP source code. `src/base` example:
https://android.googlesource.com/platform/frameworks/base/+/refs/tags/platform-tools-35.0.2

# Build

## Clone with submodules
```bash
git clone --recurse-submodules --shallow-submodules --depth 1 https://github.com/ReVanced/aapt2
```

## Protoc dependency

`protoc` needs to be installed, the current required version is 21.12. Install it with:
```bash
wget https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-linux-x86_64.zip
sudo unzip -p protoc-*.zip bin/protoc -d /usr/local/bin/
```

The required version can be identified with :
```bash
grep PROTOC_VERSION submodules/protobuf/protobuf_version.bzl
```

## Apply patch
```bash
./patch.sh
```

## Build the binary

Supported arch are 'x86_64', 'x86', 'arm64-v8a' & 'armeabi-v7a'
```bash
ANDROID_NDK="$HOME/Android/Sdk/ndk/27.3.13750724/" ./build.sh arm64-v8a  # Build output in build/bin/aapt-*
```

# Credits

This repository is made with the help of:
- [lzhiyong](https://github.com/lzhiyong/android-sdk-tools)
- [iBotPeaches](https://github.com/aosp-mirror/platform_frameworks_base/compare/main...iBotPeaches:platform_frameworks_base:apktool_14.0.0) for [apktool_ibotpeaches.patch](patches/apktool_ibotpeaches.patch])

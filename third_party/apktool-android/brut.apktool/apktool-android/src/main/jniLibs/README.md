# jniLibs

Per-ABI statically-linked `libaapt2.so` binaries packaged into the
`apktool-android` AAR.

## What ships here

| File | ABI | Source |
|------|-----|--------|
| `arm64-v8a/libaapt2.so` | 64-bit ARM | self-built |
| `armeabi-v7a/libaapt2.so` | 32-bit ARM | self-built |
| `x86_64/libaapt2.so` | 64-bit x86 | self-built |

These files **are committed**. They are not built by Gradle; they are
produced out-of-band by the recipe in
[`aapt2-build/recipe/`](../../../../../aapt2-build/recipe/) and
copied into this directory. Refreshing them is a humans-only flow
(see `aapt2-build/README.md`); Gradle just packages whatever is here
into the AAR.

## Linkage contract

Each `.so` MUST be fully statically linked — no `DT_NEEDED` entries
at all (not even for the bionic libs that AOSP would normally pull
in). Verify with the NDK's `llvm-readelf`:

```sh
$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin/llvm-readelf \
    -d arm64-v8a/libaapt2.so | grep NEEDED
# (no output expected)

file arm64-v8a/libaapt2.so
# ELF 64-bit LSB executable, ARM aarch64, ..., statically linked, stripped
```

Anything dynamic (`libc++_shared.so`, `libprotobuf-lite.so`,
`libpng16.so`, `libexpat.so`, `libz.so`, etc.) means `dlopen` will
fail at runtime on a stock Android device. The recipe's
`CMakeLists.txt` sets `-static`; if that flag isn't reaching every
dep, the audit above catches it before the binary lands here.

## Why `lib*.so` and not just `aapt2`

Android's library plugin only packages files whose names match
`lib*.so` into `jni/<abi>/` inside the AAR; the manifest also needs
`android:extractNativeLibs="true"` and AGP's
`useLegacyPackaging = true` to keep the binaries on-disk uncompressed
(so `ProcessBuilder` can `exec()` them, since `dlopen()` isn't being
used). All three flags live in the parent module's
`build.gradle.kts` / `AndroidManifest.xml`.

`ApktoolAndroid.configure(Context)` resolves
`Context.applicationInfo.nativeLibraryDir + "/libaapt2.so"` and feeds
it to `Config.setAaptBinary(...)`.

## Refresh procedure

See `aapt2-build/README.md` for the full flow. Short version:

```sh
# (one-time setup omitted: clone ReVanced/aapt2 with submodules, symlink them
#  into aapt2-build/recipe/submodules, install protoc 21.12)

cd aapt2-build/recipe
./patch.sh
export ANDROID_NDK="$HOME/Library/Android/sdk/ndk/27.1.12297006"

mkdir -p out
for abi in arm64-v8a armeabi-v7a x86_64; do
    rm -rf build
    ./build.sh $abi
    cp build/bin/aapt2-$abi out/
done

cd ../..
for abi in arm64-v8a armeabi-v7a x86_64; do
    cp aapt2-build/recipe/out/aapt2-$abi \
       brut.apktool/apktool-android/src/main/jniLibs/$abi/libaapt2.so
done
```

Then commit the three `.so` files (and bump `SELF_BUILD_PLAN.md`'s
binary-size table if the deltas are noticeable).

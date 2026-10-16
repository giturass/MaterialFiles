# aapt2-build

Source of the bionic-static `libaapt2.so` binaries that ship inside the
`apktool-android` AAR. Binaries are produced from a vendored copy of
[ReVanced/aapt2](https://github.com/ReVanced/aapt2)'s CMake recipe, with
iBotPeaches/Tumbleson PR #3130 layered on top so the result handles the
modern `<item type="layout" format="...">` resource patterns that
ReVanced's v1.1.0 prebuilts reject.

## Layout

```
aapt2-build/
├── README.md                — this file
├── SELF_BUILD_PLAN.md       — full execution plan + recipe gotchas
├── patches/                 — Apktool leniency patches (see patches/README.md)
│   ├── 0001-aapt2-port-changes-for-apktool.patch
│   └── 0002-aapt2-unrestrict-item-tag-value-format.patch
└── recipe/                  — vendored ReVanced/aapt2 build recipe
    ├── CMakeLists.txt
    ├── build.sh             — host-aware, lightly patched
    ├── patch.sh             — host-aware, switched to patch -p1
    ├── cmake/
    ├── misc/
    ├── patches/             — recipe-local patch set; sees both
    │   │                       ReVanced's patches AND our 0002
    │   ├── apktool_ibotpeaches.patch   — ReVanced (kept verbatim)
    │   ├── protobuf.patch              — ReVanced (build adaptation)
    │   ├── 32bsystem_on_armv8.patch    — ReVanced (armv7 BusError)
    │   └── 0002-aapt2-unrestrict-item-tag-value-format.patch
    └── README.md            — upstream ReVanced README + our delta
```

`recipe/submodules/` (the AOSP source roots) is intentionally NOT
checked in; developers symlink it from a scratch clone of
`ReVanced/aapt2`. `recipe/build/` and `recipe/out/` are also gitignored.

## Output contract

The end state is the three files Gradle picks up:

```
brut.apktool/apktool-android/src/main/jniLibs/arm64-v8a/libaapt2.so
brut.apktool/apktool-android/src/main/jniLibs/armeabi-v7a/libaapt2.so
brut.apktool/apktool-android/src/main/jniLibs/x86_64/libaapt2.so
```

Each `.so` MUST be statically linked (no `DT_NEEDED` entries). Verify
with the NDK's `llvm-readelf`:

```sh
"$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin/llvm-readelf" \
    -d brut.apktool/apktool-android/src/main/jniLibs/arm64-v8a/libaapt2.so \
    | grep NEEDED
# (no output expected — fully static)

file brut.apktool/apktool-android/src/main/jniLibs/arm64-v8a/libaapt2.so
# ELF 64-bit LSB executable, ARM aarch64, ..., statically linked, stripped
```

If `NEEDED` entries do appear (e.g. `libc++_shared.so`,
`libprotobuf-lite.so`, `libpng16.so`), the binary will fail to load
from `nativeLibraryDir` on stock Android. The recipe's `CMakeLists.txt`
sets `CMAKE_EXE_LINKER_FLAGS "-static"` and `PNG_SHARED=OFF`; if you
end up with dynamic deps, that flag isn't reaching every dep.

## How to (re)build

Five steps, ~5 minutes once the deps are installed.

### 1. One-time: clone ReVanced's recipe with submodules

The 17 AOSP submodules (~1.2 GB shallow) live OUTSIDE this repo to keep
git history clean. Clone them once into a scratch dir:

```sh
git clone --recurse-submodules --shallow-submodules --depth 1 \
    https://github.com/ReVanced/aapt2 ~/work/revanced-aapt2
```

### 2. One-time: symlink the submodules into the recipe

```sh
ln -sfn ~/work/revanced-aapt2/submodules \
    aapt2-build/recipe/submodules
```

(The symlink is gitignored.)

### 3. One-time: install protoc 21.12

The recipe pins this exact protoc version; newer ones generate
incompatible `.pb.h` headers.

```sh
# macOS
brew install protobuf@21
brew link --force protobuf@21
protoc --version    # expected: libprotoc 3.21.12

# Linux
wget https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-linux-x86_64.zip
sudo unzip -p protoc-*.zip bin/protoc -d /usr/local/bin/
protoc --version    # expected: libprotoc 3.21.12
```

### 4. Apply patches once per `git submodule update`

```sh
cd aapt2-build/recipe
./patch.sh
```

`patch.sh` mutates files in the symlinked AOSP tree. To re-run:

```sh
cd ~/work/revanced-aapt2
git submodule foreach --recursive 'git checkout -- . && git clean -fd'
git checkout -- .
git clean -fd
cd -
cd aapt2-build/recipe
./patch.sh
```

### 5. Build per ABI and install

```sh
cd aapt2-build/recipe
export ANDROID_NDK="$HOME/Library/Android/sdk/ndk/27.1.12297006"   # adjust

mkdir -p out
for abi in arm64-v8a armeabi-v7a x86_64; do
    rm -rf build      # CMake refuses to swap toolchain mid-config
    ./build.sh $abi
    cp build/bin/aapt2-$abi out/
done

# Install over the previously committed binaries.
cd ../..
for abi in arm64-v8a armeabi-v7a x86_64; do
    cp aapt2-build/recipe/out/aapt2-$abi \
       brut.apktool/apktool-android/src/main/jniLibs/$abi/libaapt2.so
    chmod 0755 brut.apktool/apktool-android/src/main/jniLibs/$abi/libaapt2.so
done
```

### 6. Run instrumented tests to confirm

```sh
./gradlew :brut.apktool:apktool-android-test:uninstallDebugAndroidTest \
          :brut.apktool:apktool-android-test:connectedDebugAndroidTest \
          --stacktrace
# Expected: 3 of 3 tests pass, including featureFlagsBuild against modern.apk.
```

## Patch stack

Applied by `recipe/patch.sh` in this exact order against
`recipe/submodules/base/...` (i.e. AOSP `frameworks/base`):

| # | Patch | Source | Purpose |
|---|-------|--------|---------|
| 1 | `apktool_ibotpeaches.patch` | ReVanced (vendored) | Apktool leniency baseline + AOSP runtime fixes (MCC/MNC parser, `stringToFloat`, etc.). Already covers everything in our `patches/0001-...patch`. |
| 2 | `protobuf.patch` | ReVanced | Build adaptation. |
| 3 | `32bsystem_on_armv8.patch` | ReVanced | armv7 BusError fix. |
| 4 | `0002-aapt2-unrestrict-item-tag-value-format.patch` | iBotPeaches PR #3130 (apktool-3.0.x), re-rooted to `submodules/base/...` paths and with `android::DiagMessage` qualifier preserved | Fixes `error: invalid value for type 'layout'. Expected a reference.` against modern Play-Store APKs. **The reason we're here.** |

`patches/0001-aapt2-port-changes-for-apktool.patch` is kept in-repo as
a documentary record of what iBotPeaches has on his `apktool-3.0.x`
branch; it is NOT applied during the build, because ReVanced's patch
(1) already includes everything in it (and more).

## Licence story

ReVanced's build scripts (under `recipe/`) are GPL-3.0; ReVanced's
`recipe/LICENSE` file is preserved verbatim. The OUTPUT binary is
derived from:

- AOSP `frameworks/base` source (Apache-2.0)
- iBotPeaches/Tumbleson patches (Apache-2.0)
- Statically-linked deps: AOSP `libpng` (libpng licence), `expat`
  (MIT), `protobuf-lite` (BSD-3-Clause), `fmtlib` (MIT), `boringssl`
  (OpenSSL-style), `pcre` (BSD), `jsoncpp` (MIT/Public).

**No GPL-3.0 code is statically linked into the output binary.** The
binary itself stays under the AOSP/Apktool licence stack (Apache-2.0),
matching the licence Apktool uses for the desktop aapt2 it bundles.

The AAR (`apktool-android.aar`) only contains the `.so` files — Gradle
never sees the GPL-3.0 build scripts under `recipe/`. Hosts consuming
the AAR are unaffected by the GPL-3.0 of the build recipe.

## Why this lives outside Gradle

1. The AOSP submodule tree (~1.2 GB shallow, ~20 GB if you go deep) is
   too unwieldy to vendor inside the repo; Gradle would also try to
   download it on first sync.
2. Building `aapt2` from source takes ~1 minute per ABI (~3 min total);
   running on every `./gradlew assemble` would be unacceptable.
3. The artifacts only need refreshing when the AOSP pin or a leniency
   patch changes (yearly cadence at most).

So the contract is: humans run the steps above when those events
happen, commit the `.so` files into
`apktool-android/src/main/jniLibs/`, and Gradle just packages them
into the AAR.

## Further reading

- `SELF_BUILD_PLAN.md` — phase-by-phase execution plan (what shipped,
  what's optional, the full risk register, and the recipe gotchas
  worth knowing if you ever need to refresh the binaries)
- `recipe/README.md` — upstream ReVanced README + an Apktool-Android
  delta header
- `patches/README.md` — patch curation rules + how to re-export from
  iBotPeaches's `apktool-3.0.x` branch

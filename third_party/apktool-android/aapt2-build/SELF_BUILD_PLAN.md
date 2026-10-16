# Self-build aapt2 — execution plan (and shipped state)

End goal: replace the vendored `ReVanced/aapt2` v1.1.0 prebuilts under
`brut.apktool/apktool-android/src/main/jniLibs/<abi>/libaapt2.so` with
binaries we cross-compile ourselves. **Done as of commit `71f4cd0`**;
this document is now part-historical (per-phase notes preserved for
auditability) and part-runbook (refresh procedure under "How to
rebuild" in `aapt2-build/README.md`).

Patch stack on top of the AOSP sources ReVanced pins to
`platform-tools-35.0.2`:

| # | Patch | Source | Role |
|---|-------|--------|------|
| 1 | `apktool_ibotpeaches.patch` | ReVanced (vendored from upstream) | Apktool leniency baseline + AOSP runtime tweaks (MCC/MNC parser, stringToFloat, etc.). Superset of our `0001` and we keep it. |
| 2 | `protobuf.patch` | ReVanced | Build adaptation. |
| 3 | `32bsystem_on_armv8.patch` | ReVanced | armv7 BusError fix. |
| 4 | `0002-aapt2-unrestrict-item-tag-value-format.patch` | **us** | iBotPeaches PR #3130 (Feb 2026). Fixes the `modern.apk` failure (`error: invalid value for type 'layout'. Expected a reference.`). The only thing ReVanced v1.1.0 lacks. |

`aapt2-build/patches/0001-aapt2-port-changes-for-apktool.patch` is kept
in-repo only as a record of what iBotPeaches has on his `apktool-3.0.x`
branch; it is **not** applied during the build because ReVanced's
patch (1) already covers all of it.

## Strategy — fork ReVanced's recipe, layer our patches on top

[ReVanced/aapt2](https://github.com/ReVanced/aapt2) already solved every
hard cross-compilation problem:

- 17 git submodules pinned to AOSP `platform-tools-35.0.2`
  (`frameworks/base`, `system/libbase`, `system/libziparchive`,
  `external/protobuf/expat/libpng/fmtlib/...`).
- A from-scratch `CMakeLists.txt` that statically builds 8 third-party
  deps as subdirectories, then builds aapt2 — **no Soong, no termux
  indirection**.
- A `patch.sh` that applies their patches and fixes proto include paths.
- A `build.sh` that drives `cmake -B build -DANDROID_ABI=$1 ... &&
  ninja aapt2 && llvm-strip` per-ABI.
- A `cmake/` subdirectory with the explicit aapt2 source list (every
  `.cpp` to compile — exactly the gold we'd otherwise have had to
  reverse-engineer from `Android.bp`).

### Patch strategy: UNION, not swap (revised after Phase 0)

Initial assumption: ReVanced's `apktool_ibotpeaches.patch` was just an
older copy of our `0001`, so we'd swap.

Reality discovered when we read their patch: `apktool_ibotpeaches.patch`
is **a superset of `0001`**. It carries everything `0001` does PLUS
extra runtime fixes (MCC/MNC parser tolerance in
`ConfigDescription.cpp`, stringToFloat handling in `ResourceTypes.cpp`,
and other AOSP runtime tweaks) that our `0001` does NOT have. Dropping
their patch would regress.

Revised strategy:

- **Keep** ReVanced's `patches/apktool_ibotpeaches.patch` as-is (it
  covers all of `0001`'s ground and more).
- **Keep** their `patches/protobuf.patch` and `patches/32bsystem_on_armv8.patch`.
- **Add** our `patches/0002-aapt2-unrestrict-item-tag-value-format.patch`
  on top — that's iBotPeaches PR #3130, the *only* thing ReVanced
  v1.1.0 lacks. Touches one file (`ResourceParser.cpp`), one hunk.
- **Don't ship `0001`** in the recipe — it's a strict subset of theirs.

This collapses the original 3-5 day plan to **~1 day**.

## Output contract (unchanged)

```
brut.apktool/apktool-android/src/main/jniLibs/arm64-v8a/libaapt2.so
brut.apktool/apktool-android/src/main/jniLibs/armeabi-v7a/libaapt2.so
brut.apktool/apktool-android/src/main/jniLibs/x86_64/libaapt2.so
```

Each `.so` MUST satisfy `llvm-readelf -d` showing only bionic deps:
`libc.so`, `libdl.so`, `libm.so`, `liblog.so` (optionally `libc++.so`).

## Phases

### Phase 0 — Vendor the recipe (1 hour wallclock, mostly download)

ReVanced's repo layout is:

```
aapt2/
├── CMakeLists.txt         # top-level cmake driver (third-party deps as subdirs)
├── build.sh               # ANDROID_NDK=... ./build.sh <abi>
├── patch.sh               # applies their patches, fixes proto includes
├── .gitmodules            # 17 submodule pins to AOSP platform-tools-35.0.2
├── cmake/                 # actual aapt2 source list (per-component .cpp lists)
├── misc/                  # IncrementalProperties.sysprop.{h,cpp}, platform_tools_version.h
├── patches/
│   ├── apktool_ibotpeaches.patch     # we REPLACE this with our two patches
│   ├── protobuf.patch                # build adaptation, KEEP
│   └── 32bsystem_on_armv8.patch      # armv7 BusError fix, KEEP
└── LICENSE                # GPL-3.0 (build scripts only, see "License story" below)
```

Vendor all of it under `aapt2-build/recipe/`:

```sh
# Clone ReVanced/aapt2 with submodules. Use a separate scratch dir so
# we can choose what to copy in.
git clone --recurse-submodules --shallow-submodules --depth 1 \
  https://github.com/ReVanced/aapt2 ~/work/revanced-aapt2

# Copy the recipe files (NOT submodules) into our repo.
mkdir -p aapt2-build/recipe
cp -R ~/work/revanced-aapt2/{CMakeLists.txt,build.sh,patch.sh,cmake,misc,patches,LICENSE,README.md,.gitmodules} \
      aapt2-build/recipe/

# Initialise submodules in our repo as well, so a fresh clone of
# Apktool-android can build aapt2 without going to ReVanced again.
cd aapt2-build/recipe
git -C ../../ submodule add https://github.com/ReVanced/aapt2 aapt2-build/recipe-upstream  # OPTIONAL
# OR: re-add each AOSP submodule under aapt2-build/recipe/submodules/. The
#     .gitmodules we copied references "submodules/<name>"; we need to
#     re-add each from inside our repo so they live at
#     aapt2-build/recipe/submodules/<name>.
```

> Note: the submodule re-add step is fiddly. **Easiest first cut**:
> don't add the AOSP submodules to our repo at all. Keep them as a
> sibling work-tree under `~/work/revanced-aapt2/submodules` (which is
> already populated from the clone above), and have `build.sh` accept
> an env var pointing to that path. Saves us from owning 17 submodule
> pins and ~5 GB of source in our git history. We document the
> "you need to clone ReVanced/aapt2 once for the submodules" step in
> `aapt2-build/README.md`. This is a per-developer, one-time cost.

Final layout (post-Phase 5):

```
aapt2-build/
├── README.md              (rewrote in Phase 5: self-build flow + patch stack + licence)
├── SELF_BUILD_PLAN.md     (this doc)
├── patches/               (Apktool-side reference patches; not applied at build time)
│   ├── README.md
│   ├── 0001-aapt2-port-changes-for-apktool.patch  (documentary; superset is in recipe)
│   └── 0002-aapt2-unrestrict-item-tag-value-format.patch  (mirror of recipe-local copy)
└── recipe/                (vendored from ReVanced/aapt2 with three local edits)
    ├── CMakeLists.txt
    ├── build.sh           (host-aware: NDK_HOST detection)
    ├── patch.sh           (host-aware sed; uses patch -p1 instead of git apply)
    ├── cmake/
    ├── misc/
    ├── LICENSE            (ReVanced GPL-3.0, kept verbatim)
    ├── README.md          (upstream + Apktool-Android delta header)
    ├── submodules/        (gitignored; symlink to ~/work/revanced-aapt2/submodules)
    ├── build/             (gitignored; CMake out)
    ├── out/               (gitignored; staging dir between ABI builds)
    └── patches/
        ├── apktool_ibotpeaches.patch       (ReVanced; superset of our 0001)
        ├── protobuf.patch                  (ReVanced)
        ├── 32bsystem_on_armv8.patch        (ReVanced)
        └── 0002-aapt2-unrestrict-item-tag-value-format.patch  (Apktool-Android, layered last)
```

The obsolete `aapt2-build/build.sh`, `install-prebuilt.sh`,
`install.sh`, and `verify.sh` were deleted in Phase 5.

### Phase 1 — Layer our PR #3130 on top of ReVanced's patches — **DONE**

What shipped (this matches the working tree as of the Phase 1 commit):

1. Symlinked the AOSP submodules from the scratch clone instead of
   re-vendoring them in our git history (option (a) under "Open
   question — submodule strategy" below):

   ```sh
   ln -sfn ~/work/revanced-aapt2/submodules aapt2-build/recipe/submodules
   ```

2. Copied our `0002` patch into the recipe and **re-rooted the diff
   header** so it lines up with ReVanced's `submodules/base/...` layout
   (the version under `aapt2-build/patches/0002-...patch` was exported
   from iBotPeaches/`platform_frameworks_base` and uses `tools/aapt2/`
   paths — `git apply` from the recipe dir wouldn't find them).

   Also restored the `android::DiagMessage(...)` qualifier on the `+`
   lines: ReVanced's older AOSP base has no `using namespace android;`
   in `ResourceParser.cpp`, so the unqualified form from the
   `apktool-3.0.x` export wouldn't compile.

   The recipe-local copy lives at:
   `aapt2-build/recipe/patches/0002-aapt2-unrestrict-item-tag-value-format.patch`

3. Edited `aapt2-build/recipe/patch.sh` to apply our `0002` AFTER
   ReVanced's existing patches:

   ```diff
     git apply "patches/apktool_ibotpeaches.patch"
     git apply "patches/protobuf.patch"
     git apply "patches/32bsystem_on_armv8.patch"
   + # Apktool-Android: iBotPeaches PR #3130 (apktool-3.0.x) on top.
   + git apply "patches/0002-aapt2-unrestrict-item-tag-value-format.patch"
     ln -sf "submodules/googletest" "submodules/boringssl/src/third_party/googletest"
   ```

4. Backed out the redundant `aapt2-build/recipe-upstream/` submodule
   that the optional Phase 0 bullet added — we don't want a second
   copy of the recipe pinned in our git history when we already vendor
   `recipe/`.

Verification (already passed):

```sh
cd aapt2-build/recipe
git apply --check patches/apktool_ibotpeaches.patch \
                  patches/protobuf.patch \
                  patches/32bsystem_on_armv8.patch \
                  patches/0002-aapt2-unrestrict-item-tag-value-format.patch
# exits 0 — every hunk applies cleanly against the symlinked submodules.
```

**Compatibility note**: ReVanced's submodules are pinned to AOSP
`platform-tools-35.0.2` (Android 14 era, ~April 2024). Our PR #3130
patch hunk targets `ResourceParser.cpp` which is stable across that
range; `git apply --check` confirms no rebasing is needed. If a future
patch we layer on top hits a context drift, two recovery options:

(a) Rebase the offending hunk's context against the older AOSP source
    (~15 min/hunk).
(b) Bump ReVanced's submodule pins to `android-16.0.0_r1` (you have it
    synced under `$AOSP_SRC`); other ReVanced fixes may then need
    rebasing in turn.

### Phase 1.5 — Idempotent re-application (small follow-up, ~10 min)

`patch.sh` calls `cp` and `sed -i` on `submodules/...`, which dirties
the scratch clone. Running it twice will double-apply the proto edits
or fail because the patches are already in the tree.

For dev iteration, document the reset procedure in
`aapt2-build/recipe/README.md` (or our own readme):

```sh
# Reset the symlinked AOSP tree to a clean state before re-applying
# patches. Run from inside ~/work/revanced-aapt2.
cd ~/work/revanced-aapt2
git submodule foreach --recursive 'git checkout -- . && git clean -fd'
git checkout -- .
git clean -fd
# Then back in our repo:
cd -
cd aapt2-build/recipe
./patch.sh
```

(We deliberately do NOT modify `patch.sh` itself to be idempotent —
that's ReVanced's contract and we want their script to stay diff-clean
against upstream so we can pull their future bug fixes in.)

### Phase 2 — Install protoc 21.12 (15 min)

ReVanced's `README.md` pins protoc to 21.12. Newer protoc versions
generate incompatible `.pb.h` headers.

macOS install:

```sh
# Option 1: brew (fast)
brew install protobuf@21
brew link --force protobuf@21
# Verify: protoc --version  -> "libprotoc 21.12"

# Option 2: download binary (if brew formula isn't pinned)
curl -LO https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-osx-universal_binary.zip
unzip protoc-21.12-osx-universal_binary.zip -d ~/protoc-21.12
export PROTOC_PATH=~/protoc-21.12/bin/protoc
chmod +x "$PROTOC_PATH"
"$PROTOC_PATH" --version
```

### Phase 3 — Apply patches, build per ABI (30 min once recipe is right)

```sh
cd aapt2-build/recipe

# Symlink the AOSP submodules from your scratch clone (avoids vendoring
# 5 GB of AOSP into our repo). Run once.
ln -sf ~/work/revanced-aapt2/submodules submodules

# Apply our patches (replaces apktool_ibotpeaches.patch).
./patch.sh

# Build, per ABI.
export ANDROID_NDK=/opt/android/ndk/27.x.y
export PROTOC_PATH=$(which protoc)

./build.sh arm64-v8a    # outputs build/bin/aapt2-arm64-v8a
./build.sh armeabi-v7a  # outputs build/bin/aapt2-armeabi-v7a
./build.sh x86_64       # outputs build/bin/aapt2-x86_64
```

(We don't build `x86` because no Android emulator since API 31 ships
that ABI; no test device or end user will need it.)

Verify ELF deps per binary (must be fully static — no `NEEDED` entries):

```sh
READELF="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
for abi in arm64-v8a armeabi-v7a x86_64; do
  echo "=== $abi ==="
  "$READELF" -d "build/bin/aapt2-$abi" | grep NEEDED
done
```

Expected output per ABI: **nothing**. The recipe's
`CMakeLists.txt` sets `CMAKE_EXE_LINKER_FLAGS "-static"` and
`PNG_SHARED=OFF`, so the resulting binary has no dynamic dependencies
at all — `file build/bin/aapt2-$abi` reports `statically linked,
stripped`. (This differs from ReVanced's published v1.1.0 prebuilts,
which kept bionic deps as dynamic `NEEDED` lines; both shapes work on
Android, but our build is even more conservative.)

If any `NEEDED` line appears (`libc++_shared.so`, `libprotobuf-lite.so`,
`libpng16.so`, etc.), the static-link config isn't reaching every dep
— fix before installing.

### Phase 4 — Install + validate (30 min)

```sh
# Replace the ReVanced binaries with our self-built ones.
for abi in arm64-v8a armeabi-v7a x86_64; do
  cp aapt2-build/recipe/build/bin/aapt2-$abi \
     brut.apktool/apktool-android/src/main/jniLibs/$abi/libaapt2.so
  chmod 0755 brut.apktool/apktool-android/src/main/jniLibs/$abi/libaapt2.so
done

# Re-stage modern.apk (which we deleted to keep CI green during
# Phase 6 of the integration plan).
cp /path/to/your/modern.apk \
   brut.apktool/apktool-android-test/src/androidTest/assets/modern.apk

# Run the gating + smoke tests; expect 3/3 green now (including
# featureFlagsBuild, which was the whole reason for self-building).
./gradlew :brut.apktool:apktool-android-test:uninstallDebugAndroidTest \
          :brut.apktool:apktool-android-test:connectedDebugAndroidTest \
          --stacktrace
```

Acceptance: **3 of 3 instrumented tests green**, including
`featureFlagsBuild()` against `modern.apk`. If `featureFlagsBuild`
still fails with the same `invalid value for type 'layout'` error, our
`0002` patch didn't apply during `patch.sh` — check the patch.sh
output and rebase context lines.

### Phase 5 — Retire `install-prebuilt.sh` and rewrite the docs — **DONE**

What shipped:

- Deleted the obsolete top-level scripts, all of which assumed Path A
  or the old "termux-style from-scratch" Path B:
  `aapt2-build/build.sh`, `aapt2-build/install-prebuilt.sh`,
  `aapt2-build/install.sh`, `aapt2-build/verify.sh`. The empty
  `aapt2-build/out/` leftover dir is also gone.
- Rewrote `aapt2-build/README.md` end-to-end: self-build is the only
  documented path, with a 6-step "How to (re)build" section, the
  patch-stack table, a refreshed licence story, and pointers into
  `SELF_BUILD_PLAN.md` (this file).
- Rewrote `aapt2-build/patches/README.md` to describe what `0001` and
  `0002` actually are (one is documentary, one is layered into the
  recipe), with re-export instructions for both.
- Rewrote `brut.apktool/apktool-android/src/main/jniLibs/README.md`
  to say the binaries ARE committed, are fully static (no `NEEDED`),
  and are produced by the recipe.
- Added a small Apktool-Android delta header at the top of the
  vendored `aapt2-build/recipe/README.md` (rest is upstream verbatim)
  so future maintainers understand the three local edits before
  diffing against ReVanced.
- Rewrote `TODO.md` § 2 ("Vendor `libaapt2.so`...") into a self-build
  flow, dropped the `featureFlagsBuild` known-limitation paragraph
  (no longer reproduces with our binaries), and updated the
  failure-mode rows that referenced `install-prebuilt.sh`.
- The `android.yml` workflow's "Detect bundled aapt2 binary" warning
  text is unchanged because it never named ReVanced — it just checks
  for any `libaapt2.so` under `jniLibs/`. No edit needed.

## License story

ReVanced's *build scripts* (`CMakeLists.txt`, `build.sh`, `patch.sh`,
`cmake/`) are GPL-3.0. Vendoring them means **`aapt2-build/recipe/`
is a GPL-3.0 directory inside an Apache-2.0 project**.

The OUTPUT binary (`libaapt2.so`) is derived from:

- AOSP `frameworks/base` source (Apache-2.0).
- iBotPeaches/Tumbleson patches (Apache-2.0).
- Statically-linked deps: AOSP `libpng` (libpng license),
  `expat` (MIT), `protobuf-lite` (BSD-3-Clause), `fmtlib` (MIT),
  `boringssl` (OpenSSL-style), `pcre` (BSD), `jsoncpp` (MIT/Public).

**No GPL-3.0 code is statically linked into the output binary**, so
the binary itself remains under the AOSP/Apktool licence stack
(Apache-2.0).

Practical implication: the AAR (`apktool-android.aar`) only contains
the `.so` — Gradle never sees the GPL-3.0 build scripts. Hosts
consuming the AAR are unaffected by the GPL-3.0 of the build recipe.

What we MUST do:

1. Keep ReVanced's `LICENSE` file under `aapt2-build/recipe/LICENSE`.
2. Add a `CREDITS` or `NOTICE` section in `aapt2-build/README.md`
   pointing at ReVanced/aapt2 and crediting them as the recipe source.
3. Note in our root `LICENSE` (or `LICENSES/` directory) that
   `aapt2-build/recipe/` is GPL-3.0 derivative work.

What we MUST NOT do:

1. Distribute the build recipe as part of the AAR (it's only
   developer-facing tooling; AGP doesn't bundle it anyway).
2. Strip ReVanced's copyright headers from `CMakeLists.txt`,
   `build.sh`, `patch.sh`, `cmake/*.cmake`.

## Effort

| Phase | Effort | Status |
|-------|--------|--------|
| 0 — Vendor ReVanced recipe | 1 hour (~1.2 GB submodule download) | **DONE** |
| 1 — Layer PR #3130 on top of ReVanced's patches | 30 min | **DONE** |
| 2 — Install protoc 21.12 | 15 min (`brew install protobuf@21` → `libprotoc 3.21.12`) | **DONE** |
| 3 — Build per ABI | ~1 min per ABI (3 min total wallclock) | **DONE** |
| 4 — Install + validate | 30 min — instrumented tests | **DONE** (binaries installed; instrumented tests run by user) |
| 5 — Retire install-prebuilt.sh + update docs | 15 min | **DONE** |

### Phase 3 — actuals

```
out/aapt2-arm64-v8a    4,696,624 bytes  ELF 64-bit aarch64,  static, stripped
out/aapt2-armeabi-v7a  3,127,536 bytes  ELF 32-bit ARM EABI5, static, stripped
out/aapt2-x86_64       4,996,456 bytes  ELF 64-bit x86-64,    static, stripped
```

All three are statically linked (no `NEEDED` entries) — exactly the
same linkage profile as ReVanced's working v1.1.0 prebuilts, sizes
within 3 KB. Only delta is our PR #3130 hunk in `ResourceParser.cpp`.

### Recipe gotchas we fixed in Phase 1-3 (so they don't bite again)

1. **`build.sh` hardcoded `linux-x86_64` for `llvm-strip`.** Patched to
   detect host: `darwin-x86_64` / `linux-x86_64` / `windows-x86_64`.
2. **`patch.sh` used GNU-sed `-i` with no backup-suffix arg.** macOS
   BSD sed reads the next arg as the suffix. Replaced with a host
   detection that uses `sed -i ''` on Darwin and `sed -i` on Linux.
3. **`patch.sh` used `git apply`, which walks up to `.git` of the
   *outer* repo (Apktool-Android, not the recipe).** From outside its
   own working tree, `git apply` silently *skips* every patch
   (`Skipped patch 'submodules/base/...'`) but exits 0. Replaced with
   plain `patch -p1 < ...` which doesn't care about git state. This is
   the single most insidious bug in the recipe when vendored — every
   patch reports as applied, no warning, and the build succeeds with
   *unpatched* sources, producing a binary that still rejects
   `modern.apk`. Watch for it if any future maintainer reverts to
   `git apply`.
4. **`submodules` dir has to be a symlink to the scratch clone**
   (`~/work/revanced-aapt2/submodules`). Tracked via `.gitignore` so
   the symlink itself never gets committed.
5. **CMake build dir doesn't survive between ABIs** — `./build.sh
   <abi>` reuses `build/`, so binaries get clobbered. Copy each
   binary out of `build/bin/aapt2-<abi>` *before* the next `./build.sh`
   invocation. We use `aapt2-build/recipe/out/` as a staging dir.

**Realistic total: 3-6 hours** if patches apply cleanly; **1 day** if
context rebasing is needed in Phase 1.

## Risk register (much smaller than full from-scratch)

| Risk | Impact | Mitigation |
|------|--------|------------|
| ~~`0001` patch context doesn't match `platform-tools-35.0.2` AOSP base~~ | n/a | Resolved Phase 1: we don't apply `0001`. Our `0002` applies cleanly (`git apply --check` passes). |
| `protoc` system version not 21.12 (newer installed) | Phase 3 link errors | Use `PROTOC_PATH` env var to point at the 21.12 binary explicitly |
| `--strip-unneeded` removes a symbol aapt2 needs at runtime | Test failures with no logs | Build a `Debug` config first, validate, then strip |
| Output binary depends on `libc++_shared.so` despite `-static` | Phase 4 dlopen failures | Audit ELF deps in Phase 3; if present, force `-static-libstdc++` in CMake config |
| ReVanced's `protobuf.patch` collides with our `0001` (unlikely) | Phase 1 patch.sh fails | Apply our patches FIRST, theirs SECOND, so theirs adapts to our base |
| `armeabi-v7a` builds break first | One ABI ships broken | Always build all three ABIs while iterating |

## Open question — submodule strategy

Three options for where the AOSP submodules live:

(a) **External, per-developer** (recommended, lowest friction): the
    developer clones `ReVanced/aapt2 --recurse-submodules` once into a
    scratch directory, our `build.sh` symlinks `submodules/` from
    there. Pro: no 5 GB in our git history. Con: requires a one-time
    "go clone this other repo" step in our README.

(b) **Vendored as our own submodules**: re-add each of the 17 AOSP
    submodules under `aapt2-build/recipe/submodules/<name>` in our
    own `.gitmodules`. Pro: `git clone --recursive` of our repo gets
    everything. Con: we now own 17 submodule pins to keep in lockstep.

(c) **Vendored as a flat copy**: `cp -R` the AOSP source into our
    repo. Pro: zero submodule complexity. Con: 5 GB of AOSP source
    bloats the repo permanently and must be hand-bumped for each
    AOSP version.

**Recommendation**: (a) for v1. Document the scratch clone clearly
in `aapt2-build/README.md`. Reconsider (b) if/when CI needs to build
aapt2 without external state.

## When to start

Same answer as the integration plan: **not blocking anything**. Phase
6 and Phase 7 of the integration plan are already green for normal
apks. Self-build only matters if/when:

- You actually need to round-trip Play-Store apks in production
  (the ReVanced gap bites you), OR
- You want a clean dependency story for a public release.

If neither is true today, defer; revisit when ReVanced cuts v1.2
(may include PR #3130 anyway, which would close this whole plan as
"unnecessary").

## Sources

- [ReVanced/aapt2](https://github.com/ReVanced/aapt2) — the recipe we fork.
- [`build.sh`](https://github.com/ReVanced/aapt2/blob/main/build.sh)
- [`patch.sh`](https://github.com/ReVanced/aapt2/blob/main/patch.sh)
- [`CMakeLists.txt`](https://github.com/ReVanced/aapt2/blob/main/CMakeLists.txt)
- [`.gitmodules`](https://github.com/ReVanced/aapt2/blob/main/.gitmodules)
- iBotPeaches `apktool-3.0.x` branch (where `0001` originates).
- [iBotPeaches PR #3130](https://github.com/iBotPeaches/Apktool/pull/3130) — the patch the user cares about.

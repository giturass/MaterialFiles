# aapt2-build/patches

Apktool leniency patches against AOSP `frameworks/base/tools/aapt2`.
This directory holds two unified diffs:

| File | Source | Applied during build? |
|------|--------|-----------------------|
| `0001-aapt2-port-changes-for-apktool.patch` | iBotPeaches `platform_frameworks_base@apktool-3.0.x` (full export against `android-16.0.0_r1`) | **No.** Kept as a reference of the desktop Apktool baseline. ReVanced's `apktool_ibotpeaches.patch` (under `aapt2-build/recipe/patches/`) is a superset of this, and that one IS applied. |
| `0002-aapt2-unrestrict-item-tag-value-format.patch` | iBotPeaches PR #3130 against `apktool-3.0.x` (`unrestrict-item-tag-value-format`) | **No, not from this directory.** A re-rooted copy lives at `aapt2-build/recipe/patches/0002-aapt2-unrestrict-item-tag-value-format.patch` and is applied by `recipe/patch.sh` after ReVanced's patch set. |

In short: the build flow goes through `aapt2-build/recipe/patches/`,
not this directory. Files here exist so the canonical Apktool patch
set is auditable in-tree (and so future maintainers can diff our
recipe-local patch against the desktop original).

## Why both 0001 and 0002 are not applied here

Two reasons:

1. The recipe in `aapt2-build/recipe/` already ships
   `apktool_ibotpeaches.patch`, which is a STRICT SUPERSET of `0001`.
   ReVanced exported it from iBotPeaches's `apktool_14.0.0` branch and
   it brings in additional AOSP-side fixes (MCC/MNC parser, etc.) that
   `0001` doesn't have. Layering `0001` on top of that would create
   spurious whitespace conflicts.
2. `0002` is the one new piece we add on top of ReVanced (the only
   patch that's "ours" in the build). Its recipe-local twin lives next
   to the recipe to keep the build self-contained.

If ReVanced ever bumps their `apktool_ibotpeaches.patch` to include
PR #3130, we can drop the recipe-local `0002` and this directory
becomes purely documentary.

## Patch path conventions (for the recipe-local `0002`)

`aapt2-build/recipe/patches/0002-...patch` has its diff headers
re-rooted from the standard `a/ResourceParser.cpp` form to:

```
--- a/submodules/base/tools/aapt2/ResourceParser.cpp
+++ b/submodules/base/tools/aapt2/ResourceParser.cpp
```

so `patch -p1` (run from `recipe/`) can find the file via the
`submodules/` symlink. The diff body also explicitly qualifies
`android::DiagMessage` because the AOSP base ReVanced pins is older
than the iBotPeaches branch the upstream PR was authored against, and
the older code lacks a `using namespace android;` in scope.

If you re-export this patch from a fresher iBotPeaches checkout, you
must redo BOTH transformations.

## Re-exporting the Apktool baseline (0001)

The reference snapshot in `0001-aapt2-port-changes-for-apktool.patch`
was produced against `android-16.0.0_r1`. To refresh it (e.g. when
iBotPeaches rebases his branch onto a newer AOSP tag):

```sh
cd /path/to/iBotPeaches/platform_frameworks_base
git fetch origin apktool-3.0.x
git format-patch \
  --output-directory /path/to/Apktool-Android/aapt2-build/patches \
  --stdout \
  --src-prefix=a/ \
  --dst-prefix=b/ \
  android-<NEW_TAG>..apktool-3.0.x -- tools/aapt2 \
  > 0001-aapt2-port-changes-for-apktool.patch
```

Naming: keep the `0001-` prefix and the snake-cased description. This
file is documentary; the build is unaffected.

## Re-exporting PR #3130 (0002)

If the upstream PR moves (e.g. iBotPeaches force-pushes), refresh both
the canonical copy here AND the recipe-local copy. The fastest path:

```sh
cd /path/to/iBotPeaches/platform_frameworks_base
git checkout apktool-3.0.x
git format-patch -1 <pr3130_commit> --src-prefix=a/ --dst-prefix=b/ \
  --stdout > /path/to/Apktool-Android/aapt2-build/patches/0002-aapt2-unrestrict-item-tag-value-format.patch
```

Then re-root and re-qualify it for the recipe — see "Patch path
conventions" above — and overwrite
`aapt2-build/recipe/patches/0002-aapt2-unrestrict-item-tag-value-format.patch`.

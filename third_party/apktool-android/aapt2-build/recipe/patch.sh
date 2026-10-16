#!/bin/bash

# macOS BSD sed needs an explicit empty backup-suffix argument for -i;
# GNU sed (Linux) accepts -i with no argument. Pick the right invocation.
case "$(uname -s)" in
  Darwin) SED_I=( sed -i '' ) ;;
  *)      SED_I=( sed -i    ) ;;
esac

mkdir -p "submodules/incremental_delivery/sysprop/include/"
cp "misc/IncrementalProperties.sysprop.h" "submodules/incremental_delivery/sysprop/include/"
cp "misc/IncrementalProperties.sysprop.cpp" "submodules/incremental_delivery/sysprop/"

cp "misc/platform_tools_version.h" "submodules/soong/cc/libbuildversion/include"

# As our sources are in submodules and not in frameworks/base/tools/aapt2 we need to change the inclusions
configPattern="s#frameworks/base/tools/aapt2/Configuration.proto#Configuration.proto#g"
ressourcesPattern="s#frameworks/base/tools/aapt2/Resources.proto#Resources.proto#g"

"${SED_I[@]}" "$configPattern" "submodules/base/tools/aapt2/Resources.proto"
"${SED_I[@]}" "$configPattern" "submodules/base/tools/aapt2/ResourcesInternal.proto"

"${SED_I[@]}" "$ressourcesPattern" "submodules/base/tools/aapt2/ApkInfo.proto"
"${SED_I[@]}" "$ressourcesPattern" "submodules/base/tools/aapt2/ResourcesInternal.proto"


# Apktool-Android note: ReVanced's upstream uses `git apply` here because
# their script is intended to run inside the ReVanced/aapt2 git repo.
# We vendor the recipe into another git repo (Apktool-Android), so
# `git apply` walks up to THAT repo's working tree and silently skips
# every patch (paths don't resolve from there). Plain `patch -p1` doesn't
# care about git state and works regardless of where the recipe lives.

# Apply modification made by IBotPeaches for apktool
patch -p1 < "patches/apktool_ibotpeaches.patch"
patch -p1 < "patches/protobuf.patch"

# Fix BusError when executing a 32b binary on a armv8 with compatibility mode
patch -p1 < "patches/32bsystem_on_armv8.patch"

# Apktool-Android: layered patches we apply on top of ReVanced's recipe.
# 0002 = iBotPeaches PR #3130 (apktool-3.0.x), the missing piece in
# ReVanced/aapt2 v1.1.0 that causes modern.apk to fail with
# "invalid value for type 'layout'. Expected a reference."
patch -p1 < "patches/0002-aapt2-unrestrict-item-tag-value-format.patch"

ln -sf "submodules/googletest" "submodules/boringssl/src/third_party/googletest"

# apktool-android — Android usage

How to embed **apktool-android** in an application: Gradle, manifest, API surface, and operational notes.

## Requirements

- **minSdk 26** — `nativeLibraryDir` is the supported place to `exec()` the bundled `libaapt2.so` on current Android versions.
- **ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86_64` (matching the shipped `jniLibs`).

## Quick start

```kotlin
import brut.androlib.android.ApktoolAndroid
import brut.androlib.ApkDecoder
import brut.androlib.ApkBuilder
import java.io.File

// On a background thread (Dispatchers.IO, WorkManager, foreground service, ...)
val config = ApktoolAndroid.configure(context)   // wires aapt2 + framework + temp dirs
config.isForced = true                           // optional: see options table below

val workDir = File(context.filesDir, "work").apply { mkdirs() }
val decoded = File(workDir, "decoded").apply { mkdirs() }

ApkDecoder(File(workDir, "input.apk"), config).decode(decoded)
// ... mutate `decoded/` here ...
ApkBuilder(decoded, config).build(File(workDir, "rebuilt.apk"))
```

`ApktoolAndroid.configure(context)` returns a fully-wired
[`Config`](brut.apktool/apktool-lib/src/main/java/brut/androlib/Config.java)
with three Android-specific paths populated:

| Field | Value set by `configure()` |
|-------|----------------------------|
| `aaptBinary` | `applicationInfo.nativeLibraryDir + "/libaapt2.so"` |
| `frameworkDirectory` | `filesDir/apktool/framework` (created if missing) |
| `tempDirectory` | `cacheDir/apktool` (created if missing; also propagated to `brut.util.OS` so `File.createTempFile` inside apktool-lib resolves here instead of `/tmp`) |

Override any of these after `configure()` if you need a custom layout.

## Gradle integration

### Why `implementation(files("…aar"))` alone is wrong

The GitHub Release **AAR is not a fat archive**. Almost all of its size is **`libaapt2.so`** per ABI; **`classes.jar` is only the thin `ApktoolAndroid` wrapper** (on the order of a few kilobytes). Everything else—`apktool-lib`, `brut.j.*`, Guava, Commons, smali/baksmali, pngj, etc.—lives on the module’s **`api` / `implementation` classpath**.

Gradle **`implementation(files("libs/….aar"))`** and plain **`flatDir`** **do not read a POM** and **do not pull those dependencies**. You will compile with an empty effective classpath for Apktool unless you add the **runtime JAR bundle** (below) or consume a **single Maven coordinate** whose POM lists transitives.

### Recommended: AAR + runtime JAR directory

Use one directory in the host app for both artifacts:

```text
app/libs/apktool/
├── apktool-android-1.0.0.aar          # from GitHub Releases (same version as the bundle)
└── runtime/                           # output of collectRuntimeJars (see below)
    ├── apktool-lib.jar
    ├── brut.j.common.jar
    ├── … (every other releaseRuntimeClasspath jar; xmlpull excluded)
    └── …
```

**1. Download** `apktool-android-<version>.aar` from [Releases](https://github.com/anirudhmalik/apktool-android/releases).

**2. Build the runtime bundle** from the **same commit/tag** as that AAR (otherwise versions can drift):

```sh
git checkout v1.0.0   # example: match your AAR release tag
./gradlew :brut.apktool:apktool-android:collectRuntimeJars
# Copy every .jar from:
#   brut.apktool/apktool-android/build/libs-bundle/
# into your app’s app/libs/apktool/runtime/
```

The `collectRuntimeJars` task already **excludes `xmlpull-*.jar`** (duplicate of `android.jar`).

**3. Wire the host module:**

```kotlin
// host-app/build.gradle.kts
android {
    defaultConfig {
        minSdk = 26                  // hard floor: nativeLibraryDir exec contract
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources.excludes += setOf(
            "prebuilt/linux/**",
            "prebuilt/macosx/**",
            "prebuilt/windows/**"
        )
    }

    androidResources {
        noCompress += setOf("apk")
    }
}

dependencies {
    implementation(files("libs/apktool/apktool-android-1.0.0.aar"))
    implementation(fileTree(mapOf("dir" to "libs/apktool/runtime", "include" to listOf("*.jar"))))
}
```

Adjust paths and the AAR file name to match your version.

### Alternative: Maven / internal repository

If you publish **`apktool-android`** with a **Gradle Module Metadata / POM** that exposes `api` dependencies correctly, a **single line** is enough:

```kotlin
dependencies {
    implementation("com.yourcompany:apktool-android:1.0.0")
}
```

This repo’s release job currently attaches **only the AAR**; publishing to Maven or shipping a **zip of `libs-bundle/`** next to the AAR is a separate step if you want copy/paste without running Gradle.

### Local development: composite project

For a full source checkout you can depend on the Android library module directly instead of vendoring jars (no `libs/apktool/runtime/` step):

```kotlin
// settings.gradle.kts of the host — include this repo or use includeBuild(...)
// implementation(project(":brut.apktool:apktool-android"))
```

Exact `include` / `includeBuild` layout depends on your monorepo structure.

## AndroidManifest.xml

For APKs much larger than ~50 MB, raise the heap cap:

```xml
<!--
    ART's default per-app heap is ~192 MB. Decoding a large Play-Store APK
    needs more — Apktool holds the parsed resource table, ResId intern pool,
    and aapt2 staging buffers in heap. largeHeap raises the cap (~512 MB on
    many devices).
-->
<application android:largeHeap="true">
    ...
</application>
```

## All `Config` options

Every setter on
[`brut.androlib.Config`](brut.apktool/apktool-lib/src/main/java/brut/androlib/Config.java)
applies on Android the same way as in a host JVM process. Defaults match Apktool’s usual defaults; only the rows marked **set by `configure()`** are pre-filled for Android.

### Common

| Property | Setter | Default | Purpose |
|----------|--------|---------|---------|
| `version` | constructor arg | `"android"` (or `apktool.properties` if present) | Stamped into `apktool.yml`. Cosmetic. |
| `jobs` | `setJobs(int)` | `min(availableProcessors(), 8)` | Worker pool for parallel decode/build steps. |
| `frameworkDirectory` | `setFrameworkDirectory(String)` | **set by `configure()`** → `filesDir/apktool/framework` | Cached AOSP framework APKs. |
| `frameworkTag` | `setFrameworkTag(String)` | `null` | Tag suffix for cached framework files. |
| `tempDirectory` | `setTempDirectory(File)` | **set by `configure()`** → `cacheDir/apktool` | Scratch dir; must be set on Android. |
| `libraryFiles` | `getLibraryFiles().put(pkg, paths)` | empty | Extra resource libraries. |
| `forced` | `setForced(boolean)` | `false` | Overwrite outputs if they exist. |
| `verbose` | `setVerbose(boolean)` | `false` | Verbose logging via slf4j. |

### Decode (`ApkDecoder`)

| Property | Setter | Default | Purpose |
|----------|--------|---------|---------|
| `decodeSources` | `setDecodeSources(DecodeSources)` | `ONLY_MAIN_CLASSES` | DEX decoding depth. |
| `baksmaliDebugMode` | `setBaksmaliDebugMode(boolean)` | `true` | Smali debug metadata. |
| `decodeResources` | `setDecodeResources(DecodeResources)` | `FULL` | Resource table decoding. |
| `decodeResolve` | `setDecodeResolve(DecodeResolve)` | `DEFAULT` | Missing/duplicate reference handling. |
| `keepBrokenResources` | `setKeepBrokenResources(boolean)` | `false` | Tolerate bad resources. |
| `ignoreRawValues` | `setIgnoreRawValues(boolean)` | `false` | Skip some raw-value records. |
| `analysisMode` | `setAnalysisMode(boolean)` | `false` | Analysis-oriented output. |
| `decodeAssets` | `setDecodeAssets(DecodeAssets)` | `FULL` | Extract assets vs keep raw. |

### Build (`ApkBuilder`)

| Property | Setter | Default | Purpose |
|----------|--------|---------|---------|
| `noApk` | `setNoApk(boolean)` | `false` | Skip final APK; emit `dist/` tree only. |
| `noCrunch` | `setNoCrunch(boolean)` | `false` | Disable PNG crunch. |
| `copyOriginal` | `setCopyOriginal(boolean)` | `false` | Copy `original/` into output. |
| `debuggable` | `setDebuggable(boolean)` | `false` | Force debuggable manifest. |
| `netSecConf` | `setNetSecConf(boolean)` | `false` | Inject permissive network security config. |
| `aaptBinary` | `setAaptBinary(String)` | **set by `configure()`** → `nativeLibraryDir/libaapt2.so` | Path to aapt2 executable. |

## Threading and heap

- Run decode/build **off the main thread** (ANR risk otherwise). `WorkManager`, `Dispatchers.IO`, or a foreground `Service` are typical.
- Use `android:largeHeap="true"` for large APKs; for extreme sizes, consider an `:isolatedProcess` worker.
- **Disk**: decoded trees can be very large; use `cacheDir` if eviction is acceptable, `filesDir` if you need persistence.

## Releases

| Channel | Link |
|---------|------|
| Latest stable | [releases/latest](https://github.com/anirudhmalik/apktool-android/releases/latest) |
| All versions | [releases](https://github.com/anirudhmalik/apktool-android/releases) |
| CI artifact | `apktool-android-aar` from a green [Android workflow](https://github.com/anirudhmalik/apktool-android/actions/workflows/android.yml) run |

Release tags `v*` trigger `.github/workflows/release.yml`, which verifies `jniLibs` binaries, assembles the AAR, and publishes checksums. Tag example:

```sh
git tag -a v1.0 -m "apktool-android v1.0"
git push origin v1.0
```

## Building from source

```sh
# AAR (committed libaapt2.so — no local aapt2 rebuild required)
./gradlew :brut.apktool:apktool-android:assembleRelease

# Runtime JARs for host apps that consume the AAR via files() / fileTree
# (output: brut.apktool/apktool-android/build/libs-bundle/)
./gradlew :brut.apktool:apktool-android:collectRuntimeJars

# JVM tests (apktool-lib)
./gradlew :brut.apktool:apktool-lib:test

# Instrumented tests (emulator)
./gradlew :brut.apktool:apktool-android-test:connectedDebugAndroidTest
```

To refresh `libaapt2.so`, follow [aapt2-build/README.md](aapt2-build/README.md).

## Credits (usage-adjacent)

- **9-patch decoder** in-tree uses [pngj](https://github.com/leonbloy/pngj) (Apache-2.0).

Project-wide acknowledgments: [README.md](README.md#acknowledgments).

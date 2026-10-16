# Apktool Android integration

Material Files compiles these sources directly in `app`, with a minimum runtime
of Android 11 (API 30). This integration supports making a coexistence copy of
one standalone APK. It does not assemble or install split APK sets.

The upstream snapshot is
[`anirudhmalik/apktool-android` v1.0.0](https://github.com/anirudhmalik/apktool-android/tree/f0ed422f89f37276a55e464d208b0eeb91d1ca0b),
commit `f0ed422f89f37276a55e464d208b0eeb91d1ca0b`. `UPSTREAM.json` records the
original SHA-256 of every imported file under its original upstream path.
`MATERIALFILES.patch` records the Java changes against that snapshot.
`SHA256SUMS` records the files actually used by this integration, including the
patch, tests and additional notices. To verify the local snapshot:

```sh
python3 third_party/apktool-android/verify_vendor.py
```

## Build inputs

`app/build.gradle` adds the `src/main/java` trees of `apktool-lib`, the five
`brut.j.*` modules, and the Android wrapper to the app's Java sources. It adds
only `apktool-lib/src/main/resources` as Java resources and the Android
wrapper's `src/main/jniLibs` as native libraries. No prebuilt Apktool runtime
JAR, desktop executable, CLI or upstream Gradle plugin is used. The upstream
version catalog is retained as a record, rather than executed by this build.

The Maven dependencies are pinned in the app build:

| Dependency | Version |
| --- | --- |
| `com.github.iBotPeaches.smali:smali` | `b6365a84f4` |
| `com.github.iBotPeaches.smali:smali-baksmali` | `b6365a84f4` |
| `com.github.iBotPeaches.smali:smali-dexlib2` | `b6365a84f4` |
| `com.github.iBotPeaches.smali:smali-util` | `b6365a84f4` |
| `org.apache.commons:commons-compress` | `1.27.1` |
| `commons-io:commons-io` | `2.21.0` |
| `org.apache.commons:commons-text` | `1.15.0` |
| `ar.com.hjg:pngj` | `2.1.0` |

The smali coordinate resolves to upstream's pinned source commit
[`b6365a84f40c8355af14004dcf7b8324ee050b9f`](https://github.com/iBotPeaches/smali/commit/b6365a84f40c8355af14004dcf7b8324ee050b9f).
Its normal Maven metadata brings in the ANTLR grammar runtime and utility
dependencies. The app supplies its existing `33.7.1-android` Guava and Android
supplies `org.xmlpull.v1`; a second Guava, XMLPull or SLF4J binding is excluded
from the added smali dependency trees. Existing application dependency versions
continue to govern shared transitive libraries.

The Java resource `/prebuilt/android-framework.jar` is a resource table used by
Apktool, not a runtime code dependency. It must remain available through
`Class.getResourceAsStream`. It is included in both debug and release APKs.

## Android native tool

The three original `libaapt2.so` files are copied byte for byte from the pinned
upstream commit. They are static executables built for Android API 30 with NDK
r27b, with 16 KiB ELF load-segment alignment and no `DT_NEEDED` dependencies:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

All three ABI artifacts remain in this directory for source provenance. The
current Material Files app packages only `arm64-v8a` through its ABI filter.
The upstream build recipe and patches are preserved under `aapt2-build/`.
They document how upstream built these binaries; Gradle packages the fixed
arm64 binary and does not run the recipe or download a native tool at runtime.
The byte hashes in `UPSTREAM.json` identify the exact binaries. This project
does not claim that a fresh build of the historical recipe is byte-identical.

`useLegacyPackaging = true` extracts the executable to `nativeLibraryDir`.
`keepDebugSymbols` preserves the already stripped executable. The host uses
`ApktoolAndroid.configure(context)` to locate it and rejects an unavailable
executable before doing work. The current application supports `arm64-v8a` only.

## Local runtime changes

- `ONLY_MANIFEST` loads the original resource table only to resolve manifest
  references, without decoding resource files or DEX. `ApkBuilder` recompiles
  the text manifest even when `resources.arsc` is retained raw. The caller must
  include the original APK through `usesLibrary`/`Config.libraryFiles`, retain
  its resource package name, and save the updated `apktool.yml` before building.
  This lets aapt2 retain the original numeric resource IDs; the resource table,
  DEX, assets, libraries and resource files are copied without content changes.
- `OS.exec` has a ten-minute deadline; `execAndReturn` has a fifteen-second
  deadline. Overloads accept shorter deadlines for tests. Both preserve the
  caller's interrupt status, terminate and reap the child process, close all
  process streams, and join their output collectors before returning. Output
  capture keeps a bounded 16 KiB character tail. Child processes receive the
  job's `TMPDIR`.
- Temporary directory selection uses an inheritable thread binding.
  `Config.setTempDirectory` only stores configuration. `ApkDecoder` and
  `ApkBuilder` bind it for the operation, wait for workers, close their archive
  handles and restore the previous binding. A caller using `OS` directly must
  bind its own temporary directory.
- `BackgroundWorker` cancels futures and joins the actual executor workers;
  a cancelled `Future` alone does not prove a writer has stopped.
- `ResId.clearCache()` lets the host release the resource-ID intern pool in
  its serialized operation's `finally` block, including after failures. Old
  resource-ID objects must not accumulate for the lifetime of the app process.
- `SmaliDecoder` uses smali's public `ClassDefinition` and `BaksmaliWriter`
  APIs, with the original deterministic filename collision handler. It writes
  classes sequentially within each DEX and checks cancellation during writes.
  This avoids the upstream Baksmali thread pool swallowing interruption, and
  turns a failed class decode into a failed operation. The app currently also
  requests one outer Apktool job. Before the filename handler can create any
  class path, the decoder rejects traversal/invalid segments and paths deeper
  than 32 levels, and limits all generated smali files and directory nodes to
  50,000 per APK. Resolved output paths must stay inside the DEX's smali root.
- `SmaliBuilder` checks interruption between classes and parser stages and
  always closes its DEX data store.
- `AaptManager` accepts an already executable native tool without trying to
  change the permissions of the installed, read-only native-library directory.
- `XmlUtils` rejects document types with the platform XMLPull parser before
  parsing with DOM, and rejects external entities. Optional Xerces/JAXP flags
  are used only when the implementation supports them; Android otherwise
  silently skipped several upstream manifest repairs. File validation and DOM
  parsing use the same open file handle.

The caller still owns the complete job directory and final output. It must
remove staging files only after the operation has unwound; interruption does
not justify removing a directory while a writer is using it. Some parsing and
DEX-writing work can take time to unwind after cancellation.

## Tests and scope of evidence

`tests/ApktoolRuntimeLifecycleTest.java` is a framework-free regression harness
for ART or a JVM. It checks success and error output, bounded capture, launch
failure, subprocess timeout, interruption, process reaping, collector shutdown,
worker shutdown, and temporary-directory inheritance. It also assembles a
valid DEX with a 33-level class descriptor and verifies rejection before any
package directories are created. Its sole argument is a scratch directory.
It emits `APKTOOL_RUNTIME_LIFECYCLE passed=N checks=N` on success. The project's
Android coexistence verification script compiles this
harness separately, then loads the production classes from the actual APK.

ART tests that extract aapt2 from the APK and run it under Termux verify that
binary's behavior. They do not substitute for installed-app tests of
`nativeLibraryDir`, UI cancellation, or simultaneous original/clone startup.

## Licenses

The original Apache 2.0 license is preserved in `LICENSE.md`. The original
ReVanced recipe GPL 3.0 license remains in `aapt2-build/recipe/LICENSE`; those
recipe scripts are not packaged into the app. Original source headers remain
intact and modified Java files carry a Material Files modification notice.

Additional dependency and native-component notices are packaged at
`META-INF/materialfiles-apktool/` in the Java resources. `LICENSE-SOURCES.json`
records their source URLs and SHA-256 values. AOSP native dependency notices
are taken from the `platform-tools-35.0.2` tag documented by the upstream build
recipe. These include smali, libpng, expat, protobuf, fmtlib, BoringSSL, PCRE,
jsoncpp, libsepol (LGPL 2.1), libselinux and zlib. The app's licenses screen credits
Apktool, aapt2, smali and the added Java libraries.

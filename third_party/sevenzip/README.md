# Official 7-Zip engine and JBinding

The application builds the unmodified official **7-Zip 26.04** engine from
`7z2604-src.tar.xz`. This is a source archive, not a prebuilt library. Its release
URL, SHA-256, release date and commit are recorded in
[`docs/7zip-jbinding-source.json`](../../docs/7zip-jbinding-source.json).
CMake verifies the archive before extracting it into its build directory, so
clean builds do not need to download the engine.

`jbinding-java/` and `jbinding-cpp/` are copied from the official
[7-Zip-JBinding repository](https://github.com/borisbrodski/sevenzipjbinding/tree/7486f0dcfc602cd65c0ad7e187bbf0cc005bf6af),
commit `7486f0dcfc602cd65c0ad7e187bbf0cc005bf6af` on `7z-26.03`.
The Java bridge still reports its upstream version, **26.03-2.4**; the native
version methods read the actual official engine's `C/7zVersion.h` (**26.04**).
This local Android build is not an upstream Android AAR release.

The only changes to the copied binding sources are:

- `JBindingTools.h`: use Android's `JNIEnv**` thread attachment signature.
- `JavaToCPP/JavaToCPP{SevenZip,InArchiveImpl,OutArchiveImpl}.cpp`: remove generated
  `javah` includes and use explicit C linkage for each JNI export. The existing
  function definitions specify the full JNI signatures. `JavaToCPPSevenZip.cpp`
  also initializes application-class caches on the Java entry thread;
  `JavaToCPPOutArchiveImpl.cpp` stops before `UpdateItems` on callback initialization
  failure.
- `CPPToJava/CPPToJavaArchiveUpdateCallback.{h,cpp}`: keep the output archive and
  cached `IInStream` class as global JNI references for native worker threads,
  resolve the class on the Java entry thread and clean up partial initialization.
- `JavaStatInfos/JavaPackageSevenZip.h`: fix the `reportExtractResult` JNI object
  descriptor.
- `JavaStaticInfo.h`: check class-cache initialization and preserve Java lookup
  exceptions instead of using invalid JNI identifiers.

`sources.cmake` follows upstream's engine/JNI source lists without desktop test
code. The official 26.04 engine itself has no patches. In particular, the build
uses the modern Unix COM ABI without `Z7_USE_VIRTUAL_DESTRUCTOR_IN_IUNKNOWN`.
The application's property adapter includes the same official headers.
Its interface version is **1**, checked at load time to reject the previous
adapter's incompatible JNI signature and COM layout.

`app/build.gradle` adds the Java sources, and `app/CMakeLists.txt` builds
`lib7-Zip-JBinding.so` with static libc++, API 23 compatibility and 16 KiB ELF
alignment. `SevenZipRuntime` explicitly loads and initializes this packaged
library; desktop platform-JAR discovery is not used. The JNI classes and
callbacks remain covered by the application's existing R8 keep rules.

For native-only Termux builds, use:

```sh
python -B tools/build_archive_jbinding.py
```

This builds the engine and property adapter for all four Android ABIs. It uses
the same source list as CMake and puts temporary files and outputs under `~/tmp`.
`--ndk`, `--cxx`, `--abi`, `--jobs`, `--work-dir` and `--output-dir` can override
the local toolchain and output paths. An NDK compiler must match the host CPU.
No APK is built by this helper.

After building, run the standalone engine tests on the matching Android device:

```sh
python -B tools/verify_sevenzip_native.py
```

The verifier requires an installed official `7zz` 26.04 for independent decoding.
Use the same `--ndk` and `--cxx` as the builder; `--build-dir` selects its object
directory. Each run verifies the source and object cache, creates a fresh directory
under `~/tmp/materialfiles-sevenzip-native-test/`, and writes `report.json` and
individual command logs. These tests exercise the engine directly; application
Java/Kotlin and JNI callbacks still require the separate APK integration suite.

Upstream licenses are in `licenses/` and in the original source archive. The
RAR decoder retains the unRAR restriction: it must not be used to develop a
RAR (WinRAR) compatible archiver. The application's offline license includes the
current LGPL, BSD and unRAR notices. See [the format and verification notes](../../docs/7z.md).

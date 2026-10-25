# APKEditor Android integration

Upstream: https://github.com/REAndroid/APKEditor

Pinned version: **1.4.9**, commit `5d94bc08565e30e3e3bb7accbd83138e4d6a8e45`.
The Java sources and all three dependency JARs come from this same commit. The
ARSCLib JAR is the one shipped by APKEditor, not an independently upgraded build.
`UPSTREAM.json` records every vendored file's SHA-256. `UPSTREAM-README.md` and
`UPSTREAM-build.gradle` preserve the upstream API/command and dependency reference.

`app/build.gradle` compiles the source and creates a filtered runtime JAR. Only
Android and XML pull platform stubs, duplicate annotation definitions and JAR
manifest metadata are excluded. The upstream binaries remain intact on disk.
`apkeditor.properties` has the upstream Gradle version placeholder expanded to
1.4.9. Packaged license texts are in `src/main/resources/META-INF/materialfiles-apkeditor`.

The app calls ARSCLib and APKEditor workers directly, never `Main.main` or a shell.
The six tools are decode (XML/Smali), build, merge, refactor, resource protection,
and info. Build, merge, refactor and protection share the existing key selection,
alignment, signing, verification and exclusive publication pipeline.
Protection calls the pinned upstream `Protector.runCommand()` with validated
default `ProtectorOptions`: original dictionaries, Manifest/directory/file/table
protection and the default `font` exception. ZIP structure confusion and DEX
protection remain off, as upstream defaults specify. The upstream protected-input
guard is respected. Signing preserves the protected payloads and exact ZIP entry
names without interpreting those names as filesystem paths; container extraction
continues to validate paths. The bounded ZIP reader avoids Android ZipFile's path
policy while retaining header, overlap, size, decompression and CRC checks.

The coexistence adapter applies the existing semantic rules through a DOM view of
binary attributes. It writes only changed attributes back with ARSCLib and copies
all other ZIP payloads. It never calls `ApkModule.setPackageName` and never rewrites
the original resource table. Split signing/coexistence call the same merge worker.

APKEditor, ARSCLib and JCommand use Apache-2.0. The bundled optional smali engine
and its ANTLR/StringTemplate/JCommander dependencies are covered by the packaged
smali notices. ARSCLib includes Android framework resource data (AOSP Apache-2.0).

Verification: `python3 third_party/apkeditor/verify_vendor.py` checks hashes without
compiling. `tools/verify_apk_signing.py` runs host behavior checks and compiles Java;
run it in GitHub Actions, or locally only when the user explicitly requests builds.

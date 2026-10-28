# BASS Android binaries

Official ARM64 binaries and the unmodified Java JNI binding downloaded from
[Un4seen](https://www.un4seen.com/bass.html) on 2026-10-26. Only the decoders are
used; Media3 owns audio output. All four ELF libraries have 16 KiB PT_LOAD alignment.

| Package | Version | Official archive | Archive SHA-256 |
| --- | --- | --- | --- |
| BASS | 2.4.18.3 | https://www.un4seen.com/files/bass24-android.zip | `236c9017728a910dfe74e79d36d98ad6784f3558c21e8812cfcbafe9bd0fef41` |
| BASSALAC | 2.4.1 | https://www.un4seen.com/files/bassalac24-android.zip | `8ca58d4f3d1cf85557bb7a77e36288b1e45ef152dfed10e58db7cfed62c47a46` |
| BASSFLAC | 2.4.6 | https://www.un4seen.com/files/bassflac24-android.zip | `59c62fd60185450e157e727910be2460a0f66dedd2d6ea1bb3f1c80657dd0da8` |
| BASSWV | 2.4.7.4 | https://www.un4seen.com/files/basswv24-android.zip | `32fe950ece5a6d1ea4f3c52e19f89841e6625331381708a8d7e8558c10a58fba` |

`java/com/un4seen/bass/BASS.java` comes from the BASS archive's identically named
entry. `jniLibs/arm64-v8a/` contains each archive's `libs/arm64-v8a/` library.
Original package notices are in `licenses/`; licence and attribution sections
are also bundled in the app's offline licence viewer.

BASS is proprietary and free for non-commercial use under its own terms;
commercial use requires an Un4seen licence. The add-ons are free to use with
BASS. These components are not GPL-licensed; including them does not grant a
GPL linking exception for Material Files. Redistribution must resolve the
app's GPL obligations and the BASS terms separately.

The vendor download URLs are mutable. Match the recorded SHA-256 before using
an archive to reproduce this version. The app does not download native code at
runtime.

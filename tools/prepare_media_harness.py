#!/usr/bin/env python3
"""Compile the Android media regression harness on CI; never build on a Termux device."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import wave
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    if Path("/system/build.prop").exists() or Path("/system/bin/app_process").exists():
        raise RuntimeError("Prepare this harness on GitHub Actions, not on the Android device")
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path, required=True)
    args = parser.parse_args()
    work = args.output.resolve()
    work.mkdir(parents=True, exist_ok=True)
    classes = work / "classes"
    classes.mkdir()
    media = ROOT / "app/src/main/java/me/zhanghai/android/files/media"
    sources = [ROOT / "third_party/bass/java/com/un4seen/bass/BASS.java",
               media / "BassPcmReader.java", media / "BassWaveHeader.java",
               ROOT / "tests/MediaBassAndroidTest.java"]
    subprocess.run(["javac", "--release", "17", "-cp", str(args.android_jar),
                    "-d", str(classes), *map(str, sources)], check=True)
    java_jar = work / "harness.jar"
    with zipfile.ZipFile(java_jar, "w") as archive:
        for path in classes.rglob("*.class"):
            archive.write(path, path.relative_to(classes).as_posix())
    dex = work / "media-test-dex.jar"
    subprocess.run(["java", "-cp", str(args.d8_jar), "com.android.tools.r8.D8", "--min-api", "30",
                    "--lib", str(args.android_jar), "--output", str(dex), str(java_jar)], check=True)
    fixtures = work / "fixtures"
    fixtures.mkdir()
    samples = [round(11000 * math.sin(2 * math.pi * (317 if channel == 0 else 641) * frame / 48000))
               for frame in range(48000) for channel in range(2)]
    with wave.open(str(fixtures / "source.wav"), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(48000)
        output.writeframes(struct.pack("<" + "h" * len(samples), *samples))
    (fixtures / "expected-f32.raw").write_bytes(struct.pack("<" + "f" * len(samples),
                                                          *(value / 32768 for value in samples)))
    for name, codec in (("alac.m4a", "alac"), ("source.flac", "flac"), ("source.wv", "wavpack")):
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(fixtures / "source.wav"),
                        "-c:a", codec, str(fixtures / name)], check=True)
    manifest = {"source_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "files": {path.relative_to(work).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in [dex, *fixtures.iterdir()]}}
    (work / "harness.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Prepared media ART regression harness:", work)


if __name__ == "__main__":
    main()

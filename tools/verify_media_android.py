#!/usr/bin/env python3
"""Run the precompiled CI harness on Android ART. Does not compile, install or play audible audio."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness-dir", type=Path, required=True)
    args = parser.parse_args()
    work = args.harness_dir.resolve()
    manifest = json.loads((work / "harness.json").read_text())
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if manifest["source_sha"] != sha:
        raise RuntimeError("CI harness must match the checked-out source commit")
    for name, digest in manifest["files"].items():
        if hashlib.sha256((work / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError("CI media fixture checksum mismatch: " + name)
    libraries = ROOT / "third_party/bass/libs/arm64-v8a"
    dex = work / "media-test-dex.jar"
    dex.chmod(0o400)
    env = os.environ.copy()
    env.pop("LD_LIBRARY_PATH", None)
    env.pop("LD_PRELOAD", None)
    env["CLASSPATH"] = str(dex)
    command = ["/system/bin/app_process", "-Xcheck:jni", f"-Djava.library.path={libraries}",
               "/system/bin", "MediaBassAndroidTest", str(work / "fixtures"), str(libraries)]
    run = subprocess.run(command, env=env, capture_output=True, text=True, timeout=120)
    (work / "art-media.log").write_text(run.stdout + run.stderr)
    passed = run.returncode == 0 and "MEDIA_BASS_RESULT passed=8" in run.stdout
    report = {"source_sha": sha, "passed": passed, "checks": 8,
              "scope": "BASS JNI initialization, six plugins, WAV/ALAC/FLAC/WavPack lossless PCM, file and ParcelFileDescriptor reads, small reads and seeks",
              "not_tested": ["audio output", "video decoding", "installed-app UI", "ColorOS Fluid Cloud"],
              "log": str(work / "art-media.log")}
    (work / "art-media.json").write_text(json.dumps(report, indent=2) + "\n")
    print(run.stdout)
    if not passed:
        print(run.stderr)
        raise RuntimeError("Android BASS media regression failed; see " + str(work / "art-media.log"))
    print("Media ART regression passed; report:", work / "art-media.json")


if __name__ == "__main__":
    main()

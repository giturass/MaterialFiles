#!/usr/bin/env python3
"""Run the shipped ARM64 BASS decoders in Termux; no compilation or audio output.

Pass real ALAC/FLAC/WavPack samples as positional arguments. This verifies the
native decoder and byte seeking, not Android MediaSession or UI interaction.
"""
import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("samples", nargs="+", type=Path)
    args = parser.parse_args()
    libraries = Path(__file__).resolve().parents[1] / "third_party/bass/jniLibs/arm64-v8a"
    bass = C.CDLL(str(libraries / "libbass.so"))
    bass.BASS_Init.argtypes = [C.c_int, C.c_uint, C.c_uint, C.c_void_p, C.c_void_p]
    bass.BASS_GetVersion.restype = C.c_uint
    bass.BASS_PluginLoad.argtypes = [C.c_char_p, C.c_uint]
    bass.BASS_StreamCreateFile.argtypes = [C.c_int, C.c_void_p, C.c_uint64, C.c_uint64, C.c_uint]
    bass.BASS_ChannelGetLength.argtypes = [C.c_uint, C.c_uint]
    bass.BASS_ChannelGetLength.restype = C.c_uint64
    bass.BASS_ChannelGetData.argtypes = [C.c_uint, C.c_void_p, C.c_uint]
    bass.BASS_ChannelSetPosition.argtypes = [C.c_uint, C.c_uint64, C.c_uint]
    assert bass.BASS_Init(0, 48000, 0, None, None), "BASS no-sound initialization failed"
    results = []
    try:
        for library in ["bassalac", "bassflac", "basswv"]:
            assert bass.BASS_PluginLoad(str(libraries / f"lib{library}.so").encode(), 0), library
        for sample in args.samples:
            stream = bass.BASS_StreamCreateFile(0, str(sample.resolve()).encode(), 0, 0, 0x200100)
            assert stream, (sample.name, bass.BASS_ErrorGetCode())
            try:
                size = bass.BASS_ChannelGetLength(stream, 0)
                assert 0 < size <= 64 * 1024 * 1024, "Use short, finite test samples"
                pcm = bytearray()
                buffer = C.create_string_buffer(65536)
                while len(pcm) < size:
                    count = bass.BASS_ChannelGetData(stream, buffer, min(65536, size - len(pcm)))
                    assert count > 0, (sample.name, "premature EOF", bass.BASS_ErrorGetCode())
                    pcm.extend(buffer.raw[:count])
                assert any(pcm), "Unexpected all-zero sample"
                positions = [0, 32, size // 2 // 32 * 32, max(0, size - 4096) // 32 * 32]
                for position in positions:
                    assert bass.BASS_ChannelSetPosition(stream, position, 0)
                    count = bass.BASS_ChannelGetData(stream, buffer, min(1024, size - position))
                    assert count > 0 and buffer.raw[:count] == pcm[position:position + count]
                results.append({"file": sample.name, "sha256": hashlib.sha256(sample.read_bytes()).hexdigest(),
                                "decoded_bytes": size, "pcm_sha256": hashlib.sha256(pcm).hexdigest(),
                                "seek_positions_verified": positions})
            finally:
                assert bass.BASS_StreamFree(stream)
        print(json.dumps({"bass_version": hex(bass.BASS_GetVersion()), "samples": results}, indent=2))
    finally:
        bass.BASS_PluginFree(0)
        bass.BASS_Free()


if __name__ == "__main__":
    main()

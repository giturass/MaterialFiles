#!/usr/bin/env python3
"""Run the shipped ARM64 BASS decoders in Termux; no compilation or audio output.

Pass real MP3/ALAC/FLAC/WavPack samples as positional arguments. This verifies the
native decoder's declared length and seeking on fresh streams, not Android
MediaSession or UI interaction. --no-prescan reproduces the old MP3 length bug.
"""
import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path


def repeat_mp3_frames(data, count):
    """Repeat complete MPEG-1 Layer III frames to expose VBR length estimation.

    The Media3 bear-vbr-no-seek-table fixture alone fits BASS's initial scan.
    Strip non-frame bytes rather than repeat its padding or any metadata.
    """
    bitrates = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
    frames = []
    offset = 0
    while offset + 4 <= len(data):
        header = int.from_bytes(data[offset:offset + 4], "big")
        bitrate = header >> 12 & 15
        rate = header >> 10 & 3
        if header & 0xFFFE0000 != 0xFFFA0000 or bitrate not in range(1, 15) or rate == 3:
            offset += 1
            continue
        size = 144000 * bitrates[bitrate] // [44100, 48000, 32000][rate] + (header >> 9 & 1)
        if offset + size > len(data):
            break
        frames.append(data[offset:offset + size])
        offset += size
    assert frames, "The repetition fixture must contain MPEG-1 Layer III frames"
    return b"".join(frames) * count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("samples", nargs="+", type=Path)
    parser.add_argument("--no-prescan", action="store_true")
    parser.add_argument("--repeat-mp3", type=int, choices=range(1, 33), default=1,
                        help="Repeat MPEG-1 Layer III frames in memory (use 24 for the VBR fixture)")
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
    flags = 0x200100 | (0 if args.no_prescan else 0x20000)
    assert bass.BASS_Init(0, 48000, 0, None, None), "BASS no-sound initialization failed"
    results = []
    try:
        for library in ["bassalac", "bassflac", "basswv"]:
            assert bass.BASS_PluginLoad(str(libraries / f"lib{library}.so").encode(), 0), library
        for sample in args.samples:
            filename = str(sample.resolve()).encode()
            data = sample.read_bytes()
            if args.repeat_mp3 > 1:
                data = repeat_mp3_frames(data, args.repeat_mp3)
                source = C.create_string_buffer(data)
                def create_stream():
                    return bass.BASS_StreamCreateFile(1, source, 0, len(data), flags)
            else:
                def create_stream():
                    return bass.BASS_StreamCreateFile(0, filename, 0, 0, flags)
            stream = create_stream()
            assert stream, (sample.name, bass.BASS_ErrorGetCode())
            try:
                size = bass.BASS_ChannelGetLength(stream, 0)
                assert 0 < size <= 64 * 1024 * 1024, "Use short, finite test samples"
                pcm = bytearray()
                buffer = C.create_string_buffer(65536)
                # Decode to the real EOF; stopping at the declared length hides truncation.
                while True:
                    count = bass.BASS_ChannelGetData(stream, buffer, 65536)
                    if count == -1 and bass.BASS_ErrorGetCode() == 45:  # BASS_ERROR_ENDED
                        break
                    assert count > 0, (sample.name, "decode failed", bass.BASS_ErrorGetCode())
                    pcm.extend(buffer.raw[:count])
                    assert len(pcm) <= 64 * 1024 * 1024, "Use short, finite test samples"
                assert len(pcm) == size, (sample.name, "incorrect declared length", size, len(pcm))
                assert any(pcm), "Unexpected all-zero sample"
                positions = [0, 32, size // 2 // 32 * 32, max(0, size - 4096) // 32 * 32]
                for position in positions:
                    # The full decode above builds a seek table even without PRESCAN.
                    # ExoPlayer reopens the data source, so test an unplayed stream each time.
                    assert bass.BASS_StreamFree(stream)
                    stream = create_stream()
                    assert stream, (sample.name, bass.BASS_ErrorGetCode())
                    assert bass.BASS_ChannelSetPosition(stream, position, 0)
                    count = bass.BASS_ChannelGetData(stream, buffer, min(1024, size - position))
                    assert count > 0 and buffer.raw[:count] == pcm[position:position + count]
                results.append({"file": sample.name, "sha256": hashlib.sha256(sample.read_bytes()).hexdigest(),
                                "repeat_mp3": args.repeat_mp3, "input_sha256": hashlib.sha256(data).hexdigest(),
                                "decoded_bytes": size, "pcm_sha256": hashlib.sha256(pcm).hexdigest(),
                                "seek_positions_verified": positions})
            finally:
                if stream:
                    assert bass.BASS_StreamFree(stream)
        print(json.dumps({"bass_version": hex(bass.BASS_GetVersion()),
                          "prescan": not args.no_prescan, "samples": results}, indent=2))
    finally:
        bass.BASS_PluginFree(0)
        bass.BASS_Free()


if __name__ == "__main__":
    main()

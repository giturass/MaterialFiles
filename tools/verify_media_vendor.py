#!/usr/bin/env python3
"""Validate official BASS artifacts, ARM64 ABI, and 16 KB page compatibility without compiling."""
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]


def main():
    vendor = ROOT / "third_party/bass"
    records = json.loads((vendor / "sources.json").read_text())
    libraries = 0
    for record in records:
        assert record["url"].startswith("https://www.un4seen.com/files/")
        for name, expected in record["files"].items():
            data = (vendor / name).read_bytes()
            assert hashlib.sha256(data).hexdigest() == expected, name
            if not name.endswith(".so"):
                continue
            assert data[:6] == b"\x7fELF\x02\x01", name
            assert struct.unpack_from("<H", data, 18)[0] == 183, name
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            for index in range(count):
                values = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
                if values[0] == 1:
                    assert values[7] >= 16384, f"{name}: LOAD segment alignment"
                    assert values[2] % 16384 == values[3] % 16384, name
            libraries += 1
    assert libraries == 7, libraries
    print(f"Verified {libraries} official BASS ARM64 libraries, checksums and 16 KB ELF alignment")


if __name__ == "__main__":
    main()

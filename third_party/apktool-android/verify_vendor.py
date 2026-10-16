#!/usr/bin/env python3
"""Verify the fixed Apktool snapshot without downloads or filesystem writes."""

import hashlib
from pathlib import Path
import sys


def main():
    root = Path(__file__).resolve().parent
    sums = root / "SHA256SUMS"
    expected = {}
    for line in sums.read_text(encoding="utf-8").splitlines():
        digest, relative = line.split("  ", 1)
        path = root / relative
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Missing or unexpected file: {relative}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            raise ValueError(f"SHA-256 mismatch: {relative}")
        expected[relative] = digest
    actual_files = {path.relative_to(root).as_posix() for path in root.rglob("*")
                    if path.is_file() and path != sums and "__pycache__" not in path.parts}
    if actual_files != expected.keys():
        raise ValueError(f"Unrecorded files: {sorted(actual_files - expected.keys())}")
    print(f"Verified {len(expected)} Apktool source, resource, native, notice and test files")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)

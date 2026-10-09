#!/usr/bin/env python3
"""Verify the APK's official C++ 7z writer and reader; see verify_archives.py --help."""

import sys
sys.dont_write_bytecode = True

from verify_archives import main

if __name__ == "__main__":
    main(default_suite="native")

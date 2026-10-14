#!/usr/bin/env python3
"""Verify the APK's JBinding readers against pinned upstream format fixtures."""

import sys
sys.dont_write_bytecode = True

from verify_archives import main

if __name__ == "__main__":
    main(default_suite="reader")

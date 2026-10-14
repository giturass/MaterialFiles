#!/usr/bin/env python3
"""Verify the APK's JBinding writers and official 7zz interoperability."""

import sys
sys.dont_write_bytecode = True

from verify_archives import main

if __name__ == "__main__":
    main(default_suite="formats")

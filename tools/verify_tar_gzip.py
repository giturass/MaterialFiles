#!/usr/bin/env python3
"""Verify TAR and compressed TAR reading through the built APK's fixture suite."""

import sys
sys.dont_write_bytecode = True

from verify_archives import main

if __name__ == "__main__":
    main(default_suite="reader")

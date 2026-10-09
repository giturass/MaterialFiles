#!/usr/bin/env python3
"""Verify the APK's password dialogs, error translation and solid batch extraction on Android."""

import sys
sys.dont_write_bytecode = True

from verify_archives import main

if __name__ == "__main__":
    main(default_suite="password")

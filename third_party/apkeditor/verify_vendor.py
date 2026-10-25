#!/usr/bin/env python3
"""Check the pinned APKEditor source and dependency snapshot without building."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent
manifest = json.loads((root / 'UPSTREAM.json').read_text())
for name, expected in manifest['files'].items():
    path = root / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit(f'APKEditor snapshot mismatch: {name}')
print(f"APKEditor {manifest['version']}: {len(manifest['files'])} pinned files verified")

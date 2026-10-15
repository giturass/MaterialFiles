# ZIP Zstandard reader fixture

`zip-zstandard.zip` contains a Unicode-named file and an empty file, both using
ZIP compression method 93 (Zstandard). It was generated with Python 3.14.6 and
libzstd 1.5.7, independently of the APK's 7-Zip engine. The fixture's SHA-256 is
`605fdf1af683ce52b51d612c1ffb33e4bf1101490589e3876f0398d10720c909`.

7-Zip 26.04 can decode this method but cannot create it. The archive reader suite
first checks the pinned ZIP bytes and compression methods, then decodes both
members with the independent official `7zz` executable. The APK must reproduce
those contents through individual streams and selected batch extraction. A
runtime engine version test separately rejects the old 16.02 library.

Regeneration requires Python 3.14 with Zstandard support; running the tests does
not. From the repository root:

```python
import zipfile

payload = "Independent reader fixture: 中文 🔐\n".encode() + bytes(range(256)) * 32
with zipfile.ZipFile("tests/fixtures/zip-zstandard.zip", "w") as archive:
    for name, content in (("文档/文件 🔐.txt", payload), ("empty", b"")):
        entry = zipfile.ZipInfo(name, (2026, 10, 5, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_ZSTANDARD
        entry.create_system = 3
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, content, compresslevel=3)
```

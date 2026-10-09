#!/usr/bin/env python3
"""Exercise the Android libarchive AAR's actual tar+gzip writer and independent readers.

Run on an Android/bionic host with Python and tar. All temporary files are kept under
~/tmp by default. This calls the AAR's exported C functions, not a system libarchive.
"""

import argparse
import ctypes as C
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import subprocess
import tarfile
import tempfile
import zipfile


FORMAT_TAR = 0x30000
FILTER_GZIP = 1
BLOCK_SIZE = 8192
WRITE_CALLBACK = C.CFUNCTYPE(C.c_ssize_t, C.c_void_p, C.c_void_p, C.c_void_p, C.c_size_t)

DIRECTORIES = {"资料", "空目录"}
FILES = {
    "readme.txt": b"Material Files tar.gz interoperability\n",
    "资料/中文 名称.txt": "中文文件内容\n第二行：压缩与解压。\n".encode(),
    "资料/empty.txt": b"",
    "资料/二进制.bin": b"".join(hashlib.sha256(str(i).encode()).digest() for i in range(2049)),
    "资料/" + "长文件名" * 18 + ".txt": "PAX 长文件名\n".encode(),
}
SYMLINKS = {"资料/链接.txt": "中文 名称.txt"}


class NativeArchive:
    def __init__(self, library):
        self.lib = C.CDLL(str(library))
        signatures = {
            "archive_version_details": (C.c_char_p, []),
            "archive_error_string": (C.c_char_p, [C.c_void_p]),
            "archive_set_charset": (C.c_int, [C.c_void_p, C.c_char_p]),
            "archive_write_new": (C.c_void_p, []),
            "archive_write_set_bytes_per_block": (C.c_int, [C.c_void_p, C.c_int]),
            "archive_write_set_bytes_in_last_block": (C.c_int, [C.c_void_p, C.c_int]),
            "archive_write_set_format": (C.c_int, [C.c_void_p, C.c_int]),
            "archive_write_add_filter": (C.c_int, [C.c_void_p, C.c_int]),
            "archive_write_open": (
                C.c_int, [C.c_void_p, C.c_void_p, C.c_void_p, WRITE_CALLBACK, C.c_void_p]
            ),
            "archive_write_header": (C.c_int, [C.c_void_p, C.c_void_p]),
            "archive_write_data": (C.c_ssize_t, [C.c_void_p, C.c_void_p, C.c_size_t]),
            "archive_write_free": (C.c_int, [C.c_void_p]),
            "archive_entry_new2": (C.c_void_p, [C.c_void_p]),
            "archive_entry_set_pathname": (None, [C.c_void_p, C.c_char_p]),
            "archive_entry_set_filetype": (None, [C.c_void_p, C.c_uint]),
            "archive_entry_set_perm": (None, [C.c_void_p, C.c_int]),
            "archive_entry_set_uid": (None, [C.c_void_p, C.c_int64]),
            "archive_entry_set_gid": (None, [C.c_void_p, C.c_int64]),
            "archive_entry_set_size": (None, [C.c_void_p, C.c_int64]),
            "archive_entry_set_mtime": (None, [C.c_void_p, C.c_long, C.c_long]),
            "archive_entry_set_symlink": (None, [C.c_void_p, C.c_char_p]),
            "archive_entry_free": (None, [C.c_void_p]),
            "archive_read_new": (C.c_void_p, []),
            "archive_read_support_format_by_code": (C.c_int, [C.c_void_p, C.c_int]),
            "archive_read_support_filter_by_code": (C.c_int, [C.c_void_p, C.c_int]),
            "archive_read_open_memory": (C.c_int, [C.c_void_p, C.c_void_p, C.c_size_t]),
            "archive_read_next_header": (C.c_int, [C.c_void_p, C.POINTER(C.c_void_p)]),
            "archive_read_data": (C.c_ssize_t, [C.c_void_p, C.c_void_p, C.c_size_t]),
            "archive_read_free": (C.c_int, [C.c_void_p]),
            "archive_entry_pathname": (C.c_char_p, [C.c_void_p]),
            "archive_entry_symlink": (C.c_char_p, [C.c_void_p]),
        }
        for name, (result, args) in signatures.items():
            function = getattr(self.lib, name)
            function.restype = result
            function.argtypes = args

    def check(self, result, operation, archive=None):
        if result != 0:
            error = self.lib.archive_error_string(archive) if archive else None
            raise OSError(f"{operation}: {result}: {error.decode() if error else 'native error'}")

    def create(self, max_write=None, fail_after=None):
        """Match WriteArchive's format, filter, block settings, and callback-based output."""
        lib = self.lib
        output = bytearray()
        callback_calls = 0

        @WRITE_CALLBACK
        def write_callback(archive, client_data, buffer, length):
            nonlocal callback_calls
            callback_calls += 1
            if fail_after is not None and len(output) >= fail_after:
                return -1
            count = min(length, max_write) if max_write is not None else length
            output.extend(C.string_at(buffer, count))
            return count

        archive = lib.archive_write_new()
        if not archive:
            raise MemoryError("archive_write_new")
        try:
            self.check(lib.archive_write_set_bytes_per_block(archive, BLOCK_SIZE), "block size", archive)
            self.check(lib.archive_write_set_bytes_in_last_block(archive, 1), "last block", archive)
            self.check(lib.archive_write_set_format(archive, FORMAT_TAR), "TAR format", archive)
            self.check(lib.archive_write_add_filter(archive, FILTER_GZIP), "GZIP filter", archive)
            self.check(lib.archive_write_open(archive, None, None, write_callback, None), "open", archive)
            items = [(name, None) for name in sorted(DIRECTORIES)] + list(FILES.items())
            items += [(name, target) for name, target in SYMLINKS.items()]
            for name, data in items:
                entry = lib.archive_entry_new2(archive)
                if not entry:
                    raise MemoryError("archive_entry_new2")
                try:
                    self.check(lib.archive_set_charset(archive, b"UTF-8"), "UTF-8 charset", archive)
                    lib.archive_entry_set_pathname(entry, name.encode())
                    file_type = 0o040000 if data is None else 0o120000 if isinstance(data, str) else 0o100000
                    lib.archive_entry_set_filetype(entry, file_type)
                    lib.archive_entry_set_perm(entry, 0o755 if data is None else 0o644)
                    lib.archive_entry_set_uid(entry, os.getuid())
                    lib.archive_entry_set_gid(entry, os.getgid())
                    lib.archive_entry_set_mtime(entry, 1700000000, 123456789)
                    # Real filesystem directory sizes are nonzero; tar should still store no data.
                    lib.archive_entry_set_size(entry, 4096 if data is None else len(data))
                    if isinstance(data, str):
                        lib.archive_entry_set_symlink(entry, data.encode())
                    self.check(lib.archive_write_header(archive, entry), f"header {name}", archive)
                finally:
                    # The app frees its entry immediately after writeHeader(), before file data.
                    lib.archive_entry_free(entry)
                if isinstance(data, bytes):
                    for offset in range(0, len(data), BLOCK_SIZE):
                        chunk = data[offset:offset + BLOCK_SIZE]
                        buffer = C.create_string_buffer(chunk)
                        written = 0
                        while written < len(chunk):
                            count = lib.archive_write_data(archive, C.byref(buffer, written), len(chunk) - written)
                            if count <= 0:
                                self.check(count if count else -30, f"data {name}", archive)
                            written += count
        except BaseException:
            lib.archive_write_free(archive)
            raise
        else:
            # Like Archive.writeFree(), this also flushes gzip's trailer and the final tar blocks.
            self.check(lib.archive_write_free(archive), "write free / finalization")
        return bytes(output), callback_calls

    def verify_native_reader(self, data):
        lib = self.lib
        archive = lib.archive_read_new()
        if not archive:
            raise MemoryError("archive_read_new")
        data_buffer = C.create_string_buffer(data)
        try:
            self.check(lib.archive_set_charset(archive, b"UTF-8"), "read charset", archive)
            self.check(lib.archive_read_support_format_by_code(archive, FORMAT_TAR), "read TAR", archive)
            self.check(lib.archive_read_support_filter_by_code(archive, FILTER_GZIP), "read GZIP", archive)
            self.check(lib.archive_read_open_memory(archive, data_buffer, len(data)), "read open", archive)
            entry = C.c_void_p()
            found = set()
            while True:
                result = lib.archive_read_next_header(archive, C.byref(entry))
                if result == 1:
                    break
                self.check(result, "read header", archive)
                name = lib.archive_entry_pathname(entry).decode().rstrip("/")
                found.add(name)
                content = bytearray()
                buffer = C.create_string_buffer(BLOCK_SIZE)
                while True:
                    count = lib.archive_read_data(archive, buffer, BLOCK_SIZE)
                    if count == 0:
                        break
                    if count < 0:
                        self.check(count, "read data", archive)
                    content.extend(buffer.raw[:count])
                if name in FILES:
                    assert bytes(content) == FILES[name], f"native data mismatch: {name}"
                elif name in SYMLINKS:
                    assert lib.archive_entry_symlink(entry).decode() == SYMLINKS[name]
                else:
                    assert name in DIRECTORIES and not content, f"unexpected native entry: {name}"
            assert found == set(FILES) | DIRECTORIES | set(SYMLINKS)
        finally:
            self.check(lib.archive_read_free(archive), "read free")


def verify_independent_readers(data, destination, tar_binary):
    assert data.startswith(b"\x1f\x8b\x08"), "not gzip"
    # Python's independent zlib/gzip implementation checks the CRC32 and uncompressed length.
    raw_tar = gzip.decompress(data)
    assert len(raw_tar) % 512 == 0
    with tarfile.open(fileobj=io.BytesIO(raw_tar), mode="r:") as archive:
        members = {member.name.rstrip("/"): member for member in archive.getmembers()}
        assert set(members) == set(FILES) | DIRECTORIES | set(SYMLINKS)
        for name, expected in FILES.items():
            member = members[name]
            assert member.isfile() and member.size == len(expected), name
            assert archive.extractfile(member).read() == expected, f"Python data mismatch: {name}"
        assert all(members[name].isdir() for name in DIRECTORIES)
        assert all(members[name].issym() and members[name].linkname == target
                   for name, target in SYMLINKS.items())
    destination.mkdir()
    archive_file = destination.parent / f"{destination.name}.tar.gz"
    archive_file.write_bytes(data)
    subprocess.run([tar_binary, "-xzf", str(archive_file), "-C", str(destination), "-o"],
                   check=True, capture_output=True, text=True)
    for name, expected in FILES.items():
        assert (destination / name).read_bytes() == expected, f"tar data mismatch: {name}"
    assert all((destination / name).is_dir() for name in DIRECTORIES)
    assert not list((destination / "空目录").iterdir()), "empty directory was not empty"
    assert all((destination / name).is_symlink() and os.readlink(destination / name) == target
               for name, target in SYMLINKS.items())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, required=True, help="project's library-1.1.7.aar")
    parser.add_argument("--tar", default="/system/bin/tar", help="independent tar executable")
    parser.add_argument("--work-dir", type=Path, default=Path.home() / "tmp")
    args = parser.parse_args()
    machine = platform.machine()
    abi = {"aarch64": "arm64-v8a", "arm64": "arm64-v8a", "x86_64": "x86_64",
           "armv7l": "armeabi-v7a", "i686": "x86"}.get(machine)
    if abi is None:
        parser.error(f"unsupported host architecture: {machine}")
    args.work_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="materialfiles-tar-gzip-", dir=args.work_dir) as work_dir:
        work = Path(work_dir)
        with zipfile.ZipFile(args.aar) as aar:
            native_bytes = aar.read(f"jni/{abi}/libarchive-jni.so")
        library = work / "libarchive-jni.so"
        library.write_bytes(native_bytes)
        native = NativeArchive(library)
        results = []
        for name, max_write in (("normal", None), ("short-writes", 137)):
            data, callback_calls = native.create(max_write=max_write)
            native.verify_native_reader(data)
            verify_independent_readers(data, work / name, args.tar)
            results.append({"case": name, "bytes": len(data), "output_callbacks": callback_calls})
        try:
            native.create(fail_after=BLOCK_SIZE)
        except OSError:
            results.append({"case": "output-failure", "error_propagated": True})
        else:
            raise AssertionError("output callback failure was ignored")
        print(json.dumps({
            "aar": str(args.aar.resolve()),
            "native_sha256": hashlib.sha256(native_bytes).hexdigest(),
            "abi": abi,
            "native_version": native.lib.archive_version_details().decode(),
            "independent_tar": args.tar,
            "files": len(FILES), "directories": len(DIRECTORIES), "symlinks": len(SYMLINKS),
            "cases": results,
            "result": "PASS",
        }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

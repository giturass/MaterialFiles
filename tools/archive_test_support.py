"""Shared official fixtures and Android APK harness runner for archive regression tests."""

import hashlib
import bz2
from concurrent.futures import ThreadPoolExecutor
import gzip
import io
import json
import lzma
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import tempfile
import tarfile
import urllib.request
import zipfile

from verify_sora_editor import cached_artifact, run


PASSWORD = "测试-password-🔐"
NAME = "文档/秘密名称.txt"
CONTENT = "加密压缩互操作测试\nhello 7z\n".encode()


def run_7zz(seven_zip, *arguments, cwd=None, successful=True):
    result = subprocess.run([str(seven_zip), *map(str, arguments)], cwd=cwd, capture_output=True,
                            text=True, stdin=subprocess.DEVNULL, timeout=120)
    if successful and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result


def fixtures(seven_zip, directory):
    source = directory / "source"
    source.mkdir()
    (source / "文档").mkdir(mode=0o750)
    (source / NAME).write_bytes(CONTENT)
    (source / NAME).chmod(0o640)
    (source / "empty").touch(mode=0o600)
    (source / "empty-directory").mkdir(mode=0o750)
    (source / "link").symlink_to(NAME)
    (source / "executable.sh").write_bytes(b"#!/bin/sh\nexit 0\n")
    (source / "executable.sh").chmod(0o750)
    (source / "large.bin").write_bytes(bytes((i * 37) & 255 for i in range(8192)) * 128)
    for path in source.rglob("*"):
        os.utime(path, (1700000000, 1700000000), follow_symlinks=False)
    for name, method, headers in [("headers", "LZMA2:d=4m", "on"),
                                  ("contents", "LZMA2:d=4m", "off"),
                                  ("copy", "Copy", "off")]:
        archive = directory / f"official-{name}.7z"
        result = run_7zz(seven_zip, "a", "-t7z", "-m0=" + method, "-ms=on", "-snl",
                        "-mhe=" + headers, "-p" + PASSWORD, archive, ".", cwd=source)
        if name == "headers":
            print(next(line for line in result.stdout.splitlines() if line.startswith("7-Zip")),
                  flush=True)
        run_7zz(seven_zip, "t", "-p" + PASSWORD, archive)
        wrong = run_7zz(seven_zip, "t", "-pwrong-password", archive, successful=False)
        if wrong.returncode == 0:
            raise AssertionError("Official 7zz accepted incorrect password for " + name)
        listing = run_7zz(seven_zip, "l", "-pwrong-password", archive, successful=False)
        if headers == "on":
            if listing.returncode == 0 or NAME in listing.stdout:
                raise AssertionError("Official encrypted header exposed file names")
        elif listing.returncode or NAME not in listing.stdout:
            raise AssertionError("Official content encryption unexpectedly hid file names")


def reference_members(seven_zip, archive, password=None, reference_format=None, sample_members=None):
    key = (["-p" + password] if password else []) + (["-t" + reference_format] if reference_format else [])
    run_7zz(seven_zip, "t", *key, archive)
    listing = run_7zz(seven_zip, "l", "-slt", "-ba", *key, archive).stdout
    items = []
    for block in re.split(r"\n\s*\n", listing.strip()):
        values = dict(line.split(" = ", 1) for line in block.splitlines() if " = " in line)
        name = values.get("Path")
        raw = not name and any(field in values for field in ("Size", "Packed Size", "Method"))
        if raw:
            name = archive.stem or "data"
        if not name or values.get("Folder") == "+" or values.get("Attributes", "").startswith("D") \
                or values.get("Type") == "directory" or values.get("Mode", "").startswith("d"):
            continue
        if sample_members and name not in sample_members:
            continue
        if int(values.get("Size", "0") or "0") > 2 * 1024 * 1024:
            continue
        # NTFS metadata streams are implementation details; select ordinary test files.
        if name.startswith("$"):
            continue
        result = subprocess.run([str(seven_zip), "x", "-so", *key, str(archive), *([] if raw else [name])],
                                capture_output=True, stdin=subprocess.DEVNULL, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace"))
        member = {"name": name, "size": len(result.stdout),
                  "sha256": hashlib.sha256(result.stdout).hexdigest()}
        target = values.get("Symbolic Link")
        if target:
            member["symlink"] = target
        elif re.search(r"(?:^| )l[rwxstST-]{9}$", values.get("Attributes", "")):
            member["symlink"] = result.stdout.decode("utf-8")
        items.append(member)
        if len(items) >= 6:
            break
    if not items:
        raise AssertionError("The reader fixture has no independently readable sample files: " + str(archive))
    if sample_members and {item["name"] for item in items} != set(sample_members):
        raise AssertionError("The reader fixture lost a pinned sample member: " + str(archive))
    return items


def reader_fixtures(seven_zip, directory, downloads, root, official):
    """Pin upstream corpus bytes and compare sample entries against a separate official 7zz."""
    directory.mkdir()
    downloads.mkdir(parents=True, exist_ok=True)
    catalog = json.loads((root / "tests/archive-fixtures.json").read_text())
    corpus = catalog["fixtures"]
    base_url = ("https://raw.githubusercontent.com/omicronapps/7-Zip-JBinding-4Android/"
                + catalog["commit"] + "/" + catalog["base_path"])

    def fetch(item):
        digest = item.get("sha256", item.get("git_blob_sha1"))
        cache = downloads / digest
        if cache.is_file():
            data = cache.read_bytes()
        else:
            request = urllib.request.Request(item.get("url", base_url + item["path"]),
                                             headers={"User-Agent": "MaterialFiles-archive-tests"})
            with urllib.request.urlopen(request, timeout=45) as response:
                data = response.read()
        actual = (hashlib.sha256(data).hexdigest() if "sha256" in item else
                  hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest())
        if actual != digest:
            raise AssertionError("Pinned fixture checksum mismatch: " + item["path"])
        return item, cache, data

    with ThreadPoolExecutor(max_workers=4) as executor:
        fetched = list(executor.map(fetch, corpus))
    archives = []
    for item, cache, data in fetched:
        if not cache.is_file():
            cache.write_bytes(data)
        destination = directory / item["format"].lower()
        destination.mkdir()
        if item.get("unwrap_zip"):
            with zipfile.ZipFile(io.BytesIO(data)) as wrapper:
                files = [entry for entry in wrapper.infolist() if not entry.is_dir()]
                if len(files) != 1:
                    raise AssertionError("Expected one wrapped image in " + item["path"])
                archive = destination / Path(files[0].filename).name
                with wrapper.open(files[0]) as source, archive.open("wb") as output:
                    shutil.copyfileobj(source, output)
        else:
            archive = destination / Path(item["path"]).name
            archive.write_bytes(data)
        archives.append({"archive": archive.relative_to(directory).as_posix(),
                         "format": item["format"],
                         "source_sha256": hashlib.sha256(data).hexdigest(),
                         "members": reference_members(seven_zip, archive,
                                                      reference_format=item.get("reference_format"),
                                                      sample_members=item.get("sample_members"))})

    source = directory / "generated"
    source.mkdir()
    ppmd = catalog["ppmd"]
    ppmd_name = b"fixture.txt"
    ppmd_bytes = (struct.pack("<IIHHI", 0x84ACAF8F, 0, (8 << 12) | (7 << 4) | 5,
                              len(ppmd_name), 0) + ppmd_name + bytes.fromhex(ppmd["encoded_hex"]))
    if hashlib.sha256(ppmd_bytes).hexdigest() != ppmd["archive_sha256"]:
        raise AssertionError("Pinned PPMd fixture checksum mismatch")
    ppmd_archive = source / "fixture.pmd"
    ppmd_archive.write_bytes(ppmd_bytes)
    ppmd_members = reference_members(seven_zip, ppmd_archive)
    if len(ppmd_members) != 1 or ppmd_members[0]["sha256"] != ppmd["content_sha256"]:
        raise AssertionError("The independent PPMd reference does not match its known source")
    archives.append({"archive": ppmd_archive.relative_to(directory).as_posix(), "format": "PPMD",
                     "members": ppmd_members, "source_url": ppmd["source_url"]})
    payload = "Independent reader fixture: 中文 🔐\n".encode() + bytes(range(256)) * 32
    (source / "payload.bin").write_bytes(payload)
    raw_lzma = lzma.compress(payload, format=lzma.FORMAT_ALONE)
    lzma86 = b"\0" + raw_lzma[:5] + len(payload).to_bytes(8, "little") + raw_lzma[13:]
    for name, content, format_name in (
            ("payload.bin.xz", lzma.compress(payload), "XZ"),
            ("payload.bin.lzma", raw_lzma, "LZMA"),
            ("payload.bin.lzma86", lzma86, "LZMA86"),
            ("payload.bin.gz", gzip.compress(payload, mtime=1700000000), "GZIP"),
            ("payload.bin.bz2", bz2.compress(payload), "BZIP2")):
        archive = source / name
        archive.write_bytes(content)
        archives.append({"archive": archive.relative_to(directory).as_posix(), "format": format_name,
                         "members": reference_members(seven_zip, archive)})
    tar = source / "unicode.tar"
    with tarfile.open(tar, "w", format=tarfile.PAX_FORMAT) as output:
        for name, data in (("文档/文件 🔐.txt", payload), ("empty", b"")):
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            entry.mode = 0o640
            output.addfile(entry, io.BytesIO(data))
    tar_members = reference_members(seven_zip, tar)
    archives.append({"archive": tar.relative_to(directory).as_posix(), "format": "TAR", "members": tar_members})
    lookalike = source / "pax-lookalike.tar"
    with tarfile.open(lookalike, "w", format=tarfile.USTAR_FORMAT) as output:
        for name, data in (("././@PaxHeader", b"23 path=not-a-file.txt\n"), ("real-name.txt", payload)):
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            output.addfile(entry, io.BytesIO(data))
    archives.append({"archive": lookalike.relative_to(directory).as_posix(), "format": "TAR_PAX_LOOKALIKE",
                     "members": reference_members(seven_zip, lookalike)})

    def pax_record(values):
        records = []
        for name, value in values.items():
            record = (" " + name + "=" + value + "\n").encode("utf-8")
            length = len(record) + 1
            while len(str(length)) + len(record) != length:
                length = len(str(length)) + len(record)
            records.append(str(length).encode("ascii") + record)
        return b"".join(records)

    def add_tar_data(output, name, data, type_flag=tarfile.REGTYPE):
        entry = tarfile.TarInfo(name)
        entry.type = type_flag
        entry.size = len(data)
        output.addfile(entry, io.BytesIO(data))

    scoped = source / "pax-scopes.tar"
    scoped_name = "文档/PAX 文件.txt"
    with tarfile.open(scoped, "w", format=tarfile.USTAR_FORMAT) as output:
        add_tar_data(output, "arbitrary-global-metadata", pax_record({"mtime": "1700000000"}), tarfile.XGLTYPE)
        add_tar_data(output, "arbitrary-local-metadata",
                     pax_record({"path": scoped_name, "mtime": "1700000123.75"}), tarfile.XHDTYPE)
        add_tar_data(output, "legacy-name.txt", payload)
        add_tar_data(output, "global.txt", b"global PAX defaults survive a local override\n")
        for name, type_flag in (("link", tarfile.SYMTYPE), ("alias.txt", tarfile.LNKTYPE)):
            add_tar_data(output, "link-metadata", pax_record({"linkpath": scoped_name}), tarfile.XHDTYPE)
            entry = tarfile.TarInfo(name)
            entry.type = type_flag
            entry.linkname = "" if type_flag == tarfile.SYMTYPE else "legacy-name.txt"
            output.addfile(entry)
    scoped_members = reference_members(seven_zip, scoped, sample_members=[scoped_name, "global.txt", "link"])
    for member in scoped_members:
        if member["name"] in (scoped_name, "global.txt"):
            member["modified_millis"] = 1700000123750 if member["name"] == scoped_name else 1700000000000
    with tarfile.open(scoped) as reference:
        with reference.extractfile("alias.txt") as alias:
            if alias.read() != payload:
                raise AssertionError("Independent TAR reader did not resolve the PAX hard link")
    scoped_members.append({"name": "alias.txt", "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest()})
    archives.append({"archive": scoped.relative_to(directory).as_posix(), "format": "TAR_PAX_SCOPES",
                     "members": scoped_members, "check_size": True, "alias_only": "alias.txt"})
    with tarfile.open(source / "pax-size-mismatch.tar", "w", format=tarfile.USTAR_FORMAT) as output:
        add_tar_data(output, "size-metadata", pax_record({"size": "99"}), tarfile.XHDTYPE)
        add_tar_data(output, "payload.txt", b"data")

    hardlink_members = []
    hardlinks = source / "hardlinks.tar"
    with tarfile.open(hardlinks, "w", format=tarfile.PAX_FORMAT) as output:
        entry = tarfile.TarInfo("original.txt")
        entry.size = len(payload)
        output.addfile(entry, io.BytesIO(payload))
        for name in ("alias.txt", "folder/alias.txt"):
            entry = tarfile.TarInfo(name)
            entry.type = tarfile.LNKTYPE
            entry.linkname = "original.txt"
            output.addfile(entry)
        for name in ("original.txt", "alias.txt", "folder/alias.txt"):
            hardlink_members.append({"name": name, "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest()})
    archives.append({"archive": hardlinks.relative_to(directory).as_posix(), "format": "TAR_HARDLINKS",
                     "members": hardlink_members, "check_size": True, "alias_only": "folder/alias.txt"})
    for kind, links in (("cycle", (("a", "b"), ("b", "a"))),
                        ("missing", (("alias", "missing"),)),
                        ("unsafe", (("alias", "../../external"),))):
        with tarfile.open(source / ("hardlinks-" + kind + ".tar"), "w") as output:
            for name, target in links:
                entry = tarfile.TarInfo(name)
                entry.type = tarfile.LNKTYPE
                entry.linkname = target
                output.addfile(entry)
    for suffix, compress, format_name in ((".gz", gzip.compress, "TAR_GZIP"),
                                          (".bz2", bz2.compress, "TAR_BZIP2"),
                                          (".xz", lzma.compress, "TAR_XZ")):
        archive = tar.with_name(tar.name + suffix)
        archive.write_bytes(compress(tar.read_bytes()))
        run_7zz(seven_zip, "t", archive)
        archives.append({"archive": archive.relative_to(directory).as_posix(), "format": format_name,
                         "members": tar_members})
    zip_path = source / "unicode.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as output:
        output.writestr("文档/文件 🔐.txt", payload)
        output.writestr("empty", b"")
    archives.append({"archive": zip_path.relative_to(directory).as_posix(), "format": "ZIP",
                     "members": reference_members(seven_zip, zip_path)})
    seven_z = source / "official.7z"
    shutil.copyfile(official / "official-headers.7z", seven_z)
    archives.append({"archive": seven_z.relative_to(directory).as_posix(), "format": "SEVEN_Z",
                     "password": PASSWORD, "members": reference_members(seven_zip, seven_z, PASSWORD)})
    for index, start in enumerate(range(0, len(payload), 503), 1):
        (source / f"raw.bin.{index:03d}").write_bytes(payload[start:start + 503])
    split = source / "raw.bin.001"
    archives.append({"archive": split.relative_to(directory).as_posix(), "format": "SPLIT",
                     "members": reference_members(seven_zip, split)})
    with zipfile.ZipFile(source / "traversal.zip", "w", zipfile.ZIP_STORED) as output:
        for name in ("../escape.txt", "/absolute.txt", "C:\\escape.txt", "safe/../../bad.txt",
                     "safe\\backslash.txt", "inside/../normalized.txt", "ok.txt",
                     "collision", "collision/child.txt"):
            output.writestr(name, b"safe fixture content")
    with zipfile.ZipFile(source / "bad-crc.zip", "w", zipfile.ZIP_STORED) as output:
        output.writestr("payload.bin", payload)
    corrupt = bytearray((source / "bad-crc.zip").read_bytes())
    offset = corrupt.index(payload)
    corrupt[offset + 19] ^= 0x40
    (source / "bad-crc.zip").write_bytes(corrupt)
    (source / "bad-header.7z").write_bytes(seven_z.read_bytes()[:48])
    plain = source / "plain.7z"
    run_7zz(seven_zip, "a", "-t7z", plain, "payload.bin", cwd=source)
    broken = source / "broken-volume"
    broken.mkdir()
    (broken / "archive.7z.001").write_bytes(plain.read_bytes()[:len(plain.read_bytes()) // 2])
    (source / "damaged.7z.001").write_bytes(b"damaged 7z volume, not an ordinary raw split")
    manifest = {"archives": archives, "tested_formats": sorted({item["format"] for item in archives}),
                "upstream_commit": catalog["commit"], "reference_reader": "official 7zz",
                "sampling": "At most six regular or symbolic-link entries per upstream image/archive"}
    target = directory / "reader-manifest.json"
    target.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print("Reader fixture formats:", ", ".join(manifest["tested_formats"]), flush=True)
    return target


def add_apk_arguments(parser, root, work_name):
    parser.add_argument("--apk", type=Path,
                        default=root / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--work-dir", type=Path, default=Path.home() / "tmp" / work_name)
    parser.add_argument("--fixtures", type=Path, help="Reuse official fixtures from a previous run")
    parser.add_argument("--seven-zip", default=shutil.which("7zz"))
    parser.add_argument("--gradle-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path)
    parser.add_argument("--app-process", type=Path, default=Path("/system/bin/app_process"))


class ApkHarness:
    def __init__(self, args, parser, root, need_fixtures=True):
        self.args = args
        self.root = root
        for name in ("apk", "android_jar", "app_process"):
            if not getattr(args, name).is_file():
                parser.error("Missing --" + name.replace("_", "-") + ": " + str(getattr(args, name)))
        if need_fixtures and args.fixtures is None and not args.seven_zip:
            parser.error("Specify --fixtures or install official 7zz (or pass --seven-zip)")
        if args.d8_jar is None:
            build_tools = re.search(r"buildToolsVersion\s*=\s*'([^']+)'",
                                    (root / "app/build.gradle").read_text()).group(1)
            args.d8_jar = args.android_jar.resolve().parents[2] / "build-tools" / build_tools / "lib/d8.jar"
        if not args.d8_jar.is_file():
            parser.error("SDK D8 was not found; specify --d8-jar")
        self.java = args.java_home / "bin/java" if args.java_home else shutil.which("java")
        self.javac = args.java_home / "bin/javac" if args.java_home else shutil.which("javac")
        if not self.java or not self.javac:
            parser.error("Use a JDK 17 or newer, or specify --java-home")
        retrofile = cached_artifact(args.gradle_home, "me.zhanghai.android.retrofile",
                                   "library", "1.2.0", "aar")
        args.work_dir.mkdir(parents=True, exist_ok=True)
        self.work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir)).resolve()
        print("Verification logs:", self.work, flush=True)
        self.fixture_dir = args.fixtures.resolve() if args.fixtures else self.work / "fixtures"
        if need_fixtures:
            if args.fixtures is None:
                self.fixture_dir.mkdir()
                fixtures(args.seven_zip, self.fixture_dir)
            for name in ("official-headers.7z", "official-contents.7z", "official-copy.7z", "source"):
                if not (self.fixture_dir / name).exists():
                    parser.error("Missing fixture: " + str(self.fixture_dir / name))
        self.retrofile_jar = self.work / "retrofile.jar"
        with zipfile.ZipFile(retrofile) as archive:
            self.retrofile_jar.write_bytes(archive.read("classes.jar"))
        self.apk = self.work / "application.apk"
        shutil.copyfile(args.apk, self.apk)
        self.apk.chmod(0o400)
        self.native = self.work / "lib"
        self.native.mkdir()
        abi = {"aarch64": "arm64-v8a", "armv7l": "armeabi-v7a",
               "x86_64": "x86_64", "i686": "x86"}.get(platform.machine())
        if not abi:
            parser.error("Unsupported Android CPU: " + platform.machine())
        with zipfile.ZipFile(self.apk) as archive:
            names = archive.namelist()
            if f"lib/{abi}/lib7-Zip-JBinding.so" not in names:
                raise RuntimeError("The APK is missing the requested 7-Zip-JBinding engine for " + abi)
            if any(Path(name).name in ("libsevenzip.so", "libarchive-jni.so", "libarchive.so") for name in names):
                raise RuntimeError("The APK still packages a replaced archive engine")
            for name in names:
                if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                    data = archive.read(name)
                    if b"archiver/NativeSevenZip" in data or b"me/zhanghai/android/libarchive/" in data:
                        raise RuntimeError("The APK still contains a replaced archive binding")
            for name in archive.namelist():
                if name.startswith(f"lib/{abi}/") and name.endswith(".so"):
                    (self.native / Path(name).name).write_bytes(archive.read(name))
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        print("APK:", args.apk.resolve(), flush=True)
        print("APK SHA-256:", digest, flush=True)
        (self.work / "apk.sha256").write_text(digest + "  " + str(args.apk.resolve()) + "\n")

    def run(self, main_class, arguments, completion, timeout=240,
            extra_sources=()):
        work = self.work / main_class
        work.mkdir()
        classes = work / "classes"
        classes.mkdir()
        sources = [self.root / "tests" / (main_class + ".java")]
        sources += [self.root / "tests" / (name + ".java") for name in extra_sources]
        sources.append(self.root / "tests/ArchiveTestSupport.java")
        compile_env = os.environ.copy()
        compile_env["TMPDIR"] = str(work)
        classpath = os.pathsep.join(map(str, [self.args.android_jar, self.retrofile_jar]))
        run([self.javac, f"-J-Djava.io.tmpdir={work}", "--release", "8", "-encoding", "UTF-8",
             "-cp", classpath, "-d", classes, *sources], work, "javac", env=compile_env)
        harness_jar = work / "test.jar"
        compile_jar = work / "compile-only.jar"
        with zipfile.ZipFile(harness_jar, "w", zipfile.ZIP_DEFLATED) as harness, \
                zipfile.ZipFile(compile_jar, "w", zipfile.ZIP_DEFLATED) as support:
            for path in sorted(classes.rglob("*.class")):
                name = path.relative_to(classes).as_posix()
                destination = support if name.startswith(("me/", "androidx/")) else harness
                destination.write(path, name)
        dex_jar = work / "test-dex.jar"
        run([self.java, f"-Djava.io.tmpdir={work}", "-Xmx512m", "-cp", self.args.d8_jar,
             "com.android.tools.r8.D8", "--min-api", "23", "--lib", self.args.android_jar,
             "--classpath", self.retrofile_jar, "--classpath", compile_jar,
             "--output", dex_jar, harness_jar], work, "d8", env=compile_env)
        dex_jar.chmod(0o400)
        runtime_env = os.environ.copy()
        runtime_env["CLASSPATH"] = os.pathsep.join(map(str, [dex_jar, self.apk]))
        runtime_env["TMPDIR"] = str(work)
        runtime_env.pop("LD_LIBRARY_PATH", None)
        runtime_env.pop("LD_PRELOAD", None)
        output = run([self.args.app_process, "-Xcheck:jni", f"-Djava.library.path={self.native}",
                      "/system/bin", main_class, *arguments], work, "results",
                     timeout=timeout, env=runtime_env)
        print(output, end="", flush=True)
        if not re.search(completion, output):
            raise RuntimeError("Android runtime did not report test completion; see "
                               + str(work / "results.log"))
        return output

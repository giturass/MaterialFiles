"""Locate the production APK verifier in DEX without depending on R8 names.

The instruction and class-data layouts follow the Android DEX format:
https://source.android.com/docs/core/runtime/dex-format
https://source.android.com/docs/core/runtime/dalvik-bytecode
Only the verification entry point and its result constructor are inspected.
"""

import re
import struct
import zipfile


VERIFY_MARKERS = {
    "Invalid minimum SDK",
    "APK signature is missing or invalid",
    "Malformed APK: ",
}
RESULT_ARGUMENTS = ["Z"] + ["Ljava/util/List;"] * 4


class Dex:
    def __init__(self, data):
        self.data = data
        if len(data) < 112 or not data.startswith(b"dex\n"):
            raise RuntimeError("Invalid DEX header")
        self.strings = []
        for offset in self.table(56, 4):
            _, start = self.uleb(self.uint(offset))
            end = data.find(b"\0", start)
            if end < 0:
                raise RuntimeError("Unterminated DEX string")
            self.strings.append(data[start:end].decode("utf-8", errors="replace"))
        self.types = [self.strings[self.uint(offset)] for offset in self.table(64, 4)]
        self.prototypes = []
        for offset in self.table(72, 12):
            _, result, parameters = struct.unpack_from("<III", data, offset)
            arguments = [] if not parameters else [
                self.types[index] for index in struct.unpack_from(
                    "<" + "H" * self.uint(parameters), data, parameters + 4)
            ]
            self.prototypes.append((arguments, self.types[result]))
        self.fields = []
        for offset in self.table(80, 8):
            owner, kind, name = struct.unpack_from("<HHI", data, offset)
            self.fields.append((self.types[owner], self.strings[name], self.types[kind]))
        self.method_ids = []
        for offset in self.table(88, 8):
            owner, prototype, name = struct.unpack_from("<HHI", data, offset)
            arguments, result = self.prototypes[prototype]
            self.method_ids.append({"class": self.types[owner], "method": self.strings[name],
                                    "arguments": arguments, "result": result})

    def uint(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def table(self, header, width):
        count, offset = struct.unpack_from("<II", self.data, header)
        if offset + count * width > len(self.data):
            raise RuntimeError("Truncated DEX table")
        return range(offset, offset + count * width, width)

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            if offset >= len(self.data):
                raise RuntimeError("Truncated DEX class data")
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise RuntimeError("Invalid DEX ULEB128")

    def methods(self):
        for definition in self.table(96, 32):
            offset = self.uint(definition + 24)
            if not offset:
                continue
            counts = []
            for _ in range(4):
                count, offset = self.uleb(offset)
                counts.append(count)
            for _ in range(counts[0] + counts[1]):
                _, offset = self.uleb(offset)
                _, offset = self.uleb(offset)
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    delta, offset = self.uleb(offset)
                    index += delta
                    flags, offset = self.uleb(offset)
                    code, offset = self.uleb(offset)
                    if code:
                        yield {**self.method_ids[index], "flags": flags, "code": code}

    def instructions(self, code):
        count = self.uint(code + 12)
        words = struct.unpack_from("<" + "H" * count, self.data, code + 16)
        position = 0
        while position < count:
            word = words[position]
            opcode = word & 255
            if opcode == 0 and word:
                if word == 0x100:  # packed-switch-payload
                    width = 4 + 2 * words[position + 1]
                elif word == 0x200:  # sparse-switch-payload
                    width = 2 + 4 * words[position + 1]
                elif word == 0x300:  # fill-array-data-payload
                    size = words[position + 2] | words[position + 3] << 16
                    width = 4 + (words[position + 1] * size + 1) // 2
                else:
                    raise RuntimeError("Unknown DEX instruction payload")
            elif opcode in (0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25,
                            0x26, 0x2a, 0x2b, 0x2c, 0xfc, 0xfd) or 0x6e <= opcode <= 0x72 \
                    or 0x74 <= opcode <= 0x78:
                width = 3
            elif opcode == 0x18:
                width = 5
            elif opcode in (0xfa, 0xfb):
                width = 4
            elif opcode in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a,
                            0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29, 0xfe, 0xff) \
                    or 0x2d <= opcode <= 0x3d or 0x44 <= opcode <= 0x6d \
                    or 0x90 <= opcode <= 0xaf or 0xd0 <= opcode <= 0xe2:
                width = 2
            elif opcode <= 0x28 or 0x7b <= opcode <= 0x8f or 0xb0 <= opcode <= 0xcf:
                width = 1
            else:
                raise RuntimeError(f"Unsupported DEX opcode {opcode:#x}")
            if position + width > count:
                raise RuntimeError("Truncated DEX instruction")
            yield opcode, words[position:position + width]
            position += width

    def marker_strings(self, code):
        result = set()
        for opcode, words in self.instructions(code):
            if opcode in (0x1a, 0x1b):
                index = words[1] if opcode == 0x1a else words[1] | words[2] << 16
                value = self.strings[index]
                if value in VERIFY_MARKERS:
                    result.add(value)
        return result


def find_verifier(apk):
    """Return names discovered from the packaged implementation, including merged classes."""
    candidates = []
    result_fields = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:\d+)?\.dex", name):
                continue
            dex = Dex(archive.read(name))
            for method in dex.methods():
                arguments = method["arguments"]
                if len(arguments) == 3 and arguments[:2] == ["Ljava/io/File;", "I"] \
                        and arguments[2].startswith("L") \
                        and dex.marker_strings(method["code"]) == VERIFY_MARKERS:
                    candidates.append({**method, "dex": name})
                if method["method"] == "<init>" and arguments == RESULT_ARGUMENTS:
                    fields = {dex.fields[words[1]] for opcode, words
                              in dex.instructions(method["code"]) if opcode == 0x5c}
                    fields = {field[1] for field in fields
                              if field[0] == method["class"] and field[2] == "Z"}
                    if len(fields) == 1:
                        result_fields[method["class"]] = fields.pop()
    if len(candidates) != 1:
        raise RuntimeError("Expected one production verifier with its signature and three "
                           f"diagnostic strings; found {len(candidates)}")
    method = candidates[0]
    field = result_fields.get(method["result"])
    if field is None:
        raise RuntimeError("Cannot identify the production Result.verified field from its constructor")
    return {"class": method["class"][1:-1].replace("/", "."),
            "method": method["method"],
            "callback_class": method["arguments"][2][1:-1].replace("/", "."),
            "result_class": method["result"][1:-1].replace("/", "."),
            "verified_field": field, "static": bool(method["flags"] & 8),
            "dex": method["dex"], "diagnostic_strings": sorted(VERIFY_MARKERS)}

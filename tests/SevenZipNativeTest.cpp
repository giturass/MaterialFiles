/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

// Compile with the same SEVEN_ZIP_SOURCE_FILES and definitions as the Android library.
// This executable owns the interface GUID definitions normally emitted by the JNI bridge.
#include "Common/MyInitGuid.h"
#include "7zVersion.h"
#include "7zip/Common/StreamObjects.h"
#include "7zip/IPassword.h"
#include "7zip/UI/Common/LoadCodecs.h"
#include "Windows/PropVariant.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using NWindows::NCOM::CPropVariant;
using Bytes = std::vector<Byte>;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void check(HRESULT result, const char* operation) {
    if (result == S_OK) return;
    char message[160];
    std::snprintf(message, sizeof(message), "%s failed: 0x%08x", operation,
                  static_cast<unsigned>(result));
    throw std::runtime_error(message);
}

struct Entry {
    std::wstring path;
    Bytes data;
    bool directory;
};

class MemoryOut final : public IOutStream, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_2(IOutStream, ISequentialOutStream)

    static constexpr size_t kLimit = 64 * 1024 * 1024;
    size_t position_ = 0;

public:
    Bytes data;

    STDMETHOD(Write)(const void* bytes, UInt32 size, UInt32* processed) noexcept override {
        if (processed) *processed = 0;
        if (size > kLimit - position_) return E_OUTOFMEMORY;
        try {
            if (position_ + size > data.size()) data.resize(position_ + size);
            if (size) std::memcpy(data.data() + position_, bytes, size);
            position_ += size;
            if (processed) *processed = size;
            return S_OK;
        } catch (...) {
            return E_OUTOFMEMORY;
        }
    }

    STDMETHOD(Seek)(Int64 offset, UInt32 origin, UInt64* position) noexcept override {
        if (origin > STREAM_SEEK_END) return STG_E_INVALIDFUNCTION;
        Int64 base = origin == STREAM_SEEK_SET ? 0 :
                static_cast<Int64>(origin == STREAM_SEEK_CUR ? position_ : data.size());
        if (offset < -base || offset > static_cast<Int64>(kLimit) - base) {
            return STG_E_INVALIDFUNCTION;
        }
        position_ = static_cast<size_t>(base + offset);
        if (position) *position = position_;
        return S_OK;
    }

    STDMETHOD(SetSize)(UInt64 size) noexcept override {
        if (size > kLimit) return E_OUTOFMEMORY;
        try {
            data.resize(static_cast<size_t>(size));
            return S_OK;
        } catch (...) {
            return E_OUTOFMEMORY;
        }
    }
};

class CreateCallback final : public IArchiveUpdateCallback,
        public ICryptoGetTextPassword2, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_3(IArchiveUpdateCallback, IProgress, ICryptoGetTextPassword2)

    const std::vector<Entry>& entries_;
    const wchar_t* password_;

public:
    bool successful = true;

    CreateCallback(const std::vector<Entry>& entries, const wchar_t* password)
        : entries_(entries), password_(password) {}

    STDMETHOD(SetTotal)(UInt64) noexcept override { return S_OK; }
    STDMETHOD(SetCompleted)(const UInt64*) noexcept override { return S_OK; }

    STDMETHOD(GetUpdateItemInfo)(UInt32 index, Int32* newData, Int32* newProperties,
                                UInt32* previousIndex) noexcept override {
        if (index >= entries_.size()) return E_INVALIDARG;
        *newData = 1;
        *newProperties = 1;
        *previousIndex = static_cast<UInt32>(-1);
        return S_OK;
    }

    STDMETHOD(GetProperty)(UInt32 index, PROPID id, PROPVARIANT* value) noexcept override {
        if (index >= entries_.size()) return E_INVALIDARG;
        try {
            const Entry& entry = entries_[index];
            CPropVariant property;
            switch (id) {
                case kpidPath: property = entry.path.c_str(); break;
                case kpidIsDir: property = entry.directory; break;
                case kpidIsAnti: property = false; break;
                case kpidSize: property = static_cast<UInt64>(entry.data.size()); break;
                default: break;
            }
            return property.Detach(value);
        } catch (...) {
            return E_OUTOFMEMORY;
        }
    }

    STDMETHOD(GetStream)(UInt32 index, ISequentialInStream** stream) noexcept override {
        *stream = nullptr;
        if (index >= entries_.size()) return E_INVALIDARG;
        if (entries_[index].directory) return S_OK;
        try {
            CBufInStream* buffer = new CBufInStream;
            CMyComPtr<ISequentialInStream> reference = buffer;
            const Bytes& data = entries_[index].data;
            buffer->Init(data.data(), data.size());
            *stream = reference.Detach();
            return S_OK;
        } catch (...) {
            return E_OUTOFMEMORY;
        }
    }

    STDMETHOD(SetOperationResult)(Int32 result) noexcept override {
        if (result != NArchive::NExtract::NOperationResult::kOK) successful = false;
        return S_OK;
    }

    STDMETHOD(CryptoGetTextPassword2)(Int32* defined, BSTR* password) noexcept override {
        *defined = password_ != nullptr;
        *password = password_ ? SysAllocString(password_) : nullptr;
        return password_ && !*password ? E_OUTOFMEMORY : S_OK;
    }
};

class OpenCallback final : public IArchiveOpenCallback,
        public ICryptoGetTextPassword, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_2(IArchiveOpenCallback, ICryptoGetTextPassword)

    const wchar_t* password_;

public:
    explicit OpenCallback(const wchar_t* password) : password_(password) {}

    STDMETHOD(SetTotal)(const UInt64*, const UInt64*) noexcept override { return S_OK; }
    STDMETHOD(SetCompleted)(const UInt64*, const UInt64*) noexcept override { return S_OK; }
    STDMETHOD(CryptoGetTextPassword)(BSTR* password) noexcept override {
        *password = password_ ? SysAllocString(password_) : nullptr;
        return !password_ ? E_ABORT : *password ? S_OK : E_OUTOFMEMORY;
    }
};

class ExtractCallback final : public IArchiveExtractCallback,
        public ICryptoGetTextPassword, public IArchiveExtractCallbackMessage2,
        public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_4(IArchiveExtractCallback, IProgress, ICryptoGetTextPassword,
                         IArchiveExtractCallbackMessage2)

    const wchar_t* password_;
    std::vector<CMyComPtr<ISequentialOutStream>> references_;
    UInt32 current_ = 0;

public:
    std::vector<MemoryOut*> streams;
    std::vector<Int32> results;
    bool reportedError = false;

    ExtractCallback(UInt32 count, const wchar_t* password)
        : password_(password), references_(count), streams(count, nullptr), results(count, -1) {}

    STDMETHOD(SetTotal)(UInt64) noexcept override { return S_OK; }
    STDMETHOD(SetCompleted)(const UInt64*) noexcept override { return S_OK; }
    STDMETHOD(PrepareOperation)(Int32) noexcept override { return S_OK; }

    STDMETHOD(GetStream)(UInt32 index, ISequentialOutStream** stream, Int32 mode) noexcept override {
        *stream = nullptr;
        if (index >= streams.size()) return E_INVALIDARG;
        current_ = index;
        if (mode != NArchive::NExtract::NAskMode::kExtract) return S_OK;
        try {
            streams[index] = new MemoryOut;
            references_[index] = streams[index];
            CMyComPtr<ISequentialOutStream> reference = references_[index];
            *stream = reference.Detach();
            return S_OK;
        } catch (...) {
            return E_OUTOFMEMORY;
        }
    }

    STDMETHOD(SetOperationResult)(Int32 result) noexcept override {
        results[current_] = result;
        return S_OK;
    }

    STDMETHOD(ReportExtractResult)(UInt32, UInt32, Int32 result) noexcept override {
        if (result != NArchive::NExtract::NOperationResult::kOK) reportedError = true;
        return S_OK;
    }

    STDMETHOD(CryptoGetTextPassword)(BSTR* password) noexcept override {
        *password = password_ ? SysAllocString(password_) : nullptr;
        return !password_ ? E_ABORT : *password ? S_OK : E_OUTOFMEMORY;
    }
};

int findFormat(const CCodecs& codecs, const wchar_t* name) {
    int index = codecs.FindFormatForArchiveType(UString(name));
    require(index >= 0, "Required archive handler is missing");
    return index;
}

Bytes createArchive(const CCodecs& codecs, const wchar_t* format,
                    const std::vector<Entry>& entries, const wchar_t* password = nullptr,
                    UInt32 dictionary = 0, bool dictionaryLog = false) {
    CMyComPtr<IOutArchive> archive;
    check(codecs.CreateOutArchive(findFormat(codecs, format), archive), "CreateOutArchive");
    require(archive != nullptr, "Archive writer is missing");
    if (std::wcscmp(format, L"tar") != 0) {
        CMyComPtr<ISetProperties> properties;
        check(archive.QueryInterface(IID_ISetProperties, &properties), "QueryInterface(ISetProperties)");
        const wchar_t* names[] = {L"x", L"mt", nullptr, nullptr};
        CPropVariant values[4];
        values[0] = static_cast<UInt32>(5);
        values[1] = static_cast<UInt32>(2);
        UInt32 count = 2;
        if (std::wcscmp(format, L"xz") == 0) {
            // Python and many other XZ writers use CRC64. Exercise that decoder initializer.
            names[count] = L"crc";
            values[count] = static_cast<UInt32>(8);
            ++count;
        } else if (password) {
            bool zip = std::wcscmp(format, L"zip") == 0;
            names[count] = zip ? L"em" : L"he";
            if (zip) values[count] = L"AES256";
            else values[count] = true;
            ++count;
        }
        if (dictionary) {
            names[count] = std::wcscmp(format, L"7z") == 0 ? L"0d" : L"d";
            // Numeric d/0d values mean log2(size); a BSTR with 'b' explicitly means bytes.
            if (dictionaryLog) values[count] = dictionary;
            else values[count] = (std::to_wstring(dictionary) + L"b").c_str();
            ++count;
        }
        check(properties->SetProperties(names, values, count), "SetProperties");
    }
    MemoryOut* output = new MemoryOut;
    CMyComPtr<IOutStream> outputReference = output;
    CreateCallback* callback = new CreateCallback(entries, password);
    CMyComPtr<IArchiveUpdateCallback> callbackReference = callback;
    check(archive->UpdateItems(outputReference, static_cast<UInt32>(entries.size()), callbackReference),
          "UpdateItems");
    require(callback->successful, "Archive creation reported an item error");
    require(!output->data.empty(), "Archive creation returned an empty byte stream");
    return output->data;
}

struct ReadResult {
    bool opened = false;
    bool successful = false;
    std::vector<Entry> entries;
    std::vector<std::wstring> methods;
};

ReadResult readArchive(const CCodecs& codecs, const wchar_t* format, const Bytes& bytes,
                       const wchar_t* password = nullptr) {
    ReadResult result;
    CMyComPtr<IInArchive> archive;
    check(codecs.CreateInArchive(findFormat(codecs, format), archive), "CreateInArchive");
    CBufInStream* buffer = new CBufInStream;
    CMyComPtr<IInStream> stream = buffer;
    buffer->Init(bytes.data(), bytes.size());
    CMyComPtr<IArchiveOpenCallback> opener = new OpenCallback(password);
    if (archive->Open(stream, nullptr, opener) != S_OK) return result;
    result.opened = true;
    UInt32 count;
    check(archive->GetNumberOfItems(&count), "GetNumberOfItems");
    for (UInt32 index = 0; index < count; ++index) {
        CPropVariant path;
        CPropVariant isDirectory;
        CPropVariant method;
        check(archive->GetProperty(index, kpidPath, &path), "GetProperty(Path)");
        check(archive->GetProperty(index, kpidIsDir, &isDirectory), "GetProperty(IsDir)");
        check(archive->GetProperty(index, kpidMethod, &method), "GetProperty(Method)");
        Entry entry{path.vt == VT_BSTR ? path.bstrVal : L"", {},
                    isDirectory.vt == VT_BOOL && isDirectory.boolVal != VARIANT_FALSE};
        if (entry.directory && !entry.path.empty() && entry.path.back() == L'/') entry.path.pop_back();
        result.entries.push_back(entry);
        result.methods.push_back(method.vt == VT_BSTR ? method.bstrVal : L"");
    }
    ExtractCallback* callback = new ExtractCallback(count, password);
    CMyComPtr<IArchiveExtractCallback> callbackReference = callback;
    HRESULT extracted = archive->Extract(nullptr, static_cast<UInt32>(-1), 0, callbackReference);
    result.successful = extracted == S_OK && !callback->reportedError;
    for (UInt32 index = 0; index < count; ++index) {
        if (callback->streams[index]) result.entries[index].data = callback->streams[index]->data;
        if (callback->results[index] != NArchive::NExtract::NOperationResult::kOK) {
            result.successful = false;
        }
    }
    check(archive->Close(), "Close");
    return result;
}

void compareEntries(const ReadResult& actual, const std::vector<Entry>& expected, bool stream) {
    require(actual.successful, "Archive extraction failed");
    require(actual.entries.size() == expected.size(), "Archive entry count changed");
    for (const Entry& entry : expected) {
        auto found = stream ? actual.entries.begin() : std::find_if(
                actual.entries.begin(), actual.entries.end(), [&](const Entry& other) {
                    return other.path == entry.path;
                });
        require(found != actual.entries.end(), "Archive entry name changed");
        require(found->directory == entry.directory, "Archive entry type changed");
        require(found->data == entry.data, "Extracted data differs from the original");
    }
}

void writeArtifact(const char* directory, const char* name, const Bytes& bytes) {
    if (!directory) return;
    std::ofstream output(std::string(directory) + "/" + name, std::ios::binary);
    output.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    require(output.good(), "Could not write the test archive");
}

void verifyZstandardZip(const CCodecs& codecs, const char* fixture) {
    std::ifstream input(fixture, std::ios::binary);
    require(input.good(), "Could not open the independent ZIP Zstandard fixture");
    Bytes bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    const std::string prefix = "Independent reader fixture: 中文 🔐\n";
    Bytes data(prefix.begin(), prefix.end());
    for (unsigned repeat = 0; repeat < 32; ++repeat) {
        for (unsigned value = 0; value < 256; ++value) data.push_back(static_cast<Byte>(value));
    }
    // 7-Zip stores UTF-16 units even when the platform's wchar_t is 32 bits.
    const std::u16string path = u"文档/文件 🔐.txt";
    std::vector<Entry> expected{{std::wstring(path.begin(), path.end()), data, false},
                                {L"empty", {}, false}};
    ReadResult result = readArchive(codecs, L"zip", bytes);
    compareEntries(result, expected, false);
    for (const auto& method : result.methods) {
        require(method == L"zstd", "Independent ZIP fixture does not use Zstandard");
    }
    std::printf("PASS independent ZIP Zstandard method 93 fixture, Unicode name and empty entry\n");
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(std::strcmp(MY_VERSION, "26.04") == 0, "Unexpected 7-Zip engine version");
        CCodecs codecs;
        check(codecs.Load(), "Load codecs");
        const char* directory = argc > 1 ? argv[1] : nullptr;
        Bytes payload(65537);
        for (size_t i = 0; i < payload.size(); ++i) payload[i] = static_cast<Byte>(i * 17 + i / 251);
        std::vector<Entry> files{{L"folder", {}, true}, {L"folder/中文.txt", payload, false},
                                 {L"empty.txt", {}, false}};
        std::vector<Entry> oneFile{{L"payload", payload, false}};
        std::vector<Entry> emptyFile{{L"empty", {}, false}};
        struct Format { const wchar_t* name; const char* extension; bool stream; };
        const Format formats[] = {{L"7z", "7z", false}, {L"zip", "zip", false},
                                  {L"tar", "tar", false}, {L"gzip", "gz", true},
                                  {L"bzip2", "bz2", true}, {L"xz", "xz", true}};
        for (const Format& format : formats) {
            const auto& entries = format.stream ? oneFile : files;
            Bytes bytes = createArchive(codecs, format.name, entries);
            compareEntries(readArchive(codecs, format.name, bytes), entries, format.stream);
            writeArtifact(directory, (std::string("plain.") + format.extension).c_str(), bytes);
            if (std::wcscmp(format.name, L"xz") == 0) {
                require(bytes.size() >= 12 && bytes[7] == 4, "XZ CRC64 property was not applied");
                Bytes corrupt = bytes;
                corrupt[corrupt.size() / 2] ^= 0x80;
                require(!readArchive(codecs, L"xz", corrupt).successful, "Corrupt XZ was accepted");
            }
            if (format.stream) {
                Bytes empty = createArchive(codecs, format.name, emptyFile);
                compareEntries(readArchive(codecs, format.name, empty), emptyFile, true);
                writeArtifact(directory, (std::string("empty.") + format.extension).c_str(), empty);
            }
            std::printf("PASS %s creation, extraction and empty entries\n", format.extension);
        }
        for (const wchar_t* format : {L"7z", L"zip"}) {
            bool sevenZip = std::wcscmp(format, L"7z") == 0;
            // The official ZIP writer rejects non-ASCII passwords in ZipHandlerOut.cpp.
            const wchar_t* password = sevenZip ? L"archive-test-密码" : L"archive-test-password";
            Bytes bytes = createArchive(codecs, format, files, password);
            ReadResult result = readArchive(codecs, format, bytes, password);
            compareEntries(result, files, false);
            if (!sevenZip) {
                for (size_t index = 0; index < result.entries.size(); ++index) {
                    if (!result.entries[index].directory) {
                        require(result.methods[index].find(L"AES-256 ") == 0,
                                "ZIP encryption did not use AES-256");
                    }
                }
            }
            require(!readArchive(codecs, format, bytes, L"wrong password").successful,
                    "Encrypted archive accepted a wrong password");
            require(!readArchive(codecs, format, bytes).successful,
                    "Encrypted archive was extracted without a password");
            if (sevenZip) require(!readArchive(codecs, format, bytes).opened,
                                  "7z header encryption did not hide file names");
            writeArtifact(directory, sevenZip ? "encrypted.7z" : "encrypted.zip", bytes);
            std::printf("PASS %s encryption and password rejection\n", sevenZip ? "7z header" : "ZIP AES256");
        }
        for (const wchar_t* format : {L"7z", L"xz"}) {
            for (UInt32 dictionary : {static_cast<UInt32>(4096), static_cast<UInt32>(65536)}) {
                Bytes bytes = createArchive(codecs, format, oneFile, nullptr, dictionary);
                ReadResult result = readArchive(codecs, format, bytes);
                compareEntries(result, oneFile, true);
                const wchar_t* method = dictionary == 4096 ? L"LZMA2:12" : L"LZMA2:16";
                require(result.methods.front().find(method) != std::wstring::npos,
                        "Explicit LZMA2 dictionary size was not preserved");
                const char* extension = std::wcscmp(format, L"7z") == 0 ? "7z" : "xz";
                std::string name = "dictionary-" + std::to_string(dictionary) + "." + extension;
                writeArtifact(directory, name.c_str(), bytes);
                std::printf("PASS %s explicit %u-byte dictionary\n", extension, dictionary);
            }
            Bytes bytes = createArchive(codecs, format, oneFile, nullptr, 16, true);
            ReadResult result = readArchive(codecs, format, bytes);
            compareEntries(result, oneFile, true);
            require(result.methods.front().find(L"LZMA2:16") != std::wstring::npos,
                    "VT_UI4 dictionary value 16 did not select 65536 bytes");
            const char* extension = std::wcscmp(format, L"7z") == 0 ? "7z" : "xz";
            writeArtifact(directory, (std::string("dictionary-log16.") + extension).c_str(), bytes);
            std::printf("PASS %s numeric dictionary value 16 selects 65536 bytes\n", extension);
        }
        if (argc > 2) verifyZstandardZip(codecs, argv[2]);
        std::printf("PASS official 7-Zip %s (%u registered archive handlers)\n",
                    MY_VERSION, codecs.Formats.Size());
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL %s\n", error.what());
    } catch (...) {
        std::fprintf(stderr, "FAIL unexpected native exception\n");
    }
    return 1;
}

/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.directory;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * Read-only zip-as-directory wrapper, backed by Apache commons-compress
 * rather than {@code java.util.zip}.
 *
 * <p>commons-compress accepts a few APK quirks that the JDK rejects on
 * Android (where {@code jdk.nio.zipfs.allowDotZipEntry} and
 * {@code jdk.util.zip.disableZip64ExtraFieldValidation} are no-ops on
 * ART): {@code .zip} entry names embedded in directories, oversized
 * Zip64 extra fields, and CRC mismatches that older Android apps wrote on
 * purpose. See the original opt-in comment in
 * {@code brut.apktool.Main} for the equivalent JDK toggles on desktop.</p>
 */
public class ZipRODirectory extends Directory {
    private final ZipFile mZipFile;
    private final String mPath;

    public ZipRODirectory(String fileName) throws DirectoryException {
        this(fileName, "");
    }

    public ZipRODirectory(File file) throws DirectoryException {
        this(file, "");
    }

    public ZipRODirectory(String fileName, String path) throws DirectoryException {
        this(new File(fileName), path);
    }

    public ZipRODirectory(File file, String path) throws DirectoryException {
        try {
            // builder is the only constructor entry point that survives the
            // commons-compress 1.27.x API cleanup; falls through to the
            // SeekableByteChannel-backed reader internally.
            mZipFile = ZipFile.builder().setFile(file).get();
        } catch (IOException ex) {
            throw new DirectoryException(ex);
        }
        mPath = path;
    }

    private ZipRODirectory(ZipFile zipFile, String path) {
        mZipFile = zipFile;
        mPath = path;
    }

    @Override
    protected void load() {
        mFiles = new LinkedHashSet<>();
        mDirs = new LinkedHashMap<>();

        int prefixLen = mPath.length();
        Enumeration<ZipArchiveEntry> entries = mZipFile.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            String name = entry.getName();

            if (name.equals(mPath) || !name.startsWith(mPath) || name.contains(".." + separator)) {
                continue;
            }

            String subname = name.substring(prefixLen);

            int pos = subname.indexOf(separatorChar);
            if (pos == -1) {
                if (!entry.isDirectory()) {
                    mFiles.add(subname);
                    continue;
                }
            } else {
                subname = subname.substring(0, pos);
            }

            if (!mDirs.containsKey(subname)) {
                mDirs.put(subname, new ZipRODirectory(mZipFile, mPath + subname + separator));
            }
        }
    }

    /**
     * Look up by absolute (apk-relative) entry name. Mirrors the original
     * {@code java.util.zip.ZipFile#getEntry(String)} behaviour where the
     * caller passes the full path within the archive (no {@code mPath}
     * prefix). Used by {@link #getSize}/{@link #getCompressedSize}/{@link
     * #getCompressionLevel}, which are invoked from {@code ApkDecoder} on
     * the top-level directory with full apk-relative paths.
     */
    private ZipArchiveEntry getZipFileEntry(String name) throws DirectoryException {
        ZipArchiveEntry entry = mZipFile.getEntry(name);
        if (entry == null) {
            throw new PathNotExist("Entry not found: " + name);
        }
        return entry;
    }

    @Override
    protected InputStream getFileInputImpl(String name) throws DirectoryException {
        // getFileInput recurses into sub-directory ZipRODirectory instances
        // and passes the path relative to that view; reattach mPath so we
        // can locate the real entry in the underlying ZipFile.
        ZipArchiveEntry entry = mZipFile.getEntry(mPath + name);
        if (entry == null) {
            throw new PathNotExist(name);
        }
        try {
            return mZipFile.getInputStream(entry);
        } catch (IOException ex) {
            throw new PathNotExist(name, ex);
        }
    }

    @Override
    public long getSize(String name) throws DirectoryException {
        ZipArchiveEntry entry = getZipFileEntry(name);
        return entry.getSize();
    }

    @Override
    public long getCompressedSize(String name) throws DirectoryException {
        ZipArchiveEntry entry = getZipFileEntry(name);
        return entry.getCompressedSize();
    }

    @Override
    public int getCompressionLevel(String name) throws DirectoryException {
        ZipArchiveEntry entry = getZipFileEntry(name);
        return entry.getMethod();
    }

    @Override
    public void close() throws DirectoryException {
        try {
            mZipFile.close();
        } catch (IOException ex) {
            throw new DirectoryException(ex);
        }
    }
}

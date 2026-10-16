/*
 *  Modified for Material Files (2026); see third_party/apktool-android/README.md.
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
package brut.androlib.smali;

import brut.androlib.exceptions.AndrolibException;
import brut.util.OS;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.analysis.InlineMethodResolver;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedOdexFile;
import com.android.tools.smali.dexlib2.dexbacked.ZipDexContainer;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.util.ClassFileNameHandler;

import java.io.File;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SmaliDecoder {
    private static final int MAX_CLASS_PATH_DEPTH = 32;
    private static final int MAX_GENERATED_NODES = 50000;

    private final ZipDexContainer mDexContainer;
    private final boolean mDebugMode;
    private final Set<String> mDexFiles;
    private final AtomicInteger mInferredApiLevel;
    private final Set<String> mGeneratedDirectories = ConcurrentHashMap.newKeySet();
    private final AtomicInteger mGeneratedNodes = new AtomicInteger();

    public SmaliDecoder(File apkFile, boolean debugMode) throws AndrolibException {
        mDexContainer = new ZipDexContainer(apkFile, null);
        // ZipDexContainer is lazily initialized and not thread-safe. Eagerly initialize on the constructing thread.
        try {
            mDexContainer.getEntry("");
        } catch (IOException ex) {
            throw new AndrolibException("Could not open apk file: " + apkFile, ex);
        }
        mDebugMode = debugMode;
        mDexFiles = ConcurrentHashMap.newKeySet();
        mInferredApiLevel = new AtomicInteger();
    }

    public Set<String> getDexFiles() {
        return mDexFiles;
    }

    public int getInferredApiLevel() {
        return mInferredApiLevel.get();
    }

    public void decode(String dexName, File outDir) throws AndrolibException {
        OS.throwIfInterrupted();
        try {
            // Fetch the requested dex file from the dex container.
            ZipDexContainer.DexEntry<DexBackedDexFile> dexEntry = mDexContainer.getEntry(dexName);
            if (dexEntry == null) {
                throw new AndrolibException("Could not find file: " + dexName);
            }

            // Add the requested dex file.
            Map<Integer, DexBackedDexFile> dexFiles = new TreeMap<>();
            dexFiles.put(1, dexEntry.getDexFile());

            // Add additional dex files if it's a multi-dex container.
            for (String dexEntryName : mDexContainer.getDexEntryNames()) {
                if (dexEntryName.equals(dexName)) {
                    continue;
                }

                String prefix = dexName + "/";
                if (!dexEntryName.startsWith(prefix)) {
                    continue;
                }

                int dexNum;
                try {
                    dexNum = Integer.parseInt(dexEntryName.substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (dexNum > 1) {
                    dexFiles.put(dexNum, mDexContainer.getEntry(dexEntryName).getDexFile());
                }
            }

            // Decode the dex files into separate folders.
            for (Map.Entry<Integer, DexBackedDexFile> entry : dexFiles.entrySet()) {
                OS.throwIfInterrupted();
                int dexNum = entry.getKey();
                DexBackedDexFile dexFile = entry.getValue();

                if (dexFile.supportsOptimizedOpcodes()) {
                    throw new AndrolibException("Cannot disassemble an odex file without deodexing it: " + dexName);
                }

                String dirName = "smali";
                if (dexNum > 1 || !dexName.equals("classes.dex")) {
                    dirName += "_" + dexName.substring(0, dexName.lastIndexOf('.')).replace('/', '@');
                    if (dexNum > 1) {
                        dirName += dexNum;
                    }
                }

                decodeFile(dexFile, new File(outDir, dirName));
            }

            mDexFiles.add(dexName);
        } catch (IOException ex) {
            throw new AndrolibException("Could not baksmali file: " + dexName, ex);
        }
    }

    private void decodeFile(DexBackedDexFile dexFile, File smaliDir) throws IOException {
        BaksmaliOptions options = new BaksmaliOptions();
        options.parameterRegisters = true;
        options.localsDirective = true;
        options.sequentialLabels = true;
        options.debugInfo = mDebugMode;
        options.codeOffsets = false;
        options.accessorComments = false;
        options.allowOdex = false;
        options.deodex = false;
        options.implicitReferences = false;
        options.normalizeVirtualMethods = false;
        options.registerInfo = 0;

        if (dexFile instanceof DexBackedOdexFile) {
            options.inlineResolver = InlineMethodResolver.createInlineMethodResolver(
                ((DexBackedOdexFile) dexFile).getOdexVersion());
        }

        File canonicalDirectory = smaliDir.getCanonicalFile();
        if (!canonicalDirectory.equals(smaliDir.getAbsoluteFile())) {
            throw new IOException("Smali output directory contains a symbolic link");
        }
        Path outputRoot = canonicalDirectory.toPath();
        reserveDirectory(outputRoot.toString());
        OS.mkdir(canonicalDirectory);
        // Baksmali.disassembleDexFile swallows interruptions and can report
        // failure only through a boolean that Apktool previously ignored.
        // Write one class at a time with its public writer API. The outer
        // Apktool pool still parallelizes different DEX files.
        List<ClassDef> classes = new ArrayList<>(dexFile.getClasses());
        classes.sort(Comparator.comparing(ClassDef::getType));
        ClassFileNameHandler names = new ClassFileNameHandler(canonicalDirectory, ".smali");
        for (ClassDef classDef : classes) {
            OS.throwIfInterrupted();
            String descriptor = classDef.getType();
            // ClassFileNameHandler creates directories while resolving its
            // collision-safe name. Validate and reserve the complete logical
            // path before invoking it, not merely before opening the writer.
            reserveClassPath(outputRoot, descriptor);
            File smaliFile = names.getUniqueFilenameForClass(descriptor);
            File canonicalFile = smaliFile.getCanonicalFile();
            if (!canonicalFile.equals(smaliFile.getAbsoluteFile())
                    || !canonicalFile.toPath().startsWith(outputRoot)
                    || canonicalFile.toPath().equals(outputRoot)) {
                throw new IOException("Smali output escapes its DEX directory");
            }
            Files.createDirectories(canonicalFile.getParentFile().toPath());
            try (Writer output = new InterruptibleWriter(
                    Files.newBufferedWriter(canonicalFile.toPath(), StandardCharsets.UTF_8));
                 BaksmaliWriter writer = new BaksmaliWriter(output, null)) {
                new ClassDefinition(options, classDef).writeTo(writer);
            }
        }
        OS.throwIfInterrupted();

        int apiLevel = dexFile.getOpcodes().api;
        mInferredApiLevel.updateAndGet(cur -> (cur == 0 || cur > apiLevel) ? apiLevel : cur);
    }

    private void reserveClassPath(Path outputRoot, String descriptor) throws IOException {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IOException("Invalid class descriptor");
        }
        int depth = 1;
        for (int i = 1; i < descriptor.length() - 1; ++i) {
            char value = descriptor.charAt(i);
            if (value == '/' && ++depth > MAX_CLASS_PATH_DEPTH) {
                throw new IOException("Smali class path exceeds 32 levels");
            }
            if (value == '\\' || value <= 0x20 || value == 0x7F || value == ';' || value == '[') {
                throw new IOException("Invalid character in class descriptor");
            }
        }
        String[] segments = descriptor.substring(1, descriptor.length() - 1).split("/", -1);
        Path path = outputRoot;
        for (int i = 0; i < segments.length; ++i) {
            String segment = segments[i];
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Unsafe class descriptor path");
            }
            if (i < segments.length - 1) {
                path = path.resolve(segment);
                reserveDirectory(path.toString());
            }
        }
        reserveNode();
    }

    private void reserveDirectory(String directory) throws IOException {
        if (mGeneratedDirectories.add(directory)) {
            reserveNode();
        }
    }

    private void reserveNode() throws IOException {
        if (mGeneratedNodes.incrementAndGet() > MAX_GENERATED_NODES) {
            throw new IOException("Generated smali files and directories exceed 50000 entries");
        }
    }

    private static final class InterruptibleWriter extends FilterWriter {
        InterruptibleWriter(Writer writer) {
            super(writer);
        }

        private void checkInterrupted() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Smali decode cancelled");
            }
        }

        @Override
        public void write(int value) throws IOException {
            checkInterrupted();
            out.write(value);
        }

        @Override
        public void write(char[] value, int offset, int count) throws IOException {
            checkInterrupted();
            out.write(value, offset, count);
        }

        @Override
        public void write(String value, int offset, int count) throws IOException {
            checkInterrupted();
            out.write(value, offset, count);
        }
    }
}

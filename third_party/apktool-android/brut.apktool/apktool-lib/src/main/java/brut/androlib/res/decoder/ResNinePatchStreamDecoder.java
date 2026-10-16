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
package brut.androlib.res.decoder;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import ar.com.hjg.pngj.PngjException;
import ar.com.hjg.pngj.chunks.PngChunkPLTE;
import ar.com.hjg.pngj.chunks.PngChunkTRNS;
import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.NinePatchNotFoundException;
import brut.androlib.res.data.LayoutBounds;
import brut.androlib.res.data.NinePatchData;
import brut.util.BinaryDataInputStream;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteOrder;

/**
 * Decodes a 9-patch PNG with private {@code npTc}/{@code npLb} chunks back
 * into a borderless PNG with the visual 1-pixel border that the Android
 * resource compiler expects on disk.
 *
 * <p>This implementation uses PNGJ rather than {@code javax.imageio}/AWT so it
 * runs on Android (ART), which has no AWT graphics stack. The pixel buffer is
 * a plain {@code int[h+2][w+2]} ARGB grid — no {@code BufferedImage}, no
 * {@code Bitmap}, no premultiplied-alpha surprises.</p>
 *
 * <p>The chunk discovery still walks the raw byte stream rather than going
 * through PNGJ's chunk map: it's the same handful of lines whether the rest of
 * the file is decoded by PNGJ or AWT, and keeping it byte-driven makes the
 * decoder tolerant of weird vendor chunks ahead of {@code npTc}.</p>
 */
public class ResNinePatchStreamDecoder implements ResStreamDecoder {

    @Override
    public void decode(InputStream in, OutputStream out) throws AndrolibException {
        try {
            byte[] data = IOUtils.toByteArray(in);
            if (data.length == 0) {
                return;
            }

            DecodedImage src = readPng(data);
            int w = src.width;
            int h = src.height;

            // ARGB int buffer with the 1-pixel border needed by aapt2. The
            // border is initialised to 0x00000000 (transparent) which matches
            // BufferedImage.TYPE_INT_ARGB's default.
            int[][] dst = new int[h + 2][w + 2];

            for (int y = 0; y < h; y++) {
                System.arraycopy(src.pixels[y], 0, dst[y + 1], 1, w);
            }

            NinePatchData np = findNinePatchData(data);
            drawHLine(dst, h + 1, np.paddingLeft + 1, w - np.paddingRight);
            drawVLine(dst, w + 1, np.paddingTop + 1, h - np.paddingBottom);

            int[] xDivs = np.xDivs;
            if (xDivs.length == 0) {
                drawHLine(dst, 0, 1, w);
            } else {
                for (int i = 0; i < xDivs.length; i += 2) {
                    drawHLine(dst, 0, xDivs[i] + 1, xDivs[i + 1]);
                }
            }

            int[] yDivs = np.yDivs;
            if (yDivs.length == 0) {
                drawVLine(dst, 0, 1, h);
            } else {
                for (int i = 0; i < yDivs.length; i += 2) {
                    drawVLine(dst, 0, yDivs[i] + 1, yDivs[i + 1]);
                }
            }

            // Optional optical inset / layout bounds chunk.
            // https://developer.android.com/about/versions/android-4.3.html#OpticalBounds
            try {
                LayoutBounds lb = findLayoutBounds(data);
                for (int i = 0; i < lb.left; i++) {
                    int x = 1 + i;
                    dst[h + 1][x] = LayoutBounds.COLOR_TICK;
                }
                for (int i = 0; i < lb.right; i++) {
                    int x = w - i;
                    dst[h + 1][x] = LayoutBounds.COLOR_TICK;
                }
                for (int i = 0; i < lb.top; i++) {
                    int y = 1 + i;
                    dst[y][w + 1] = LayoutBounds.COLOR_TICK;
                }
                for (int i = 0; i < lb.bottom; i++) {
                    int y = h - i;
                    dst[y][w + 1] = LayoutBounds.COLOR_TICK;
                }
            } catch (NinePatchNotFoundException ignored) {
                // This chunk might not exist.
            }

            writePng(dst, w + 2, h + 2, out);
        } catch (IOException | PngjException | NullPointerException ex) {
            // The file is not a valid image.
            throw new AndrolibException(ex);
        }
    }

    /**
     * Reads any PNG variant supported by libpng (8-bit/16-bit, greyscale,
     * RGB, indexed, with or without alpha) into a row-major ARGB int grid
     * with 8 bits per channel.
     */
    private static DecodedImage readPng(byte[] data) {
        PngReader reader = new PngReader(new ByteArrayInputStream(data));
        try {
            // Don't slurp ancillary chunks we don't need (npTc/npLb are scanned
            // off the raw byte array). Crucially we DO need PLTE and tRNS for
            // palette images.
            //
            // PNGJ's PngReader uses ImageLineInt by default; ImageLineInt is
            // already "one sample per int" for any bit depth (samples are at
            // the file's bit depth, not pre-scaled to 8 bit) so we don't need
            // an explicit unpacked-mode flag.
            reader.setShouldCloseStream(true);

            ImageInfo info = reader.imgInfo;
            int w = info.cols;
            int h = info.rows;

            PngChunkPLTE plte = info.indexed ? reader.getMetadata().getPLTE() : null;
            PngChunkTRNS trns = reader.getMetadata().getTRNS();

            // Pre-compute the palette as ARGB ints if the image is indexed.
            int[] paletteArgb = null;
            if (plte != null) {
                int n = plte.getNentries();
                paletteArgb = new int[n];
                int[] alphas = trns != null ? trns.getPalletteAlpha() : null;
                for (int i = 0; i < n; i++) {
                    int rgb = plte.getEntry(i); // 0xRRGGBB
                    int alpha = (alphas != null && i < alphas.length) ? alphas[i] : 0xFF;
                    paletteArgb[i] = (alpha << 24) | (rgb & 0x00FFFFFF);
                }
            }

            int[] greyTrns = null;
            int[] rgbTrns = null;
            if (trns != null && plte == null) {
                if (info.greyscale) {
                    greyTrns = new int[]{trns.getGray()};
                } else {
                    rgbTrns = new int[]{trns.getRGB()[0], trns.getRGB()[1], trns.getRGB()[2]};
                }
            }

            int[][] argb = new int[h][w];
            int bitDepth = info.bitDepth;
            int channels = info.channels;
            boolean alpha = info.alpha;
            boolean greyscale = info.greyscale;
            boolean indexed = info.indexed;

            for (int y = 0; y < h; y++) {
                ImageLineInt line = (ImageLineInt) reader.readRow();
                int[] scan = line.getScanline();
                int[] row = argb[y];

                if (indexed) {
                    for (int x = 0; x < w; x++) {
                        row[x] = paletteArgb[scan[x]];
                    }
                } else if (greyscale) {
                    if (alpha) {
                        for (int x = 0; x < w; x++) {
                            int g = sampleTo8Bit(scan[2 * x], bitDepth);
                            int a = sampleTo8Bit(scan[2 * x + 1], bitDepth);
                            row[x] = (a << 24) | (g << 16) | (g << 8) | g;
                        }
                    } else {
                        int trnsValue = greyTrns != null ? greyTrns[0] : -1;
                        for (int x = 0; x < w; x++) {
                            int s = scan[x];
                            int g = sampleTo8Bit(s, bitDepth);
                            int a = (s == trnsValue) ? 0 : 0xFF;
                            row[x] = (a << 24) | (g << 16) | (g << 8) | g;
                        }
                    }
                } else { // RGB / RGBA
                    if (alpha) {
                        for (int x = 0; x < w; x++) {
                            int r = sampleTo8Bit(scan[channels * x], bitDepth);
                            int g = sampleTo8Bit(scan[channels * x + 1], bitDepth);
                            int b = sampleTo8Bit(scan[channels * x + 2], bitDepth);
                            int a = sampleTo8Bit(scan[channels * x + 3], bitDepth);
                            row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                    } else {
                        for (int x = 0; x < w; x++) {
                            int r = sampleTo8Bit(scan[channels * x], bitDepth);
                            int g = sampleTo8Bit(scan[channels * x + 1], bitDepth);
                            int b = sampleTo8Bit(scan[channels * x + 2], bitDepth);
                            int a = isTransparentRgb(rgbTrns, scan, channels, x, bitDepth) ? 0 : 0xFF;
                            row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                    }
                }
            }
            return new DecodedImage(w, h, argb);
        } finally {
            reader.end();
        }
    }

    /**
     * Linearly scales a PNG sample value (in [0, 2^bitDepth - 1]) to 8 bits.
     * 16-bit samples are reduced via the standard {@code (high << 8) | high}
     * round-trip approximation by simply shifting — sufficient for ticks and
     * roundtrip rebuild.
     */
    private static int sampleTo8Bit(int sample, int bitDepth) {
        switch (bitDepth) {
            case 8:
                return sample & 0xFF;
            case 16:
                return (sample >>> 8) & 0xFF;
            case 1:
                return sample == 0 ? 0 : 0xFF;
            case 2:
                return (sample * 0x55) & 0xFF; // 0->0, 1->85, 2->170, 3->255
            case 4:
                return (sample * 0x11) & 0xFF; // 0->0, ..., 15->255
            default:
                int max = (1 << bitDepth) - 1;
                return (sample * 0xFF + (max >> 1)) / max;
        }
    }

    private static boolean isTransparentRgb(int[] trns, int[] scan, int channels, int x, int bitDepth) {
        if (trns == null) {
            return false;
        }
        // tRNS samples in PNGJ are stored at the file's bit depth.
        int r = scan[channels * x];
        int g = scan[channels * x + 1];
        int b = scan[channels * x + 2];
        if (bitDepth == 16) {
            // tRNS is also 16-bit when bit depth is 16; fall through.
        }
        return r == trns[0] && g == trns[1] && b == trns[2];
    }

    private static void writePng(int[][] argb, int w, int h, OutputStream out) {
        ImageInfo info = new ImageInfo(w, h, 8, true); // 8-bit RGBA
        PngWriter writer = new PngWriter(out, info);
        try {
            writer.setShouldCloseStream(false);
            ImageLineInt line = new ImageLineInt(info);
            int[] scan = line.getScanline();
            for (int y = 0; y < h; y++) {
                int[] row = argb[y];
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    int idx = x * 4;
                    scan[idx] = (p >>> 16) & 0xFF;
                    scan[idx + 1] = (p >>> 8) & 0xFF;
                    scan[idx + 2] = p & 0xFF;
                    scan[idx + 3] = (p >>> 24) & 0xFF;
                }
                writer.writeRow(line, y);
            }
        } finally {
            writer.end();
        }
    }

    private NinePatchData findNinePatchData(byte[] data) throws NinePatchNotFoundException, IOException {
        BinaryDataInputStream in = new BinaryDataInputStream(data, ByteOrder.BIG_ENDIAN);
        findChunk(in, NinePatchData.MAGIC);
        return NinePatchData.read(in);
    }

    private LayoutBounds findLayoutBounds(byte[] data) throws NinePatchNotFoundException, IOException {
        BinaryDataInputStream in = new BinaryDataInputStream(data, ByteOrder.BIG_ENDIAN);
        findChunk(in, LayoutBounds.MAGIC);
        return LayoutBounds.read(in);
    }

    private void findChunk(BinaryDataInputStream in, int magic) throws NinePatchNotFoundException, IOException {
        in.skipBytes(8);
        for (;;) {
            int size;
            try {
                size = in.readInt();
            } catch (EOFException ignored) {
                throw new NinePatchNotFoundException();
            }
            if (in.readInt() == magic) {
                return;
            }
            in.skipBytes(size + 4);
        }
    }

    private void drawHLine(int[][] dst, int y, int x1, int x2) {
        for (int x = x1; x <= x2; x++) {
            dst[y][x] = NinePatchData.COLOR_TICK;
        }
    }

    private void drawVLine(int[][] dst, int x, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            dst[y][x] = NinePatchData.COLOR_TICK;
        }
    }

    private static final class DecodedImage {
        final int width;
        final int height;
        final int[][] pixels;

        DecodedImage(int width, int height, int[][] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }
}

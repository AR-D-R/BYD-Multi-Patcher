package com.bydspotifymanager.app;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Injects the optional Waze startup logger without moving any existing APK entry data. */
final class WazeDebugLoggerInjector {
    private static final String MANIFEST = "AndroidManifest.xml";
    private static final String LOGGER_ASSET = "waze_debug_logger/classes.dex";
    private static final String LOGGER_PROVIDER = "com.waze.byddebug.StartupLogProvider";
    private static final String LOGGER_AUTHORITY = "com.waze.byddebug.startuplog";
    private static final byte[] APK_SIG_MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

    private WazeDebugLoggerInjector() {}

    static void inject(Context context, File inputApk, File outputApk) throws Exception {
        if (context == null) throw new IllegalArgumentException("Context is required for Waze debug logger injection");
        if (inputApk == null || !inputApk.isFile()) throw new IOException("Patched Waze base.apk is missing");

        byte[] manifest;
        byte[] loggerDex;
        String dexName;
        try (ZipFile zip = new ZipFile(inputApk)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null) throw new IOException("Waze base.apk is missing AndroidManifest.xml");
            manifest = IoUtil.readAll(zip.getInputStream(manifestEntry));
            manifest = ManifestElementUtil.addApplicationProvider(
                    manifest, LOGGER_PROVIDER, LOGGER_AUTHORITY, true);
            dexName = nextDexName(zip);
        }
        try (InputStream in = context.getAssets().open(LOGGER_ASSET)) {
            loggerDex = IoUtil.readAll(in);
        }
        if (loggerDex.length < 256 || loggerDex[0] != 'd' || loggerDex[1] != 'e'
                || loggerDex[2] != 'x' || loggerDex[3] != '\n') {
            throw new IOException("Generated Waze debug logger DEX is missing or invalid");
        }

        rebuildZipDirectory(inputApk, outputApk, manifest, dexName, loggerDex);
        verify(outputApk, dexName);
    }

    private static String nextDexName(ZipFile zip) throws IOException {
        int max = 1;
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            String name = entries.nextElement().getName();
            if ("classes.dex".equals(name)) {
                max = Math.max(max, 1);
            } else if (name.startsWith("classes") && name.endsWith(".dex")) {
                String n = name.substring("classes".length(), name.length() - ".dex".length());
                try { max = Math.max(max, Integer.parseInt(n)); } catch (NumberFormatException ignored) {}
            }
        }
        if (max >= 99) throw new IOException("Waze has an unexpected number of DEX files");
        return "classes" + (max + 1) + ".dex";
    }

    /**
     * Keeps every existing local ZIP entry byte-for-byte at its original offset. The old APK
     * signing block and central directory are discarded, then a replacement manifest and the
     * logger DEX are appended. The new central directory points at the replacement manifest,
     * leaving the old manifest local record unreachable. Existing stored-resource alignment is
     * therefore preserved exactly for Waze.
     */
    private static void rebuildZipDirectory(File inputApk, File outputApk, byte[] manifest,
                                            String dexName, byte[] loggerDex) throws Exception {
        List<CentralRecord> centralRecords = new ArrayList<>();
        byte[] zipComment;
        long payloadEnd;

        try (RandomAccessFile raf = new RandomAccessFile(inputApk, "r")) {
            long eocd = findEocd(raf);
            raf.seek(eocd + 10);
            int totalEntries = readU16LE(raf);
            long centralSize = readU32LE(raf);
            long centralOffset = readU32LE(raf);
            int commentLen = readU16LE(raf);
            zipComment = new byte[commentLen];
            raf.readFully(zipComment);
            if (centralOffset + centralSize != eocd) {
                throw new IOException("Unexpected Waze ZIP central-directory layout");
            }

            payloadEnd = findApkSigningBlockStart(raf, centralOffset);
            long pos = centralOffset;
            int parsed = 0;
            while (pos < eocd) {
                raf.seek(pos);
                if (readU32LE(raf) != 0x02014b50L) {
                    throw new IOException("Malformed Waze central directory at 0x" + Long.toHexString(pos));
                }
                raf.seek(pos + 28);
                int nameLen = readU16LE(raf);
                int extraLen = readU16LE(raf);
                int entryCommentLen = readU16LE(raf);
                long recordLen = 46L + nameLen + extraLen + entryCommentLen;
                if (recordLen > Integer.MAX_VALUE || pos + recordLen > eocd) {
                    throw new IOException("Malformed Waze central directory record");
                }
                byte[] raw = new byte[(int) recordLen];
                raf.seek(pos);
                raf.readFully(raw);
                String name = new String(raw, 46, nameLen, StandardCharsets.UTF_8);
                if (!MANIFEST.equals(name) && !dexName.equals(name)) {
                    centralRecords.add(new CentralRecord(name, raw));
                }
                parsed++;
                pos += recordLen;
            }
            if (parsed != totalEntries) {
                throw new IOException("Waze central-directory entry count mismatch");
            }
        }

        if (outputApk.exists() && !outputApk.delete()) {
            throw new IOException("Cannot replace temporary Waze debug APK");
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(inputApk));
             CountingOutputStream out = new CountingOutputStream(
                     new BufferedOutputStream(new FileOutputStream(outputApk)))) {
            copyExactly(in, out, payloadEnd);

            NewEntry manifestEntry = writeEntry(out, MANIFEST, manifest, ZipEntry.DEFLATED, false);
            NewEntry dexEntry = writeEntry(out, dexName, loggerDex, ZipEntry.STORED, true);

            long newCentralOffset = out.count;
            for (CentralRecord record : centralRecords) out.write(record.raw);
            writeCentralRecord(out, manifestEntry);
            writeCentralRecord(out, dexEntry);
            long newCentralSize = out.count - newCentralOffset;
            int newCount = centralRecords.size() + 2;
            if (newCount > 0xffff || newCentralOffset > 0xffffffffL || newCentralSize > 0xffffffffL) {
                throw new IOException("ZIP64 is not supported for Waze debug logger injection");
            }
            writeEocd(out, newCount, newCentralSize, newCentralOffset, zipComment);
        }
    }

    private static NewEntry writeEntry(CountingOutputStream out, String name, byte[] data,
                                       int method, boolean align4) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        byte[] payload = method == ZipEntry.DEFLATED ? deflate(data) : data;
        long localOffset = out.count;

        byte[] extra = new byte[0];
        if (align4) {
            long baseDataOffset = localOffset + 30L + nameBytes.length;
            int padData = (int) ((4 - ((baseDataOffset + 4) & 3)) & 3);
            if ((baseDataOffset & 3) != 0 || padData != 0) {
                extra = new byte[4 + padData];
                put16(extra, 0, 0xD935);
                put16(extra, 2, padData);
            }
        }

        writeU32(out, 0x04034b50L);
        writeU16(out, 20);
        writeU16(out, 0x0800); // UTF-8, known sizes, no data descriptor.
        writeU16(out, method);
        writeU16(out, 0);
        writeU16(out, 0);
        writeU32(out, crc.getValue());
        writeU32(out, payload.length);
        writeU32(out, data.length);
        writeU16(out, nameBytes.length);
        writeU16(out, extra.length);
        out.write(nameBytes);
        out.write(extra);
        out.write(payload);

        return new NewEntry(nameBytes, method, crc.getValue(), payload.length, data.length, localOffset);
    }

    private static void writeCentralRecord(CountingOutputStream out, NewEntry e) throws IOException {
        writeU32(out, 0x02014b50L);
        writeU16(out, 20);
        writeU16(out, 20);
        writeU16(out, 0x0800);
        writeU16(out, e.method);
        writeU16(out, 0);
        writeU16(out, 0);
        writeU32(out, e.crc);
        writeU32(out, e.compressedSize);
        writeU32(out, e.uncompressedSize);
        writeU16(out, e.name.length);
        writeU16(out, 0);
        writeU16(out, 0);
        writeU16(out, 0);
        writeU16(out, 0);
        writeU32(out, 0);
        writeU32(out, e.localOffset);
        out.write(e.name);
    }

    private static void writeEocd(CountingOutputStream out, int count, long centralSize,
                                  long centralOffset, byte[] comment) throws IOException {
        writeU32(out, 0x06054b50L);
        writeU16(out, 0);
        writeU16(out, 0);
        writeU16(out, count);
        writeU16(out, count);
        writeU32(out, centralSize);
        writeU32(out, centralOffset);
        writeU16(out, comment.length);
        out.write(comment);
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(1024, data.length / 2));
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater, 16 * 1024)) {
            out.write(data);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static void verify(File apk, String dexName) throws Exception {
        if (!apk.isFile() || apk.length() < 1024) throw new IOException("Debug-injected Waze base.apk was not created");
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry manifest = zip.getEntry(MANIFEST);
            ZipEntry dex = zip.getEntry(dexName);
            if (manifest == null || dex == null) throw new IOException("Waze debug APK is missing injected entries");
            byte[] manifestBytes = IoUtil.readAll(zip.getInputStream(manifest));
            int providers = ManifestElementUtil.countApplicationProvider(manifestBytes, LOGGER_PROVIDER);
            if (providers != 1) throw new IOException("Waze debug provider verification failed: " + providers);
            byte[] dexBytes = IoUtil.readAll(zip.getInputStream(dex));
            if (dexBytes.length < 256 || dexBytes[0] != 'd' || dexBytes[1] != 'e' || dexBytes[2] != 'x') {
                throw new IOException("Waze debug DEX verification failed");
            }
        }
    }

    private static long findApkSigningBlockStart(RandomAccessFile raf, long centralOffset) throws IOException {
        if (centralOffset < 24) return centralOffset;
        raf.seek(centralOffset - 16);
        byte[] magic = new byte[16];
        raf.readFully(magic);
        for (int i = 0; i < magic.length; i++) {
            if (magic[i] != APK_SIG_MAGIC[i]) return centralOffset;
        }
        raf.seek(centralOffset - 24);
        long sizeInFooter = readU64LE(raf);
        long blockSize = sizeInFooter + 8L;
        long blockStart = centralOffset - blockSize;
        if (sizeInFooter < 24 || blockStart < 0) throw new IOException("Malformed APK Signing Block");
        raf.seek(blockStart);
        long sizeInHeader = readU64LE(raf);
        if (sizeInHeader != sizeInFooter) throw new IOException("APK Signing Block size mismatch");
        return blockStart;
    }

    private static long findEocd(RandomAccessFile raf) throws IOException {
        long length = raf.length();
        long min = Math.max(0, length - 22 - 0xffffL);
        for (long pos = length - 22; pos >= min; pos--) {
            raf.seek(pos);
            if (readU32LE(raf) == 0x06054b50L) {
                raf.seek(pos + 20);
                int commentLen = readU16LE(raf);
                if (pos + 22L + commentLen == length) return pos;
            }
        }
        throw new IOException("APK end-of-central-directory record not found");
    }

    private static void copyExactly(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) throw new IOException("Unexpected EOF while copying Waze APK payload");
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static int readU16LE(RandomAccessFile raf) throws IOException {
        int a = raf.read();
        int b = raf.read();
        if ((a | b) < 0) throw new IOException("Unexpected EOF");
        return a | (b << 8);
    }

    private static long readU32LE(RandomAccessFile raf) throws IOException {
        long a = readU16LE(raf);
        long b = readU16LE(raf);
        return a | (b << 16);
    }

    private static long readU64LE(RandomAccessFile raf) throws IOException {
        long lo = readU32LE(raf);
        long hi = readU32LE(raf);
        return lo | (hi << 32);
    }

    private static void writeU16(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeU32(OutputStream out, long value) throws IOException {
        out.write((int) value & 0xff);
        out.write((int) (value >>> 8) & 0xff);
        out.write((int) (value >>> 16) & 0xff);
        out.write((int) (value >>> 24) & 0xff);
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static final class CentralRecord {
        final String name;
        final byte[] raw;
        CentralRecord(String name, byte[] raw) { this.name = name; this.raw = raw; }
    }

    private static final class NewEntry {
        final byte[] name;
        final int method;
        final long crc;
        final long compressedSize;
        final long uncompressedSize;
        final long localOffset;
        NewEntry(byte[] name, int method, long crc, long compressedSize,
                 long uncompressedSize, long localOffset) {
            this.name = name;
            this.method = method;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localOffset = localOffset;
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        long count;
        CountingOutputStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int b) throws IOException { delegate.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len); count += len;
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}

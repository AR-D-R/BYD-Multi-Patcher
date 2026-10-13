package com.bydspotifymanager.app;

import android.content.Context;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * Keeps the trained ART V010 profile valid after a clone package rewrite changes DEX CRC32s.
 * The hot method/class payload is preserved; only each DEX checksum in the V010 header table changes.
 */
final class ProfileMetadataUtil {
    private static final String EMBEDDED_PROFILE = "assets/dexopt/baseline.prof";
    private static final String DM_ASSET = "profiles/v16_primary.dm";

    private ProfileMetadataUtil() {}

    static void updateEmbeddedBaselineProfile(File apk) throws Exception {
        Map<String, Long> dexCrcs = readDexCrcs(apk);
        File tmp = new File(apk.getParentFile(), apk.getName() + ".profiletmp");
        boolean updated = false;

        try (ZipFile zin = new ZipFile(apk);
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry src = en.nextElement();
                byte[] data = IoUtil.readAll(zin.getInputStream(src));
                if (EMBEDDED_PROFILE.equals(src.getName())) {
                    data = rewriteV010Checksums(data, dexCrcs);
                    updated = true;
                }
                writeEntry(zout, src, data);
            }
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }

        if (!updated) {
            tmp.delete();
            throw new IOException("Embedded baseline.prof was not found");
        }
        if (apk.exists() && !apk.delete()) {
            tmp.delete();
            throw new IOException("Cannot replace APK after profile update");
        }
        if (!tmp.renameTo(apk)) {
            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(apk)) {
                IoUtil.copy(in, out);
            }
            tmp.delete();
        }
    }

    /**
     * Builds DexMetadata from the patched APK's own embedded profile. This keeps the profile
     * version-specific when Spotify itself changes DEX layout between releases.
     */
    static void createMatchingDmFromEmbeddedProfile(File apk, File outDm) throws Exception {
        byte[] prof;
        byte[] profm = null;
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry p = z.getEntry("assets/dexopt/baseline.prof");
            if (p == null) throw new IOException("Patched APK has no embedded baseline.prof");
            prof = IoUtil.readAll(z.getInputStream(p));
            ZipEntry pm = z.getEntry("assets/dexopt/baseline.profm");
            if (pm != null) profm = IoUtil.readAll(z.getInputStream(pm));
        }
        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outDm)))) {
            ZipEntry p = new ZipEntry("primary.prof");
            p.setMethod(ZipEntry.DEFLATED);
            zout.putNextEntry(p); zout.write(prof); zout.closeEntry();
            if (profm != null) {
                ZipEntry pm = new ZipEntry("primary.profm");
                pm.setMethod(ZipEntry.DEFLATED);
                zout.putNextEntry(pm); zout.write(profm); zout.closeEntry();
            }
        }
    }

    /** Build a DexMetadata .dm whose primary.prof matches the final APK's DEX CRC32 values. */
    static void createMatchingDm(Context context, File apk, File outDm) throws Exception {
        Map<String, Long> dexCrcs = readDexCrcs(apk);
        boolean profileWritten = false;

        try (InputStream raw = context.getAssets().open(DM_ASSET);
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw));
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outDm)))) {
            ZipEntry src;
            while ((src = zin.getNextEntry()) != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                IoUtil.copy(zin, buf);
                byte[] data = buf.toByteArray();
                if ("primary.prof".equals(src.getName())) {
                    data = rewriteV010Checksums(data, dexCrcs);
                    profileWritten = true;
                }
                ZipEntry dst = new ZipEntry(src.getName());
                dst.setMethod(ZipEntry.DEFLATED);
                zout.putNextEntry(dst);
                zout.write(data);
                zout.closeEntry();
                zin.closeEntry();
            }
        }

        if (!profileWritten) {
            outDm.delete();
            throw new IOException("Template DM does not contain primary.prof");
        }
    }

    static Map<String, Long> readDexCrcs(File apk) throws IOException {
        Map<String, Long> result = new HashMap<>();
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.matches("classes(\\d+)?\\.dex")) result.put(n, e.getCrc());
            }
        }
        if (!result.containsKey("classes.dex")) throw new IOException("APK has no classes.dex");
        return result;
    }

    static byte[] rewriteV010Checksums(byte[] profile, Map<String, Long> dexCrcs) throws Exception {
        if (profile.length < 17 || profile[0] != 'p' || profile[1] != 'r' || profile[2] != 'o' || profile[3] != 0) {
            throw new IOException("Unsupported ART profile magic");
        }
        if (profile[4] != '0' || profile[5] != '1' || profile[6] != '0' || profile[7] != 0) {
            throw new IOException("Expected ART profile version 010");
        }

        int dexCount = profile[8] & 0xff;
        long expectedUncompressed = u32(profile, 9);
        long compressedSize = u32(profile, 13);
        if (compressedSize != profile.length - 17L) throw new IOException("ART profile compressed-size mismatch");

        byte[] body;
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(profile, 17, profile.length - 17));
             ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(expectedUncompressed, Integer.MAX_VALUE))) {
            IoUtil.copy(in, out);
            body = out.toByteArray();
        }
        if (body.length != expectedUncompressed) throw new IOException("ART profile uncompressed-size mismatch");

        int off = 0;
        for (int i = 0; i < dexCount; i++) {
            if (off + 16 > body.length) throw new IOException("Truncated ART DEX header table");
            int keyLen = u16(body, off);
            int keyStart = off + 16;
            int keyEnd = keyStart + keyLen;
            if (keyEnd > body.length) throw new IOException("Truncated ART DEX key");
            String key = new String(body, keyStart, keyLen, StandardCharsets.UTF_8);
            String dexName = keyToDexName(key);
            Long crc = dexCrcs.get(dexName);
            if (crc == null) throw new IOException("Profile references missing DEX: " + dexName);
            putU32(body, off + 8, crc);
            off = keyEnd;
        }

        byte[] compressed;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DeflaterOutputStream def = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION))) {
            def.write(body);
            def.finish();
            compressed = out.toByteArray();
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream(17 + compressed.length);
        result.write(profile, 0, 9); // magic + version + dex count
        writeU32(result, body.length);
        writeU32(result, compressed.length);
        result.write(compressed);
        return result.toByteArray();
    }

    private static String keyToDexName(String key) throws IOException {
        if ("base.apk".equals(key) || "classes.dex".equals(key)) return "classes.dex";
        int bang = key.lastIndexOf('!');
        if (bang >= 0 && bang + 1 < key.length()) return key.substring(bang + 1);
        if (key.matches("classes(\\d+)?\\.dex")) return key;
        throw new IOException("Unsupported ART profile DEX key: " + key);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return ((long)b[o] & 0xffL)
                | (((long)b[o + 1] & 0xffL) << 8)
                | (((long)b[o + 2] & 0xffL) << 16)
                | (((long)b[o + 3] & 0xffL) << 24);
    }

    private static void putU32(byte[] b, int o, long v) {
        b[o] = (byte)(v & 0xff);
        b[o + 1] = (byte)((v >>> 8) & 0xff);
        b[o + 2] = (byte)((v >>> 16) & 0xff);
        b[o + 3] = (byte)((v >>> 24) & 0xff);
    }

    private static void writeU32(OutputStream out, long v) throws IOException {
        out.write((int)(v & 0xff));
        out.write((int)((v >>> 8) & 0xff));
        out.write((int)((v >>> 16) & 0xff));
        out.write((int)((v >>> 24) & 0xff));
    }

    private static void writeEntry(ZipOutputStream zout, ZipEntry template, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(template.getName());
        e.setTime(template.getTime());
        e.setComment(template.getComment());
        e.setExtra(template.getExtra());
        int method = template.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) method = ZipEntry.DEFLATED;
        e.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        }
        zout.putNextEntry(e);
        zout.write(data);
        zout.closeEntry();
    }
}

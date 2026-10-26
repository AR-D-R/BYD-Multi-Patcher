package com.bydspotifymanager.app;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Binary-manifest edits for the supported Spotify 9.1.82.2161 APKM bundle. */
final class Spotify91ManifestUtil {
    static final String OLD_PACKAGE = "com.spotify.music";
    private static final String CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection";
    private static final String MEDIA_ROUTE_SERVICE_SLOT =
            "com.spotify.connect.mediarouteproviderapi.SpotifyMediaRouteProviderService";

    /*
     * Only manifest values that are identities/authorities owned by the installed package are
     * retargeted. Real Spotify Java component class names deliberately stay under
     * com.spotify.music because the stock DEX classes are not relocated.
     */
    private static final Set<String> PACKAGE_OWNED_EXACT = new LinkedHashSet<>(Arrays.asList(
            OLD_PACKAGE,
            OLD_PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            OLD_PACKAGE + ".permission.C2D_MESSAGE",
            OLD_PACKAGE + ".permission.INTERNAL_BROADCAST",
            OLD_PACKAGE + ".permission.SECURED_BROADCAST",
            OLD_PACKAGE + ".androidx-startup",
            OLD_PACKAGE + ".share",
            OLD_PACKAGE + ".pushnotificationsv2",
            OLD_PACKAGE + ".profile",
            OLD_PACKAGE + ".vtec",
            OLD_PACKAGE + ".calimage",
            OLD_PACKAGE + ".early-initialization",
            OLD_PACKAGE + ".imagepicker",
            OLD_PACKAGE + ".sso.afterlogindummytask"
    ));

    static final class VersionInfo {
        final String name;
        final long code;
        VersionInfo(String name, long code) { this.name = name; this.code = code; }
    }

    private Spotify91ManifestUtil() {}

    static List<String> scanStrings(byte[] data) {
        if (data == null || data.length < 36) throw new IllegalArgumentException("AndroidManifest.xml is unexpectedly small");
        int rootHeader = Spotify89BinaryUtil.u16(data, 2);
        if (Spotify89BinaryUtil.u16(data, rootHeader) != 1) throw new IllegalArgumentException("Binary XML string pool missing");
        return Spotify89BinaryUtil.parsePool(data, rootHeader).strings;
    }

    static boolean isOriginalPackage(byte[] data) { return scanStrings(data).contains(OLD_PACKAGE); }

    static VersionInfo versionInfo(byte[] data) {
        int rootHs = Spotify89BinaryUtil.u16(data, 2);
        Spotify89BinaryUtil.Pool meta = Spotify89BinaryUtil.parsePool(data, rootHs);
        List<String> strings = meta.strings;
        int o = rootHs + meta.size;
        if (o + 8 <= data.length && Spotify89BinaryUtil.u16(data, o) == 0x0180) o += Spotify89BinaryUtil.i32(data, o + 4);
        while (o + 8 <= data.length) {
            int type = Spotify89BinaryUtil.u16(data, o), size = Spotify89BinaryUtil.i32(data, o + 4);
            if (size < 8 || o + size > data.length) break;
            if (type == 0x0102 && "manifest".equals(strings.get(Spotify89BinaryUtil.i32(data, o + 20)))) {
                int attrStart = Spotify89BinaryUtil.u16(data, o + 24), attrSize = Spotify89BinaryUtil.u16(data, o + 26), attrCount = Spotify89BinaryUtil.u16(data, o + 28);
                int ap = o + 16 + attrStart;
                String versionName = null;
                long versionCode = -1;
                for (int i = 0; i < attrCount; i++) {
                    int ao = ap + i * attrSize;
                    int nameIdx = Spotify89BinaryUtil.i32(data, ao + 4), rawIdx = Spotify89BinaryUtil.i32(data, ao + 8);
                    int dtype = data[ao + 15] & 0xff, dval = Spotify89BinaryUtil.i32(data, ao + 16);
                    String name = strings.get(nameIdx);
                    if ("versionName".equals(name)) {
                        if (rawIdx != Spotify89BinaryUtil.NO_INDEX) versionName = strings.get(rawIdx);
                        else if (dtype == 3 && dval >= 0 && dval < strings.size()) versionName = strings.get(dval);
                    } else if ("versionCode".equals(name)) {
                        versionCode = dval & 0xffffffffL;
                    }
                }
                return new VersionInfo(versionName, versionCode);
            }
            o += size;
        }
        return new VersionInfo(null, -1);
    }

    /**
     * Retargets manifest package-owned strings without touching unrelated Spotify namespaces such as
     * com.spotify.musicappplatform.*. All replacements are deliberately equal length so the binary
     * XML structure stays byte-for-byte stable apart from string contents.
     */
    static byte[] patchIdentity(byte[] data, String newPackage) {
        if (newPackage == null || newPackage.length() != OLD_PACKAGE.length()) {
            throw new IllegalArgumentException("9.1 clone package must preserve package-name length");
        }
        byte[] out = data.clone();
        int rootHs = Spotify89BinaryUtil.u16(out, 2);
        Spotify89BinaryUtil.Pool pool = Spotify89BinaryUtil.parsePool(out, rootHs);
        boolean utf8 = (pool.flags & 0x100) != 0;
        int headerSize = Spotify89BinaryUtil.u16(out, rootHs + 2);
        int stringCount = Spotify89BinaryUtil.i32(out, rootHs + 8);
        int stringsStart = Spotify89BinaryUtil.i32(out, rootHs + 20);
        boolean changedRootPackage = false;

        for (int i = 0; i < stringCount; i++) {
            int rel = Spotify89BinaryUtil.i32(out, rootHs + headerSize + 4 * i);
            int start = rootHs + stringsStart + rel;
            String s = pool.strings.get(i);
            String replacement = s;
            if (PACKAGE_OWNED_EXACT.contains(s)) {
                replacement = s.replace(OLD_PACKAGE, newPackage);
            } else if (CAR_CONNECTION_AUTHORITY.equals(s)) {
                String leaf = newPackage.substring(newPackage.lastIndexOf('.') + 1);
                replacement = leaf + "xxx.car.app.connection";
            }
            if (replacement.equals(s)) continue;
            if (replacement.length() != s.length()) {
                throw new IllegalStateException("Manifest identity edit changed string length: " + s);
            }
            overwritePoolString(out, start, utf8, s, replacement);
            if (OLD_PACKAGE.equals(s)) changedRootPackage = true;
        }
        if (!changedRootPackage) throw new IllegalArgumentException("Selected APK is not an original com.spotify.music package");
        return out;
    }

    static final String[] BYD_CLUSTER_PERMISSIONS = {
            "android.permission.BYDAUTO_INSTRUMENT_COMMON",
            "android.permission.BYDAUTO_INSTRUMENT_GET",
            "android.permission.BYDAUTO_INSTRUMENT_SET"
    };

    static byte[] patchAutoResume(byte[] data, String newPackage) {
        return patchPlaybackService(data, newPackage + ".AutoResumeService", false);
    }

    static byte[] patchDirectCluster(byte[] data, String newPackage) {
        byte[] out = patchPlaybackService(data, newPackage + ".AutoResumeService", true);
        return ManifestElementUtil.addRootStringElements(out, "uses-permission", BYD_CLUSTER_PERMISSIONS);
    }

    private static byte[] patchPlaybackService(byte[] data, String resumeService, boolean clusterBridge) {
        Map<String,String> replacements = new LinkedHashMap<>();
        replacements.put("android.media.MediaRoute2ProviderService", "byd.intent.action.RESTORE_PLAYBACK");
        replacements.put(MEDIA_ROUTE_SERVICE_SLOT, resumeService);
        data = Spotify89BinaryUtil.replaceXmlStrings(data, replacements);

        int rootHs = Spotify89BinaryUtil.u16(data, 2);
        Spotify89BinaryUtil.Pool meta = Spotify89BinaryUtil.parsePool(data, rootHs);
        List<String> ss = meta.strings;
        int o = rootHs + meta.size;
        if (o + 8 > data.length || Spotify89BinaryUtil.u16(data, o) != 0x0180) throw new IllegalArgumentException("Manifest resource map missing");
        int mapHeader = Spotify89BinaryUtil.u16(data, o + 2), mapSize = Spotify89BinaryUtil.i32(data, o + 4);
        List<Integer> resmap = new ArrayList<>();
        for (int p = o + mapHeader; p < o + mapSize; p += 4) resmap.add(Spotify89BinaryUtil.i32(data, p));
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(Arrays.copyOfRange(data, o, o + mapSize));
        o += mapSize;
        boolean found = false;

        while (o < data.length) {
            int typ = Spotify89BinaryUtil.u16(data, o), sz = Spotify89BinaryUtil.i32(data, o + 4);
            if (sz < 8 || o + sz > data.length) throw new IllegalArgumentException("Bad manifest chunk");
            byte[] c = Arrays.copyOfRange(data, o, o + sz);
            if (typ == 0x0102) {
                String tag = ss.get(Spotify89BinaryUtil.i32(c, 20));
                int attrStart = Spotify89BinaryUtil.u16(c, 24), attrSize = Spotify89BinaryUtil.u16(c, 26), attrCount = Spotify89BinaryUtil.u16(c, 28), ap = 16 + attrStart;
                List<Attr> attrs = new ArrayList<>();
                for (int i = 0; i < attrCount; i++) {
                    int ao = ap + i * attrSize, ni = Spotify89BinaryUtil.i32(c, ao + 4), raw = Spotify89BinaryUtil.i32(c, ao + 8), dt = c[ao + 15] & 0xff, dv = Spotify89BinaryUtil.i32(c, ao + 16);
                    String val = raw != Spotify89BinaryUtil.NO_INDEX ? ss.get(raw) : (dt == 3 && dv >= 0 && dv < ss.size() ? ss.get(dv) : null);
                    attrs.add(new Attr(ss.get(ni), ao, ni, dt, dv, val));
                }
                if ("service".equals(tag) && attrs.stream().anyMatch(a -> "name".equals(a.name) && resumeService.equals(a.value))) {
                    found = true;
                    int permissionIndex = -1;
                    for (int i = 0; i < attrs.size(); i++) {
                        Attr a = attrs.get(i);
                        if ("enabled".equals(a.name)) {
                            if (a.dtype != 0x12 || a.dval != 0) throw new IllegalArgumentException("Unexpected restore service enabled state");
                            Spotify89BinaryUtil.put32(c, a.off + 16, 0xffffffff);
                        } else if ("exported".equals(a.name)) {
                            if (a.dtype != 0x12) throw new IllegalArgumentException("Unexpected restore service exported type");
                            Spotify89BinaryUtil.put32(c, a.off + 16, 0xffffffff);
                        } else if (clusterBridge && "permission".equals(a.name)) {
                            permissionIndex = i;
                        }
                    }
                    // The reused MediaRoute2 provider slot can be protected by a system-only bind
                    // permission. Direct cluster mode repurposes it as our explicit foreground relay,
                    // so remove that unrelated provider bind permission.
                    if (permissionIndex >= 0) {
                        int removePos = ap + permissionIndex * attrSize;
                        byte[] nc = new byte[c.length - attrSize];
                        System.arraycopy(c, 0, nc, 0, removePos);
                        System.arraycopy(c, removePos + attrSize, nc, removePos, c.length - removePos - attrSize);
                        c = nc;
                        Spotify89BinaryUtil.put32(c, 4, sz - attrSize);
                        attrCount--;
                        attrs.remove(permissionIndex);
                    }
                    int fgIdx = ss.indexOf("foregroundServiceType"), androidNs = ss.indexOf("http://schemas.android.com/apk/res/android");
                    if (fgIdx < 0 || androidNs < 0) throw new IllegalArgumentException("Manifest foregroundServiceType metadata missing");
                    byte[] attr = new byte[20];
                    Spotify89BinaryUtil.put32(attr, 0, androidNs);
                    Spotify89BinaryUtil.put32(attr, 4, fgIdx);
                    Spotify89BinaryUtil.put32(attr, 8, 0xffffffff);
                    Spotify89BinaryUtil.put16(attr, 12, 8);
                    attr[14] = 0;
                    attr[15] = 0x11;
                    Spotify89BinaryUtil.put32(attr, 16, 2);
                    int targetRid = fgIdx < resmap.size() ? resmap.get(fgIdx) : 0xffffffff, insertIndex = attrCount;
                    for (int i = 0; i < attrs.size(); i++) {
                        int rid = attrs.get(i).nameIdx < resmap.size() ? resmap.get(attrs.get(i).nameIdx) : 0xffffffff;
                        if (Integer.compareUnsigned(rid, targetRid) > 0) { insertIndex = i; break; }
                    }
                    int insertPos = ap + insertIndex * attrSize;
                    byte[] nc = new byte[c.length + attrSize];
                    System.arraycopy(c, 0, nc, 0, insertPos);
                    System.arraycopy(attr, 0, nc, insertPos, attr.length);
                    System.arraycopy(c, insertPos, nc, insertPos + attrSize, c.length - insertPos);
                    c = nc;
                    Spotify89BinaryUtil.put32(c, 4, c.length);
                    Spotify89BinaryUtil.put16(c, 28, attrCount + 1);
                }
            }
            chunks.add(c);
            o += sz;
        }
        if (!found) throw new IllegalArgumentException("Spotify 9.1.82 auto-resume service slot not found");
        int prefix = rootHs + meta.size, total = prefix;
        for (byte[] c : chunks) total += c.length;
        byte[] out = new byte[total];
        System.arraycopy(data, 0, out, 0, prefix);
        int p = prefix;
        for (byte[] c : chunks) { System.arraycopy(c, 0, out, p, c.length); p += c.length; }
        Spotify89BinaryUtil.put32(out, 4, out.length);
        return out;
    }

    static byte[] disableAutoResumeTrigger(byte[] manifest) {
        String from = "byd.intent.action.RESTORE_PLAYBACK";
        String to = "byd.intent.action.RESTORE_DISABLED";
        byte[] out = IoUtil.replaceSameLength(manifest, from.getBytes(StandardCharsets.UTF_8), to.getBytes(StandardCharsets.UTF_8));
        return IoUtil.replaceSameLength(out, from.getBytes(StandardCharsets.UTF_16LE), to.getBytes(StandardCharsets.UTF_16LE));
    }

    private static void overwritePoolString(byte[] data, int start, boolean utf8, String oldValue, String newValue) {
        if (utf8) {
            int p = start;
            int[] chars = readLen8(data, p); p = chars[1];
            int[] bytes = readLen8(data, p); p = bytes[1];
            byte[] oldBytes = oldValue.getBytes(StandardCharsets.UTF_8), newBytes = newValue.getBytes(StandardCharsets.UTF_8);
            if (oldBytes.length != bytes[0] || newBytes.length != oldBytes.length) throw new IllegalStateException("UTF-8 manifest replacement length mismatch");
            System.arraycopy(newBytes, 0, data, p, newBytes.length);
        } else {
            int p = start;
            int[] chars = readLen16(data, p); p = chars[1];
            byte[] oldBytes = oldValue.getBytes(StandardCharsets.UTF_16LE), newBytes = newValue.getBytes(StandardCharsets.UTF_16LE);
            if (oldBytes.length != chars[0] * 2 || newBytes.length != oldBytes.length) throw new IllegalStateException("UTF-16 manifest replacement length mismatch");
            System.arraycopy(newBytes, 0, data, p, newBytes.length);
        }
    }

    private static int[] readLen8(byte[] b, int o) {
        int x = b[o++] & 0xff;
        if ((x & 0x80) != 0) x = ((x & 0x7f) << 8) | (b[o++] & 0xff);
        return new int[]{x, o};
    }

    private static int[] readLen16(byte[] b, int o) {
        int x = Spotify89BinaryUtil.u16(b, o); o += 2;
        if ((x & 0x8000) != 0) { x = ((x & 0x7fff) << 16) | Spotify89BinaryUtil.u16(b, o); o += 2; }
        return new int[]{x, o};
    }

    private static final class Attr {
        final String name, value;
        final int off, nameIdx, dtype, dval;
        Attr(String name, int off, int nameIdx, int dtype, int dval, String value) {
            this.name = name; this.off = off; this.nameIdx = nameIdx; this.dtype = dtype; this.dval = dval; this.value = value;
        }
    }
}

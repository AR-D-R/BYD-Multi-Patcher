package com.bydspotifymanager.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Persistent patched split-APK exports for Spotify, Waze and ABRP. */
final class PatchedBundleArchive {
    static final String INFO_ENTRY = "byd-multi-patcher.properties";
    private static final String FORMAT_VERSION = "1";

    static final class Info {
        final String packageName;
        final String product;
        final String version;
        final String mode;
        final String created;
        final String displayName;
        Info(Properties p) {
            packageName = p.getProperty("package", "");
            product = p.getProperty("product", "Spotify");
            version = p.getProperty("version", "unknown");
            mode = p.getProperty("mode", "standard");
            created = p.getProperty("created", "unknown");
            displayName = p.getProperty("displayName", product + " " + version);
        }
    }

    static final class Extracted {
        final Info info;
        final List<File> apks;
        final File dexMetadata;
        Extracted(Info info, List<File> apks, File dexMetadata) {
            this.info = info; this.apks = apks; this.dexMetadata = dexMetadata;
        }
    }

    private PatchedBundleArchive() {}

    /** Existing internal saved-bundle format, kept for profiles that still use .apks. */
    static String saveToDownloads(Context context, List<File> signedApks, File dm,
                                  String product, String version, String packageName,
                                  String mode, String displayName, String settings) throws Exception {
        return saveBundle(context, null, signedApks, dm, product, version, packageName,
                mode, displayName, settings, ".apks");
    }

    /**
     * Saves the patched split set as an APKM-compatible ZIP so file managers/installers that
     * understand APKM (for example CX File Explorer on the tester's unit) can install it directly.
     * The APKMirror outer signature is deliberately not copied because the archive is modified.
     */
    static String saveApkmToDownloads(Context context, File sourceApkm, List<File> signedApks, File dm,
                                      String product, String version, String packageName,
                                      String mode, String displayName, String settings) throws Exception {
        return saveBundle(context, sourceApkm, signedApks, dm, product, version, packageName,
                mode, displayName, settings, ".apkm");
    }

    private static String saveBundle(Context context, File sourceApkm, List<File> signedApks, File dm,
                                     String product, String version, String packageName,
                                     String mode, String displayName, String settings,
                                     String extension) throws Exception {
        if (signedApks == null || signedApks.isEmpty()) throw new IOException("No patched APKs to archive");
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String safeMode = sanitise(mode == null ? "standard" : mode);
        String fileName = sanitise(product) + "_" + sanitise(version) + "_" + safeMode + "_" + timestamp + extension;

        File tmp = new File(context.getCacheDir(), "patched_bundle_" + timestamp + extension);
        Properties info = new Properties();
        info.setProperty("format", FORMAT_VERSION);
        info.setProperty("managerVersion", BuildConfigData.MANAGER_VERSION);
        info.setProperty("package", packageName == null ? "" : packageName);
        info.setProperty("product", product == null ? "Spotify" : product);
        info.setProperty("version", version == null ? "unknown" : version);
        info.setProperty("mode", mode == null ? "standard" : mode);
        info.setProperty("created", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        info.setProperty("displayName", displayName == null ? product : displayName);
        if (settings != null && !settings.trim().isEmpty()) info.setProperty("settings", settings.trim());
        info.setProperty("container", extension.substring(1));

        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            ByteArrayOutputStream meta = new ByteArrayOutputStream();
            info.store(meta, "BYD Multi Patcher saved patched bundle");
            addBytes(zout, INFO_ENTRY, meta.toByteArray());

            if (".apkm".equals(extension)) {
                addBytes(zout, "info.json", buildApkmInfo(sourceApkm, product, version, packageName, displayName));
                byte[] icon = readOptionalEntry(sourceApkm, "icon.png");
                if (icon != null && icon.length > 0) addBytes(zout, "icon.png", icon);
            }

            for (File apk : signedApks) {
                if (apk == null || !apk.isFile()) throw new IOException("Patched APK missing while archiving");
                addFile(zout, apk.getName(), apk);
            }
            if (dm != null && dm.isFile()) addFile(zout, "base.dm", dm);
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, ".apkm".equals(extension) ? "application/octet-stream" : "application/zip");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BYD-Multi-Patcher");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("Android could not create the Downloads entry");
                boolean complete = false;
                try (InputStream in = new BufferedInputStream(new FileInputStream(tmp));
                     OutputStream out = resolver.openOutputStream(uri, "w")) {
                    if (out == null) throw new IOException("Android could not open the Downloads entry");
                    IoUtil.copy(in, out);
                    complete = true;
                } finally {
                    if (complete) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Downloads.IS_PENDING, 0);
                        resolver.update(uri, done, null, null);
                    } else {
                        resolver.delete(uri, null, null);
                    }
                }
                return "Downloads/BYD-Multi-Patcher/" + fileName;
            }

            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(downloads, "BYD-Multi-Patcher");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create Downloads/BYD-Multi-Patcher");
            File dest = uniqueFile(dir, fileName);
            try (InputStream in = new BufferedInputStream(new FileInputStream(tmp));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                IoUtil.copy(in, out);
            }
            return dest.getAbsolutePath();
        } finally {
            if (!tmp.delete()) tmp.deleteOnExit();
        }
    }

    private static byte[] buildApkmInfo(File sourceApkm, String product, String version,
                                        String packageName, String displayName) throws Exception {
        JSONObject json = null;
        byte[] original = readOptionalEntry(sourceApkm, "info.json");
        if (original != null) {
            try { json = new JSONObject(new String(original, StandardCharsets.UTF_8)); }
            catch (Exception ignored) { json = null; }
        }
        if (json == null) json = new JSONObject();

        String safeProduct = product == null || product.trim().isEmpty() ? "Spotify" : product.trim();
        String safeVersion = version == null || version.trim().isEmpty() ? "unknown" : version.trim();
        String safeDisplay = displayName == null || displayName.trim().isEmpty() ? safeProduct : displayName.trim();
        String title = safeDisplay + " " + safeVersion + " (BYD Multi Patcher)";

        if (!json.has("apkm_version")) json.put("apkm_version", 5);
        json.put("apk_title", title);
        json.put("app_name", safeDisplay);
        json.put("release_version", safeVersion);
        json.put("release_title", title);
        json.put("pname", packageName == null ? "" : packageName);
        json.put("byd_multi_patcher", BuildConfigData.MANAGER_VERSION);
        return json.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readOptionalEntry(File archive, String name) throws IOException {
        if (archive == null || !archive.isFile()) return null;
        try (ZipFile z = new ZipFile(archive)) {
            ZipEntry e = z.getEntry(name);
            if (e == null || e.isDirectory()) return null;
            try (InputStream in = new BufferedInputStream(z.getInputStream(e))) {
                return IoUtil.readAll(in);
            }
        }
    }

    static boolean isPatchedBundle(File file) {
        if (file == null || !file.isFile()) return false;
        try (ZipFile z = new ZipFile(file)) {
            ZipEntry info = z.getEntry(INFO_ENTRY);
            ZipEntry base = z.getEntry("base.apk");
            return info != null && base != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static Info readInfo(File file) throws Exception {
        try (ZipFile z = new ZipFile(file)) {
            ZipEntry e = z.getEntry(INFO_ENTRY);
            if (e == null) throw new IOException("Saved bundle metadata is missing");
            Properties p = new Properties();
            try (InputStream in = z.getInputStream(e)) { p.load(in); }
            if (!FORMAT_VERSION.equals(p.getProperty("format"))) throw new IOException("Unsupported saved bundle format");
            String pkg = p.getProperty("package", "").trim();
            if (pkg.isEmpty()) throw new IOException("Saved bundle package name is missing");
            return new Info(p);
        }
    }

    static Extracted extract(File archive, File outputDir) throws Exception {
        Info info = readInfo(archive);
        deleteTree(outputDir);
        if (!outputDir.mkdirs()) throw new IOException("Cannot create saved-bundle install directory");
        List<File> apks = new ArrayList<>();
        File dm = null;
        try (ZipFile z = new ZipFile(archive)) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || INFO_ENTRY.equals(name)) continue;
                if (name.contains("/") || name.contains("\\")) throw new IOException("Unsafe saved-bundle entry: " + name);
                if (!name.endsWith(".apk") && !"base.dm".equals(name)) continue;
                File out = new File(outputDir, name);
                try (InputStream in = new BufferedInputStream(z.getInputStream(e));
                     OutputStream fout = new BufferedOutputStream(new FileOutputStream(out))) {
                    IoUtil.copy(in, fout);
                }
                if (name.endsWith(".apk")) apks.add(out); else dm = out;
            }
        }
        if (apks.isEmpty()) throw new IOException("Saved bundle contains no APK files");
        apks.sort((a, b) -> {
            if ("base.apk".equals(a.getName())) return -1;
            if ("base.apk".equals(b.getName())) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        if (!"base.apk".equals(apks.get(0).getName())) throw new IOException("Saved bundle base.apk is missing");
        return new Extracted(info, apks, dm);
    }

    private static void addFile(ZipOutputStream zout, String name, File file) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(file.lastModified());
        zout.putNextEntry(e);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { IoUtil.copy(in, zout); }
        zout.closeEntry();
    }

    private static void addBytes(ZipOutputStream zout, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zout.putNextEntry(e);
        zout.write(data);
        zout.closeEntry();
    }

    private static File uniqueFile(File dir, String fileName) {
        File f = new File(dir, fileName);
        if (!f.exists()) return f;
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        for (int i = 2; i < 1000; i++) {
            f = new File(dir, stem + "-" + i + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    private static String sanitise(String value) {
        String s = value == null ? "patched" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        while (s.startsWith("-")) s = s.substring(1);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "patched" : s;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) f.deleteOnExit();
    }
}

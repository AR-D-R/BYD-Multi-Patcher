package com.bydspotifymanager.app;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates the exact ABRP bundle currently supported by the ABRP profile. */
final class AbrpSourceValidator {
    private static final int EXPECTED_APK_COUNT = 36;
    private static final Set<String> REQUIRED_SPLITS = new LinkedHashSet<>(Arrays.asList(
            "base.apk",
            "split_config.arm64_v8a.apk",
            "split_config.armeabi_v7a.apk",
            "split_config.x86.apk",
            "split_config.x86_64.apk",
            "split_config.ldpi.apk",
            "split_config.mdpi.apk",
            "split_config.tvdpi.apk",
            "split_config.hdpi.apk",
            "split_config.xhdpi.apk",
            "split_config.xxhdpi.apk",
            "split_config.xxxhdpi.apk",
            "split_config.en.apk"
    ));

    static final class Validation {
        final boolean ok;
        final String detail;
        final int apkCount;

        Validation(boolean ok, String detail, int apkCount) {
            this.ok = ok;
            this.detail = detail;
            this.apkCount = apkCount;
        }
    }

    private AbrpSourceValidator() {}

    static Validation validateSource(File apkm) {
        if (apkm == null || !apkm.isFile()) {
            return new Validation(false, "ABRP APKM file not found.", 0);
        }

        File baseTmp = null;
        try (ZipFile bundle = new ZipFile(apkm)) {
            int apkCount = 0;
            for (Enumeration<? extends ZipEntry> entries = bundle.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                if (!name.contains("/") && name.endsWith(".apk")) apkCount++;
            }
            if (apkCount != EXPECTED_APK_COUNT) {
                return new Validation(false, "Expected the linked ABRP 7.1.7 APKM with "
                        + EXPECTED_APK_COUNT + " APK splits; found " + apkCount + ".", apkCount);
            }
            for (String required : REQUIRED_SPLITS) {
                if (bundle.getEntry(required) == null) {
                    return new Validation(false, "ABRP APKM variant mismatch: missing " + required
                            + ". Use the linked universal 120-640dpi APKM variant.", apkCount);
                }
            }

            ZipEntry infoEntry = bundle.getEntry("info.json");
            if (infoEntry == null) {
                return new Validation(false, "This is not the supported APKMirror ABRP bundle: info.json is missing.", apkCount);
            }
            JSONObject info;
            try (InputStream in = bundle.getInputStream(infoEntry)) {
                info = new JSONObject(new String(IoUtil.readAll(in), StandardCharsets.UTF_8));
            }
            String packageName = info.optString("pname", "");
            String versionName = info.optString("release_version", "");
            long versionCode = info.optLong("versioncode", -1L);
            if (!BuildConfigData.ABRP_PACKAGE.equals(packageName)
                    || !BuildConfigData.ABRP_SUPPORTED_VERSION.equals(versionName)
                    || BuildConfigData.ABRP_SUPPORTED_VERSION_CODE != versionCode) {
                return new Validation(false, "Expected ABRP " + BuildConfigData.ABRP_SUPPORTED_VERSION
                        + " (" + BuildConfigData.ABRP_SUPPORTED_VERSION_CODE + ") but found "
                        + (versionName.isEmpty() ? "unknown" : versionName) + " (" + versionCode + ") · "
                        + (packageName.isEmpty() ? "unknown package" : packageName) + ".", apkCount);
            }

            ZipEntry baseEntry = bundle.getEntry("base.apk");
            if (baseEntry == null) {
                return new Validation(false, "This is not an ABRP APKM bundle: base.apk is missing.", apkCount);
            }
            baseTmp = File.createTempFile("abrp_base_", ".apk", apkm.getParentFile());
            try (InputStream in = new BufferedInputStream(bundle.getInputStream(baseEntry));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(baseTmp))) {
                IoUtil.copy(in, out);
            }
            String baseHash = IoUtil.sha256(baseTmp);
            if (!BuildConfigData.ABRP_BASE_SHA256.equalsIgnoreCase(baseHash)) {
                return new Validation(false, "ABRP base.apk does not match the supported APKMirror 7.1.7 bundle. SHA-256: "
                        + baseHash, apkCount);
            }
            try (ZipFile base = new ZipFile(baseTmp)) {
                if (base.getEntry("AndroidManifest.xml") == null || base.getEntry("resources.arsc") == null
                        || base.getEntry("classes.dex") == null) {
                    return new Validation(false, "ABRP base.apk is missing required Android app files.", apkCount);
                }
            }
            return new Validation(true, "Verified original ABRP " + BuildConfigData.ABRP_SUPPORTED_VERSION
                    + " APKM · universal 120-640dpi · " + apkCount + " APK splits", apkCount);
        } catch (Exception e) {
            return new Validation(false, e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        } finally {
            if (baseTmp != null && !baseTmp.delete()) baseTmp.deleteOnExit();
        }
    }
}

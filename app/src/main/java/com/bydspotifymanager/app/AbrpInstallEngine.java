package com.bydspotifymanager.app;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Selects, applies the ABRP BYD profile, signs, and stages a compatible APK set. */
final class AbrpInstallEngine {
    interface Progress { void onProgress(String message); }

    private static final int[] DENSITIES = {120, 160, 213, 240, 320, 480, 640};
    private static final String DEBUG_PROVIDER = "com.iternio.abrpapp.byddebug.StartupLogProvider";
    private static final String DEBUG_AUTHORITY = "com.iternio.abrpapp.byddebug.startuplog";
    private static final String DEBUG_ASSET = "abrp_debug_logger/classes.dex";

    private AbrpInstallEngine() {}

    static List<File> prepareInstallBundle(Context context, File sourceApkm, File outputDir,
                                           boolean directBydBattery,
                                           boolean autoDayNightDefault,
                                           boolean nativeBydTts,
                                           boolean enableDebugLogging,
                                           Progress progress) throws Exception {
        AbrpSourceValidator.Validation validation = AbrpSourceValidator.validateSource(sourceApkm);
        if (!validation.ok) throw new IllegalArgumentException(validation.detail);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("Cannot create ABRP install directory");
        }
        File stage = new File(outputDir, ".stage");
        if (!stage.mkdirs()) throw new IllegalStateException("Cannot create ABRP staging directory");

        try (ZipFile bundle = new ZipFile(sourceApkm)) {
            Set<String> selected = new LinkedHashSet<>();
            selected.add("base.apk");

            String abiSplit = selectAbiSplit(bundle);
            selected.add(abiSplit);

            int deviceDensity = context.getResources().getDisplayMetrics().densityDpi;
            String densitySplit = selectDensitySplit(bundle, deviceDensity);
            selected.add(densitySplit);

            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            String languageSplit = selectLanguageSplit(bundle, locale);
            if (languageSplit != null) selected.add(languageSplit);
            if (!"split_config.en.apk".equals(languageSplit)
                    && bundle.getEntry("split_config.en.apk") != null) {
                selected.add("split_config.en.apk");
            }

            List<File> files = new ArrayList<>();
            int index = 0;
            for (String name : selected) {
                index++;
                ZipEntry entry = bundle.getEntry(name);
                if (entry == null) throw new IllegalArgumentException("ABRP APKM is missing selected split " + name);
                File raw = new File(stage, name + ".raw");
                try (InputStream in = new BufferedInputStream(bundle.getInputStream(entry));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(raw))) {
                    IoUtil.copy(in, out);
                }

                File signingInput = raw;
                File patched = null;
                if ("base.apk".equals(name)) {
                    if (progress != null) progress.onProgress(
                            "Applying ABRP BYD layout, Android location compatibility"
                                    + (directBydBattery ? " and direct BYD battery data" : "")
                                    + (autoDayNightDefault ? " and Automatic Day/Night default" : "")
                                    + (nativeBydTts ? " and BYD native Cerence TTS" : "")
                                    + (enableDebugLogging ? " and privacy-safe 5-minute debug logging" : "") + "…");
                    patched = new File(stage, "base.patched.apk");
                    patchBaseApk(context, raw, patched, directBydBattery, autoDayNightDefault, nativeBydTts, enableDebugLogging);
                    signingInput = patched;
                }

                if (progress != null) progress.onProgress(
                        "Signing ABRP APK " + index + "/" + selected.size() + " · " + name);
                File signed = new File(outputDir, name);
                ApkSignerUtil.sign(signingInput, signed);
                files.add(signed);

                if (!raw.delete()) raw.deleteOnExit();
                if (patched != null && !patched.delete()) patched.deleteOnExit();
            }
            deleteTree(stage);

            if (progress != null) {
                progress.onProgress("ABRP BYD install set ready · " + abiSplit.substring("split_config.".length(), abiSplit.length() - 4)
                        + " · " + densitySplit.substring("split_config.".length(), densitySplit.length() - 4)
                        + " · " + files.size() + " locally signed APKs");
            }
            return files;
        } catch (Exception e) {
            deleteTree(stage);
            throw e;
        }
    }

    private static void patchBaseApk(Context context, File input, File output, boolean directBydBattery,
                                     boolean autoDayNightDefault, boolean nativeBydTts,
                                     boolean enableDebugLogging) throws Exception {
        try (ZipFile source = new ZipFile(input);
             CountingOutputStream counted = new CountingOutputStream(new FileOutputStream(output));
             ZipOutputStream zip = new ZipOutputStream(counted)) {
            boolean manifestPatched = false;
            boolean bundlePatched = false;
            boolean mainApplicationDexPatched = false;
            boolean locationDexPatched = false;
            boolean ttsDexPatched = false;
            boolean hookNeeded = directBydBattery || nativeBydTts;
            String debugDexName = hookNeeded ? "classes9.dex" : "classes8.dex";
            if (hookNeeded && source.getEntry("classes8.dex") != null) {
                throw new IllegalArgumentException("ABRP base.apk already contains classes8.dex; BYD Multi Patcher runtime hook slot is not free");
            }
            if (enableDebugLogging && source.getEntry(debugDexName) != null) {
                throw new IllegalArgumentException("ABRP base.apk already contains " + debugDexName
                        + "; privacy-safe debug logger slot is not free");
            }
            for (Enumeration<? extends ZipEntry> entries = source.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (isV1Signature(name) || "stamp-cert-sha256".equals(name)) continue;
                byte[] data = IoUtil.readAll(source.getInputStream(entry));
                if ("AndroidManifest.xml".equals(name)) {
                    data = AbrpPatchUtil.patchManifestForByd(data, directBydBattery, nativeBydTts);
                    if (enableDebugLogging) {
                        data = ManifestElementUtil.addApplicationProvider(
                                data, DEBUG_PROVIDER, DEBUG_AUTHORITY, true);
                    }
                    manifestPatched = true;
                } else if ("assets/index.android.bundle".equals(name)) {
                    data = AbrpPatchUtil.patchHermesBundle(data, autoDayNightDefault, nativeBydTts);
                    bundlePatched = true;
                } else if ("classes3.dex".equals(name)) {
                    if (directBydBattery) data = AbrpDexUtil.makeMainApplicationSubclassable(data);
                    mainApplicationDexPatched = true;
                } else if ("classes5.dex".equals(name)) {
                    data = AbrpDexUtil.forceAndroidLocationManager(data);
                    locationDexPatched = true;
                } else if ("classes7.dex".equals(name)) {
                    if (nativeBydTts) data = AbrpDexUtil.redirectTextToSpeechToByd(data);
                    ttsDexPatched = true;
                }
                writeEntry(zip, counted.count, entry, data);
            }
            if (!manifestPatched || !bundlePatched || !mainApplicationDexPatched || !locationDexPatched || !ttsDexPatched) {
                throw new IllegalArgumentException("ABRP base.apk is missing required BYD display/location patch targets");
            }

            if (hookNeeded) {
                byte[] directDex;
                try (InputStream in = context.getAssets().open("abrp_direct_hook/classes.dex")) {
                    directDex = IoUtil.readAll(in);
                }
                if (directDex.length < 256 || directDex[0] != 'd' || directDex[1] != 'e' || directDex[2] != 'x') {
                    throw new IOException("Generated ABRP runtime hook DEX is missing or invalid");
                }
                ZipEntry directHook = new ZipEntry("classes8.dex");
                directHook.setMethod(ZipEntry.DEFLATED);
                writeEntry(zip, counted.count, directHook, directDex);
            }

            if (enableDebugLogging) {
                byte[] debugDex;
                try (InputStream in = context.getAssets().open(DEBUG_ASSET)) {
                    debugDex = IoUtil.readAll(in);
                }
                if (debugDex.length < 256 || debugDex[0] != 'd' || debugDex[1] != 'e' || debugDex[2] != 'x') {
                    throw new IOException("Generated ABRP privacy-safe debug logger DEX is missing or invalid");
                }
                ZipEntry debugHook = new ZipEntry(debugDexName);
                debugHook.setMethod(ZipEntry.DEFLATED);
                writeEntry(zip, counted.count, debugHook, debugDex);
            }
        }
        verifyPatchedBase(output, directBydBattery, autoDayNightDefault, nativeBydTts, enableDebugLogging);
    }

    private static void verifyPatchedBase(File output, boolean directBydBattery,
                                          boolean autoDayNightDefault, boolean nativeBydTts,
                                          boolean enableDebugLogging) throws Exception {
        if (!output.isFile() || output.length() < 1024) {
            throw new IOException("Patched ABRP base.apk was not created correctly");
        }
        try (ZipFile zip = new ZipFile(output)) {
            ZipEntry bundle = zip.getEntry("assets/index.android.bundle");
            ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
            ZipEntry classes3 = zip.getEntry("classes3.dex");
            ZipEntry classes5 = zip.getEntry("classes5.dex");
            ZipEntry classes7 = zip.getEntry("classes7.dex");
            ZipEntry directHook = zip.getEntry("classes8.dex");
            boolean hookNeeded = directBydBattery || nativeBydTts;
            String debugDexName = hookNeeded ? "classes9.dex" : "classes8.dex";
            ZipEntry debugHook = zip.getEntry(debugDexName);
            if (bundle == null || manifest == null || classes3 == null || classes5 == null || classes7 == null
                    || (hookNeeded && directHook == null)
                    || (!hookNeeded && !enableDebugLogging && directHook != null)
                    || (enableDebugLogging && debugHook == null)
                    || (!enableDebugLogging && zip.getEntry("classes9.dex") != null)) {
                throw new IOException("Patched ABRP base.apk is incomplete or has an unexpected BYD Multi Patcher runtime/debug DEX");
            }
            byte[] manifestBytes = IoUtil.readAll(zip.getInputStream(manifest));
            AbrpPatchUtil.verifyPatchedManifest(manifestBytes, directBydBattery, nativeBydTts);
            int debugProviders = ManifestElementUtil.countApplicationProvider(manifestBytes, DEBUG_PROVIDER);
            int expectedDebugProviders = enableDebugLogging ? 1 : 0;
            if (debugProviders != expectedDebugProviders) {
                throw new IOException("Expected " + expectedDebugProviders
                        + " ABRP debug provider(s), found " + debugProviders);
            }
            AbrpPatchUtil.verifyPatchedHermes(IoUtil.readAll(zip.getInputStream(bundle)), autoDayNightDefault, nativeBydTts);
            if (directBydBattery) {
                AbrpDexUtil.verifyMainApplicationSubclassable(IoUtil.readAll(zip.getInputStream(classes3)));
            }
            AbrpDexUtil.verifyAndroidLocationManagerForced(IoUtil.readAll(zip.getInputStream(classes5)));
            if (nativeBydTts) AbrpDexUtil.verifyTextToSpeechRedirect(IoUtil.readAll(zip.getInputStream(classes7)));
            else AbrpDexUtil.verifyTextToSpeechStock(IoUtil.readAll(zip.getInputStream(classes7)));
            if (hookNeeded) {
                byte[] directDex = IoUtil.readAll(zip.getInputStream(directHook));
                if (directDex.length < 256 || directDex[0] != 'd' || directDex[1] != 'e' || directDex[2] != 'x') {
                    throw new IOException("Patched ABRP runtime classes8.dex is invalid");
                }
            }
            if (enableDebugLogging) {
                byte[] debugDex = IoUtil.readAll(zip.getInputStream(debugHook));
                if (debugDex.length < 256 || debugDex[0] != 'd' || debugDex[1] != 'e' || debugDex[2] != 'x') {
                    throw new IOException("Patched ABRP privacy-safe debug " + debugDexName + " is invalid");
                }
            }
        }
    }

    private static String selectAbiSplit(ZipFile bundle) {
        for (String abi : Build.SUPPORTED_ABIS) {
            String candidate = "split_config." + abi.replace('-', '_') + ".apk";
            if (bundle.getEntry(candidate) != null) return candidate;
        }
        throw new IllegalArgumentException("ABRP APKM has no ABI split compatible with this device. Device ABIs: "
                + Arrays.toString(Build.SUPPORTED_ABIS));
    }

    private static String selectDensitySplit(ZipFile bundle, int deviceDensity) {
        int bestDensity = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int density : DENSITIES) {
            String candidate = "split_config." + densityName(density) + ".apk";
            if (bundle.getEntry(candidate) == null) continue;
            int distance = Math.abs(deviceDensity - density);
            if (distance < bestDistance || (distance == bestDistance && density > bestDensity)) {
                bestDensity = density;
                bestDistance = distance;
            }
        }
        if (bestDensity < 0) throw new IllegalArgumentException("ABRP APKM has no compatible screen-density split.");
        return "split_config." + densityName(bestDensity) + ".apk";
    }

    private static String selectLanguageSplit(ZipFile bundle, Locale locale) {
        String language = locale == null ? "en" : locale.getLanguage().toLowerCase(Locale.US);
        if ("id".equals(language)) language = "in";
        String candidate = "split_config." + language + ".apk";
        if (bundle.getEntry(candidate) != null) return candidate;
        return bundle.getEntry("split_config.en.apk") == null ? null : "split_config.en.apk";
    }

    private static String densityName(int density) {
        switch (density) {
            case DisplayMetrics.DENSITY_LOW: return "ldpi";
            case DisplayMetrics.DENSITY_MEDIUM: return "mdpi";
            case DisplayMetrics.DENSITY_TV: return "tvdpi";
            case DisplayMetrics.DENSITY_HIGH: return "hdpi";
            case DisplayMetrics.DENSITY_XHIGH: return "xhdpi";
            case DisplayMetrics.DENSITY_XXHIGH: return "xxhdpi";
            case DisplayMetrics.DENSITY_XXXHIGH: return "xxxhdpi";
            default: throw new IllegalArgumentException("Unsupported density " + density);
        }
    }

    private static boolean isV1Signature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if ("META-INF/MANIFEST.MF".equals(upper)) return true;
        return upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    /** Preserves four-byte APK alignment and 4096-byte alignment for stored native libraries. */
    private static void writeEntry(ZipOutputStream zip, long currentOffset, ZipEntry template, byte[] data)
            throws IOException {
        ZipEntry entry = new ZipEntry(template.getName());
        entry.setTime(template.getTime());
        entry.setComment(template.getComment());
        byte[] extra = template.getExtra();
        if (extra == null) extra = new byte[0]; else extra = extra.clone();
        int method = template.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) method = ZipEntry.DEFLATED;
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            int alignment = entry.getName().startsWith("lib/") && entry.getName().endsWith(".so") ? 4096 : 4;
            byte[] name = entry.getName().getBytes(StandardCharsets.UTF_8);
            long base = currentOffset + 30L + name.length + extra.length;
            if ((base & (alignment - 1L)) != 0) {
                int padding = (int) ((-base - 4L) & (alignment - 1L));
                byte[] aligned = Arrays.copyOf(extra, extra.length + 4 + padding);
                int p = extra.length;
                aligned[p] = 0x35;
                aligned[p + 1] = (byte) 0xd9;
                aligned[p + 2] = (byte) padding;
                aligned[p + 3] = (byte) (padding >>> 8);
                extra = aligned;
            }
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        }
        if (extra.length > 0) entry.setExtra(extra);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }
}

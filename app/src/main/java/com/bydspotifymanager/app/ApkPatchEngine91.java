package com.bydspotifymanager.app;

import android.content.Context;
import android.os.Build;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Spotify 9.1.82.2161 APKMirror APKM patch engine. */
final class ApkPatchEngine91 {
    interface Progress { void onProgress(String message); }

    private static final int EXPECTED_APKM_APK_COUNT = 39;
    private static final int EXPECTED_BASE_DEX_COUNT = 14;
    private static final String AUTO_RESUME_ASSET = "auto_resume/classes.dex";
    private static final String CLUSTER_BRIDGE_ASSET = "cluster_bridge/classes.dex";
    private static final String AUTO_RESUME_DEX = "classes15.dex";
    private static final String LHS_DEX = "classes16.dex";
    private static final String MEDIA_AUTHORITY_OLD = "com.spotify.mobile.android.mediaapi";

    private static final Set<String> REQUIRED_SPLITS = new LinkedHashSet<>(Arrays.asList(
            "base.apk",
            "split_config.arm64_v8a.apk",
            "split_config.armeabi_v7a.apk",
            "split_config.x86.apk",
            "split_config.x86_64.apk",
            "split_config.mdpi.apk",
            "split_config.xxxhdpi.apk",
            "split_audiomodelimpl_dynamic_wrapper.apk",
            "split_listening_party_message_source_dynamic_wrapper.apk"
    ));

    static final class Validation {
        final boolean ok;
        final String detail;
        final int apkCount;
        Validation(boolean ok, String detail, int apkCount) { this.ok = ok; this.detail = detail; this.apkCount = apkCount; }
    }

    private ApkPatchEngine91() {}

    static Validation validateSource(File apkm) {
        if (apkm == null || !apkm.isFile()) return new Validation(false, "Spotify 9.1 APKM file not found.", 0);
        File baseTmp = null;
        try (ZipFile bundle = new ZipFile(apkm)) {
            List<String> apkNames = topLevelApks(bundle);
            if (apkNames.size() != EXPECTED_APKM_APK_COUNT) {
                return new Validation(false, "Expected the supported Spotify 9.1.82 APKM with " + EXPECTED_APKM_APK_COUNT
                        + " APK splits; found " + apkNames.size() + ".", apkNames.size());
            }
            for (String required : REQUIRED_SPLITS) {
                if (!apkNames.contains(required)) return new Validation(false,
                        "Spotify 9.1 APKM variant mismatch: missing " + required + ". Use the linked universal 120-640dpi APKM variant.", apkNames.size());
            }
            ZipEntry baseEntry = bundle.getEntry("base.apk");
            if (baseEntry == null) return new Validation(false, "This is not a Spotify APKM bundle: base.apk is missing.", apkNames.size());
            baseTmp = File.createTempFile("spotify91_base_", ".apk", apkm.getParentFile());
            try (InputStream in = new BufferedInputStream(bundle.getInputStream(baseEntry));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(baseTmp))) {
                IoUtil.copy(in, out);
            }
            String baseHash = IoUtil.sha256(baseTmp);
            if (!BuildConfigData.STOCK_SHA256.equalsIgnoreCase(baseHash)) {
                return new Validation(false, "Spotify 9.1 base.apk does not match the supported APKMirror bundle. SHA-256: " + baseHash, apkNames.size());
            }
            Validation base = validateBaseApk(baseTmp, apkNames.size());
            if (!base.ok) return base;
            return new Validation(true, "Verified original Spotify " + BuildConfigData.SUPPORTED_VERSION
                    + " APKM · " + apkNames.size() + " APK splits", apkNames.size());
        } catch (Exception e) {
            return new Validation(false, e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        } finally {
            if (baseTmp != null && !baseTmp.delete()) baseTmp.deleteOnExit();
        }
    }

    private static Validation validateBaseApk(File apk, int apkCount) {
        try (ZipFile z = new ZipFile(apk)) {
            String[] required = {"AndroidManifest.xml", "resources.arsc", "classes.dex", PlayerPositionUtil.LARGE_MAIN_PATH,
                    "assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"};
            for (String n : required) if (z.getEntry(n) == null) return new Validation(false, "Missing required Spotify 9.1 base.apk entry: " + n, apkCount);
            byte[] manifest = IoUtil.readAll(z.getInputStream(z.getEntry("AndroidManifest.xml")));
            if (!Spotify91ManifestUtil.isOriginalPackage(manifest)) return new Validation(false, "Selected APKM base is not original com.spotify.music.", apkCount);
            Spotify91ManifestUtil.VersionInfo vi = Spotify91ManifestUtil.versionInfo(manifest);
            if (!BuildConfigData.SUPPORTED_VERSION.equals(vi.name) || vi.code != BuildConfigData.SUPPORTED_VERSION_CODE) {
                return new Validation(false, "Expected Spotify " + BuildConfigData.SUPPORTED_VERSION + " (" + BuildConfigData.SUPPORTED_VERSION_CODE
                        + ") but found " + vi.name + " (" + vi.code + ").", apkCount);
            }
            List<String> strings = Spotify91ManifestUtil.scanStrings(manifest);
            if (!strings.contains("com.spotify.connect.mediarouteproviderapi.SpotifyMediaRouteProviderService")
                    || !strings.contains("android.media.MediaRoute2ProviderService") || !strings.contains("foregroundServiceType")) {
                return new Validation(false, "Spotify 9.1.82 auto-resume service slot was not found.", apkCount);
            }
            PlayerPositionUtil.moveLargePlayerLeft(IoUtil.readAll(z.getInputStream(z.getEntry(PlayerPositionUtil.LARGE_MAIN_PATH))));
            for (String p : IconBrandingUtil.DENSITY_ICONS) if (z.getEntry(p) == null) {
                return new Validation(false, "Spotify 9.1 launcher icon resource missing from base.apk: " + p, apkCount);
            }
            int dexCount = 0;
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                String n = en.nextElement().getName();
                if (n.matches("classes(\\d+)?\\.dex")) dexCount++;
            }
            if (dexCount != EXPECTED_BASE_DEX_COUNT || z.getEntry(AUTO_RESUME_DEX) != null || z.getEntry(LHS_DEX) != null) {
                return new Validation(false, "Expected Spotify 9.1.82 base with " + EXPECTED_BASE_DEX_COUNT + " stock DEX files; found " + dexCount + ".", apkCount);
            }
            return new Validation(true, "Verified Spotify " + BuildConfigData.SUPPORTED_VERSION + " APKM base.apk", apkCount);
        } catch (Exception e) {
            return new Validation(false, e.getClass().getSimpleName() + ": " + e.getMessage(), apkCount);
        }
    }

    static List<File> buildSignedBundle(Context context, File sourceApkm, File outputDir,
                                        boolean secondary, String appName, float scaleFactor,
                                        boolean bydLayout, boolean playerLeft, boolean autoResume,
                                        boolean clusterBridge, int iconHue, boolean iconBadge, Progress progress) throws Exception {
        Validation validation = validateSource(sourceApkm);
        if (!validation.ok) throw new IllegalArgumentException(validation.detail);
        String targetPackage = secondary ? BuildConfigData.SECONDARY_PACKAGE : BuildConfigData.PRIMARY_PACKAGE;
        String clean = appName == null ? "" : appName.trim();
        if (clean.isEmpty()) clean = secondary ? "SpotifyPlus2" : "SpotifyPlus";
        if (clean.codePointCount(0, clean.length()) > 32) throw new IllegalArgumentException("Spotify 9.1 app name can be up to 32 characters.");

        if (outputDir.exists()) deleteTree(outputDir);
        if (!outputDir.mkdirs()) throw new IOException("Cannot create Spotify 9.1 APKM build directory");
        File stage = new File(outputDir, ".stage");
        if (!stage.mkdirs()) throw new IOException("Cannot create Spotify 9.1 staging directory");

        try (ZipFile bundle = new ZipFile(sourceApkm)) {
            List<String> allNames = topLevelApks(bundle);
            String selectedAbi = chooseAbi(allNames);
            List<String> names = new ArrayList<>();
            for (String n : allNames) {
                String abi = abiForSplit(n);
                if (abi != null && !abi.equals(selectedAbi)) continue;
                names.add(n);
            }
            names.sort((a, b) -> {
                if ("base.apk".equals(a)) return -1;
                if ("base.apk".equals(b)) return 1;
                return a.compareToIgnoreCase(b);
            });

            progress.onProgress("Spotify 9.1 APKM · selected ABI " + selectedAbi + " · " + names.size() + " APKs will be installed.");
            List<File> signed = new ArrayList<>();
            int index = 0;
            for (String name : names) {
                index++;
                ZipEntry outer = bundle.getEntry(name);
                if (outer == null) throw new IOException("APKM entry disappeared: " + name);
                File raw = new File(stage, name + ".raw");
                try (InputStream in = new BufferedInputStream(bundle.getInputStream(outer));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(raw))) { IoUtil.copy(in, out); }
                File patched = new File(stage, name + ".patched");
                if ("base.apk".equals(name)) {
                    progress.onProgress("Patching Spotify 9.1.82 base.apk…");
                    patchBase(context, raw, patched, targetPackage, clean, scaleFactor, bydLayout, playerLeft, autoResume,
                            clusterBridge, iconHue, iconBadge, progress);
                } else {
                    patchSplit(raw, patched, targetPackage, scaleFactor, clusterBridge);
                }
                progress.onProgress("Signing Spotify 9.1 APK " + index + "/" + names.size() + " · " + name);
                File signedApk = new File(outputDir, name);
                ApkSignerUtil.sign(patched, signedApk);
                signed.add(signedApk);
                if (!raw.delete()) raw.deleteOnExit();
                if (!patched.delete()) patched.deleteOnExit();
            }
            deleteTree(stage);
            progress.onProgress("Spotify 9.1 APKM patched · base + " + (names.size() - 1)
                    + " compatible splits signed with one local certificate.");
            return signed;
        } catch (Exception e) {
            deleteTree(stage);
            throw e;
        }
    }

    private static void patchBase(Context context, File source, File output, String targetPackage,
                                  String appName, float scaleFactor, boolean bydLayout, boolean playerLeft,
                                  boolean autoResume, boolean clusterBridge, int iconHue, boolean iconBadge, Progress progress) throws Exception {
        Validation validation = validateBaseApk(source, EXPECTED_APKM_APK_COUNT);
        if (!validation.ok) throw new IllegalArgumentException(validation.detail);
        boolean modifyIcon = iconBadge || IconBrandingUtil.normaliseHue(iconHue) != 0;

        try (ZipFile zin = new ZipFile(source)) {
            byte[] manifest = IoUtil.readAll(zin.getInputStream(zin.getEntry("AndroidManifest.xml")));
            manifest = Spotify91ManifestUtil.patchIdentity(manifest, targetPackage);
            manifest = clusterBridge ? Spotify91ManifestUtil.patchDirectCluster(manifest, targetPackage)
                    : Spotify91ManifestUtil.patchAutoResume(manifest, targetPackage);
            // Direct-cluster mode keeps the RESTORE_PLAYBACK trigger so the relay can start after a
            // vehicle restart even when playback Auto Resume itself is disabled in the helper DEX.
            if (!autoResume && !clusterBridge) manifest = Spotify91ManifestUtil.disableAutoResumeTrigger(manifest);

            byte[] resources = IoUtil.readAll(zin.getInputStream(zin.getEntry("resources.arsc")));
            resources = patchResourceIdentity(resources, targetPackage);
            if (modifyIcon) resources = IconBrandingUtil.retargetAdaptiveForegroundResource(resources);
            if (scaleFactor != 1f) ResourceScaler.scaleResourceTable(resources, scaleFactor);
            resources = BinaryStringPoolUtil.replaceFirstString(resources, "Spotify", appName);

            Map<String, byte[]> brandedIcons = new HashMap<>();
            byte[] adaptiveForegroundPng = null;
            if (modifyIcon) {
                for (String p : IconBrandingUtil.DENSITY_ICONS) {
                    ZipEntry e = zin.getEntry(p);
                    if (e == null) throw new IOException("Spotify launcher icon missing: " + p);
                    brandedIcons.put(p, IconBrandingUtil.brandDensityIcon(IoUtil.readAll(zin.getInputStream(e)), iconHue, iconBadge));
                }
                byte[] xxx = IoUtil.readAll(zin.getInputStream(zin.getEntry(IconBrandingUtil.DENSITY_ICONS[IconBrandingUtil.DENSITY_ICONS.length - 1])));
                adaptiveForegroundPng = IconBrandingUtil.buildAdaptiveForegroundPng(xxx, iconHue, iconBadge);
            }

            byte[] autoDex;
            if (clusterBridge) {
                autoDex = retargetHelperDex(IoUtil.readAll(context.getAssets().open(CLUSTER_BRIDGE_ASSET)), targetPackage);
                if (!autoResume) {
                    autoDex = IoUtil.replaceSameLength(autoDex,
                            "AIVI_CLUSTER_AUTOR=1".getBytes(StandardCharsets.US_ASCII),
                            "AIVI_CLUSTER_AUTOR=0".getBytes(StandardCharsets.US_ASCII));
                    autoDex = DexUtil.repair(autoDex);
                }
                byte[] clusterServiceMarker = ("L" + targetPackage.replace('.', '/') + "/AutoResumeService;").getBytes(StandardCharsets.US_ASCII);
                byte[] clusterConfigMarker = "AIVI_CLUSTER_AUTOR=".getBytes(StandardCharsets.US_ASCII);
                if (autoDex.length < 512 || !contains(autoDex, clusterServiceMarker) || !contains(autoDex, clusterConfigMarker))
                    throw new IOException("Generated direct BYD instrument-cluster relay DEX is missing or invalid");
            } else {
                autoDex = retargetHelperDex(IoUtil.readAll(context.getAssets().open(AUTO_RESUME_ASSET)), targetPackage);
                if (autoDex.length < 512 || !contains(autoDex, "AIVI-AutoResume".getBytes(StandardCharsets.US_ASCII)))
                    throw new IOException("Generated robust AutoResume helper DEX is missing or invalid");
            }
            byte[] lhsDex = null;
            if (playerLeft) {
                lhsDex = IoUtil.readAll(context.getAssets().open(PlayerPositionUtil.HOOK_ASSET));
                if (lhsDex.length < 256 || lhsDex[0] != 'd' || lhsDex[1] != 'e' || lhsDex[2] != 'x') {
                    throw new IOException("Generated LHS runtime hook DEX is missing or invalid");
                }
            }

            try (CountingOutputStream counted = new CountingOutputStream(new FileOutputStream(output));
                 ZipOutputStream zout = new ZipOutputStream(counted)) {
                for (Enumeration<? extends ZipEntry> en = zin.entries(); en.hasMoreElements();) {
                    ZipEntry src = en.nextElement();
                    String name = src.getName();
                    if (isV1Signature(name) || "stamp-cert-sha256".equals(name)) continue;
                    if (modifyIcon && IconBrandingUtil.ADAPTIVE_FOREGROUND_XML.equals(name)) continue;
                    byte[] data;
                    if ("AndroidManifest.xml".equals(name)) data = manifest;
                    else if ("resources.arsc".equals(name)) data = resources;
                    else if (brandedIcons.containsKey(name)) data = brandedIcons.get(name);
                    else {
                        data = IoUtil.readAll(zin.getInputStream(src));
                        if (name.matches("classes(\\d+)?\\.dex")) {
                            data = patchDexIdentity(data, targetPackage);
                            if (scaleFactor != 1f && "classes9.dex".equals(name)) {
                                data = Spotify91ComposeScaleUtil.applyClasses9Dex(data, scaleFactor);
                            }
                        } else if (name.endsWith(".xml") && name.startsWith("res/") && scaleFactor != 1f && shouldScaleBinaryXml(name)) {
                            ResourceScaler.scaleBinaryXml(data, scaleFactor);
                        }
                        if (playerLeft && PlayerPositionUtil.LARGE_MAIN_PATH.equals(name)) data = PlayerPositionUtil.moveLargePlayerLeft(data);
                    }
                    writeEntry(zout, counted.count, src, data);
                }
                if (modifyIcon && adaptiveForegroundPng != null) writeAdded(zout, IconBrandingUtil.ADAPTIVE_FOREGROUND_PNG, adaptiveForegroundPng);
                writeAdded(zout, AUTO_RESUME_DEX, autoDex);
                if (playerLeft) writeAdded(zout, LHS_DEX, lhsDex);
            }
        }

        // Keep Spotify 9.1.82's own trained ART profile, but bind its DEX checksums to the cloned APK.
        ProfileMetadataUtil.updateEmbeddedBaselineProfile(output);
        verifyBaseOutput(output, targetPackage, appName, playerLeft, autoResume, modifyIcon, clusterBridge);
        progress.onProgress("9.1.82 package clone + provider authorities retargeted to " + targetPackage + ".");
        progress.onProgress("Embedded 9.1.82 performance profile matched to final DEX CRCs.");
        if (scaleFactor != 1f) progress.onProgress(Math.round(scaleFactor * 100f) + "% resource + Compose display scale applied from the 9.1.82 stock baseline.");
        if (bydLayout) progress.onProgress("Wide-screen layout: the legacy 9.1.78 fixed-offset map is skipped on 9.1.82; the version-safe stock container geometry is retained.");
        if (scaleFactor != 1f) progress.onProgress("9.1.82 Compose Density display-scale hook applied to Home/library tiles and text.");
        progress.onProgress("9.1.78-only login byte-offset hook remains intentionally disabled on 9.1.82.");
        progress.onProgress(playerLeft ? "Player position: LHS large-screen runtime mirror enabled." : "Player position: RHS stock layout.");
        progress.onProgress(autoResume ? "Vehicle restart playback resume enabled · robust connection/session/play verification." : "Vehicle restart playback resume disabled.");
        if (clusterBridge) progress.onProgress("Instrument cluster: direct BYD media relay injected into " + targetPackage + "; stock com.spotify.music remains untouched.");
    }

    private static void patchSplit(File source, File output, String targetPackage, float scaleFactor, boolean clusterBridge) throws Exception {
        try (ZipFile zin = new ZipFile(source);
             CountingOutputStream counted = new CountingOutputStream(new FileOutputStream(output));
             ZipOutputStream zout = new ZipOutputStream(counted)) {
            ZipEntry manifestEntry = zin.getEntry("AndroidManifest.xml");
            if (manifestEntry == null) throw new IllegalArgumentException("Spotify 9.1 split manifest missing: " + source.getName());
            byte[] manifest = IoUtil.readAll(zin.getInputStream(manifestEntry));
            manifest = Spotify91ManifestUtil.patchIdentity(manifest, targetPackage);
            for (Enumeration<? extends ZipEntry> en = zin.entries(); en.hasMoreElements();) {
                ZipEntry src = en.nextElement();
                String name = src.getName();
                if (isV1Signature(name) || "stamp-cert-sha256".equals(name)) continue;
                byte[] data = "AndroidManifest.xml".equals(name) ? manifest : IoUtil.readAll(zin.getInputStream(src));
                if ("resources.arsc".equals(name)) {
                    data = patchResourceIdentity(data, targetPackage);
                    if (scaleFactor != 1f) ResourceScaler.scaleResourceTable(data, scaleFactor);
                } else if (name.startsWith("res/") && name.endsWith(".xml") && scaleFactor != 1f && shouldScaleBinaryXml(name)) {
                    ResourceScaler.scaleBinaryXml(data, scaleFactor);
                }
                writeEntry(zout, counted.count, src, data);
            }
        }
        verifySplitOutput(output, targetPackage, clusterBridge);
    }

    private static byte[] patchResourceIdentity(byte[] resources, String targetPackage) {
        byte[] out = resources;
        out = IoUtil.replaceSameLength(out, BuildConfigData.OFFICIAL_PACKAGE.getBytes(StandardCharsets.UTF_16LE), targetPackage.getBytes(StandardCharsets.UTF_16LE));
        String authorityTo = targetPackage + ".android.mediaapi_";
        if (authorityTo.length() != MEDIA_AUTHORITY_OLD.length()) throw new IllegalStateException("Media authority replacement length mismatch");
        out = IoUtil.replaceSameLength(out, MEDIA_AUTHORITY_OLD.getBytes(StandardCharsets.UTF_8), authorityTo.getBytes(StandardCharsets.UTF_8));
        out = IoUtil.replaceSameLength(out, MEDIA_AUTHORITY_OLD.getBytes(StandardCharsets.UTF_16LE), authorityTo.getBytes(StandardCharsets.UTF_16LE));
        return out;
    }

    private static byte[] patchDexIdentity(byte[] dex, String targetPackage) throws Exception {
        String from = BuildConfigData.OFFICIAL_PACKAGE;
        if (from.length() != targetPackage.length() || from.length() >= 0x80) throw new IllegalStateException("Clone DEX package replacement must preserve short string length");
        byte[] oldItem = new byte[from.length() + 2], newItem = new byte[targetPackage.length() + 2];
        oldItem[0] = (byte) from.length(); newItem[0] = (byte) targetPackage.length();
        System.arraycopy(from.getBytes(StandardCharsets.US_ASCII), 0, oldItem, 1, from.length());
        System.arraycopy(targetPackage.getBytes(StandardCharsets.US_ASCII), 0, newItem, 1, targetPackage.length());
        byte[] changed = IoUtil.replaceSameLength(dex, oldItem, newItem);
        return Arrays.equals(changed, dex) ? dex : DexUtil.repair(changed);
    }

    private static byte[] retargetHelperDex(byte[] dex, String targetPackage) throws Exception {
        String base = BuildConfigData.PRIMARY_PACKAGE;
        if (base.length() != targetPackage.length()) throw new IllegalArgumentException("AutoResume helper package replacement length mismatch");
        byte[] out = IoUtil.replaceSameLength(dex, base.getBytes(StandardCharsets.US_ASCII), targetPackage.getBytes(StandardCharsets.US_ASCII));
        out = IoUtil.replaceSameLength(out, base.replace('.', '/').getBytes(StandardCharsets.US_ASCII), targetPackage.replace('.', '/').getBytes(StandardCharsets.US_ASCII));
        return DexUtil.repair(out);
    }

    private static void verifyBaseOutput(File output, String targetPackage, String appName,
                                         boolean playerLeft, boolean autoResume, boolean modifiedIcon,
                                         boolean clusterBridge) throws Exception {
        try (ZipFile z = new ZipFile(output)) {
            byte[] manifest = IoUtil.readAll(z.getInputStream(z.getEntry("AndroidManifest.xml")));
            List<String> ms = Spotify91ManifestUtil.scanStrings(manifest);
            if (!ms.contains(targetPackage)) throw new IllegalArgumentException("9.1 output package identity verification failed");
            if (ms.contains(BuildConfigData.OFFICIAL_PACKAGE)) throw new IllegalArgumentException("9.1 clone still contains official package identity");
            String expectedService = targetPackage + ".AutoResumeService";
            if (!ms.contains(expectedService)) throw new IllegalArgumentException("9.1 playback/cluster service verification failed");
            if (clusterBridge) {
                Spotify89ManifestUtil.verifyExportedServiceWithoutPermission(manifest, expectedService);
                for (String permission : Spotify91ManifestUtil.BYD_CLUSTER_PERMISSIONS) {
                    if (ManifestElementUtil.countRootStringElement(manifest, "uses-permission", permission) != 1)
                        throw new IllegalArgumentException("9.1 BYD instrument permission verification failed: " + permission);
                }
            }
            String action = (clusterBridge || autoResume) ? "byd.intent.action.RESTORE_PLAYBACK" : "byd.intent.action.RESTORE_DISABLED";
            if (!ms.contains(action)) throw new IllegalArgumentException("9.1 AutoResume trigger verification failed");
            byte[] resources = IoUtil.readAll(z.getInputStream(z.getEntry("resources.arsc")));
            if (!contains(resources, targetPackage.getBytes(StandardCharsets.UTF_16LE))) throw new IllegalArgumentException("9.1 resource package identity verification failed");
            if (!contains(resources, (targetPackage + ".android.mediaapi_").getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("9.1 media authority verification failed");
            if (!containsResourceString(resources, appName)) throw new IllegalArgumentException("9.1 app label verification failed");
            if (z.getEntry(AUTO_RESUME_DEX) == null) throw new IllegalArgumentException("9.1 AutoResume helper DEX missing");
            byte[] resumeDex = IoUtil.readAll(z.getInputStream(z.getEntry(AUTO_RESUME_DEX)));
            if (clusterBridge) {
                byte[] serviceMarker = ("L" + targetPackage.replace('.', '/') + "/AutoResumeService;").getBytes(StandardCharsets.US_ASCII);
                byte[] configMarker = "AIVI_CLUSTER_AUTOR=".getBytes(StandardCharsets.US_ASCII);
                if (!contains(resumeDex, serviceMarker) || !contains(resumeDex, configMarker))
                    throw new IllegalArgumentException("9.1 BYD cluster bridge verification failed");
            } else if (!contains(resumeDex, "AIVI-AutoResume".getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("9.1 robust AutoResume helper verification failed");
            }
            if (playerLeft && z.getEntry(LHS_DEX) == null) throw new IllegalArgumentException("9.1 LHS helper DEX missing");
            if (!playerLeft && z.getEntry(LHS_DEX) != null) throw new IllegalArgumentException("Unexpected 9.1 LHS helper DEX");
            if (modifiedIcon && z.getEntry(IconBrandingUtil.ADAPTIVE_FOREGROUND_PNG) == null) throw new IllegalArgumentException("9.1 branded adaptive launcher icon missing");
            int dexCount = 0;
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement(); String n = e.getName();
                if (!n.matches("classes(\\d+)?\\.dex")) continue;
                dexCount++;
                byte[] d = IoUtil.readAll(z.getInputStream(e));
                if (!Arrays.equals(d, DexUtil.repair(d))) throw new IllegalArgumentException("DEX checksum verification failed: " + n);
            }
            int expected = EXPECTED_BASE_DEX_COUNT + 1 + (playerLeft ? 1 : 0);
            if (dexCount != expected) throw new IllegalArgumentException("Expected " + expected + " output DEX files, found " + dexCount);
        }
    }

    private static boolean containsResourceString(byte[] resources, String value) {
        try {
            int hs = Spotify89BinaryUtil.u16(resources, 2);
            return Spotify89BinaryUtil.parsePool(resources, hs).strings.contains(value);
        } catch (Exception ignored) { return false; }
    }

    private static void verifySplitOutput(File output, String targetPackage, boolean clusterBridge) throws Exception {
        try (ZipFile z = new ZipFile(output)) {
            ZipEntry me = z.getEntry("AndroidManifest.xml");
            if (me == null) throw new IllegalArgumentException("Patched 9.1 split manifest missing");
            List<String> ms = Spotify91ManifestUtil.scanStrings(IoUtil.readAll(z.getInputStream(me)));
            if (!ms.contains(targetPackage) || ms.contains(BuildConfigData.OFFICIAL_PACKAGE))
                throw new IllegalArgumentException("Spotify 9.1 split package identity verification failed: " + output.getName());
        }
    }

    private static List<String> topLevelApks(ZipFile bundle) {
        List<String> names = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> en = bundle.entries(); en.hasMoreElements();) {
            String n = en.nextElement().getName();
            if (n.endsWith(".apk") && !n.contains("/")) names.add(n);
        }
        return names;
    }

    private static String chooseAbi(List<String> names) {
        Set<String> available = new LinkedHashSet<>();
        for (String n : names) {
            if (n.startsWith("split_config.") && n.endsWith(".apk")) {
                String middle = n.substring("split_config.".length(), n.length() - 4);
                if (isKnownAbi(middle)) available.add(middle);
            }
        }
        for (String abi : Build.SUPPORTED_ABIS) {
            String normal = abi.replace('-', '_');
            if (available.contains(normal)) return normal;
        }
        throw new IllegalArgumentException("Spotify 9.1 APKM has no ABI split compatible with this device. Device ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS));
    }

    private static String abiForSplit(String name) {
        for (String abi : new String[]{"arm64_v8a", "armeabi_v7a", "x86_64", "x86"}) {
            if (name.equals("split_config." + abi + ".apk") || name.endsWith(".config." + abi + ".apk")) return abi;
        }
        return null;
    }

    private static boolean isKnownAbi(String s) {
        return "arm64_v8a".equals(s) || "armeabi_v7a".equals(s) || "x86".equals(s) || "x86_64".equals(s);
    }

    private static boolean shouldScaleBinaryXml(String name) {
        return !"res/drawable/encore_icon_shuffle_24.xml".equals(name)
                && !"res/drawable/smart_shuffle_icon.xml".equals(name);
    }

    private static boolean isV1Signature(String name) {
        String u = name.toUpperCase(Locale.ROOT);
        if ("META-INF/MANIFEST.MF".equals(u)) return true;
        return u.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
    }

    private static boolean contains(byte[] h, byte[] n) {
        outer: for (int i = 0; i <= h.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    private static void writeAdded(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        z.putNextEntry(e); z.write(data); z.closeEntry();
    }

    /** Preserves APK alignment, including 4096-byte alignment for uncompressed native libraries. */
    private static void writeEntry(ZipOutputStream z, long currentOffset, ZipEntry template, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(template.getName());
        e.setTime(template.getTime()); e.setComment(template.getComment());
        byte[] extra = template.getExtra();
        if (extra == null) extra = new byte[0]; else extra = extra.clone();
        int method = template.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) method = ZipEntry.DEFLATED;
        e.setMethod(method);
        if (method == ZipEntry.STORED) {
            int alignment = e.getName().startsWith("lib/") && e.getName().endsWith(".so") ? 4096 : 4;
            byte[] name = e.getName().getBytes(StandardCharsets.UTF_8);
            long base = currentOffset + 30L + name.length + extra.length;
            if ((base & (alignment - 1L)) != 0) {
                int padding = (int) ((-base - 4L) & (alignment - 1L));
                byte[] aligned = Arrays.copyOf(extra, extra.length + 4 + padding);
                int p = extra.length;
                aligned[p] = 0x35; aligned[p + 1] = (byte) 0xD9;
                aligned[p + 2] = (byte) padding; aligned[p + 3] = (byte) (padding >>> 8);
                extra = aligned;
            }
            CRC32 crc = new CRC32(); crc.update(data);
            e.setSize(data.length); e.setCompressedSize(data.length); e.setCrc(crc.getValue());
        }
        if (extra.length > 0) e.setExtra(extra);
        z.putNextEntry(e); z.write(data); z.closeEntry();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) deleteTree(k); }
        if (!f.delete()) f.deleteOnExit();
    }
}

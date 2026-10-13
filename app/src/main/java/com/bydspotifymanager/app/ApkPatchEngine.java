package com.bydspotifymanager.app;

import android.content.Context;

import com.github.luben.zstd.Zstd;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Legacy Spotify 9.1.78 standalone-APK engine retained for reference.
 *  The active Spotify 9.1.82 APKM path is ApkPatchEngine91. */
final class ApkPatchEngine {
    interface Progress { void onProgress(String message); }

    private static final String PATCH_ROOT = "patches/v16_primary/";

    static File build(Context context, File stockApk, File outFile, boolean secondary,
                      String appName, float scaleFactor, boolean bydLayout, boolean playerLeft, boolean autoResume,
                      int iconHue, boolean iconBadge, Progress progress) throws Exception {
        File primary = new File(outFile.getParentFile(), "primary_unsigned.apk");
        buildPrimary(context, stockApk, primary, scaleFactor, bydLayout, playerLeft, autoResume,
                iconHue, iconBadge, progress);
        // Always rewrite the visible label, including the default "SpotifyPlus". v16's
        // embedded base label is SpotifyPlus9, so skipping the default label left the wrong name.
        rewriteIdentityAndLabel(primary, outFile, secondary, appName, progress);
        if (!primary.delete()) primary.deleteOnExit();
        // The forced email-OTP compatibility patch changes classes6.dex for both slots,
        // and Secondary additionally changes package strings in several DEX files. Rebind
        // the embedded ART profile to the final APK CRCs in every case.
        ProfileMetadataUtil.updateEmbeddedBaselineProfile(outFile);
        progress.onProgress("Embedded performance profile matched to final DEX CRCs.");
        progress.onProgress("Email one-time-code login compatibility enabled.");
        return outFile;
    }

    private static void buildPrimary(Context context, File stock, File output, float scaleFactor,
                                     boolean bydLayout, boolean playerLeft, boolean autoResume, int iconHue,
                                     boolean iconBadge, Progress progress) throws Exception {
        Map<String, PatchSpec> specs = loadSpecs(context);
        Set<String> seen = new HashSet<>();
        try (ZipFile zin = new ZipFile(stock);
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            boolean modifyIcon = iconHue != 0 || iconBadge;
            byte[] adaptiveForegroundPng = null;
            if (modifyIcon) {
                ZipEntry xxx = zin.getEntry(IconBrandingUtil.DENSITY_ICONS[IconBrandingUtil.DENSITY_ICONS.length - 1]);
                if (xxx == null) throw new IOException("Spotify xxxhdpi launcher icon is missing");
                adaptiveForegroundPng = IconBrandingUtil.buildAdaptiveForegroundPng(
                        IoUtil.readAll(zin.getInputStream(xxx)), iconHue, iconBadge);
            }
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry srcEntry = en.nextElement();
                String name = srcEntry.getName();
                if ("stamp-cert-sha256".equals(name)) continue;
                if (modifyIcon && IconBrandingUtil.ADAPTIVE_FOREGROUND_XML.equals(name)) continue;
                PatchSpec spec = specs.get(name);
                byte[] source = null;
                byte[] target;
                if (spec != null) {
                    seen.add(name);
                    source = IoUtil.readAll(zin.getInputStream(srcEntry));
                    // v0.1.12 uses the original Spotify resources AND original Spotify DEX as
                    // the visual/runtime baseline. v16 inherited old experimental UI DEX edits
                    // (including eleven F0():float methods forced to 1.5), so reconstructing those
                    // DEX patches would make the 100% preset permanently enlarged. Functional DEX
                    // edits are reapplied explicitly below from the exact stock APK.
                    if ("resources.arsc".equals(name) || isSpotifyDex(name)) {
                        target = source;
                    } else if ("patch_chunks".equals(spec.op)) {
                        ByteArrayOutputStream merged = new ByteArrayOutputStream((int)spec.targetSize);
                        for (PatchChunk c : spec.chunks) {
                            byte[] dict = Arrays.copyOfRange(source, c.srcStart, c.srcEnd);
                            byte[] patch = IoUtil.readAll(context.getAssets().open(PATCH_ROOT + c.asset));
                            byte[] chunkOut = new byte[(int)c.targetSize];
                            long result = Zstd.decompressUsingDict(chunkOut, 0, patch, 0, patch.length, dict);
                            if (Zstd.isError(result) || result != chunkOut.length) {
                                throw new IOException("Chunk patch failed for " + name + ": " + Zstd.getErrorName(result));
                            }
                            merged.write(chunkOut);
                        }
                        target = merged.toByteArray();
                        if (target.length != spec.targetSize) throw new IOException("Chunk size mismatch for " + name);
                    } else {
                        byte[] patch = IoUtil.readAll(context.getAssets().open(PATCH_ROOT + spec.asset));
                        target = new byte[(int)spec.targetSize];
                        long result = Zstd.decompressUsingDict(target, 0, patch, 0, patch.length, source);
                        if (Zstd.isError(result) || result != target.length) {
                            throw new IOException("Patch decompression failed for " + name + ": " + Zstd.getErrorName(result));
                        }
                    }
                } else {
                    target = IoUtil.readAll(zin.getInputStream(srcEntry));
                }
                // v16 was built from an earlier enlarged BYD UI profile. For the Manager,
                // visual sizing must always start from the exact original Spotify baseline.
                // Resource XML/table and Spotify DEX are restored to stock first; only the
                // explicitly understood clone/login/runtime-scale edits are then reapplied.
                if (name.startsWith("res/")) {
                    if (source == null) source = target;
                    target = source;
                    if (name.endsWith(".xml") && scaleFactor != 1f && shouldScaleBinaryXml(name)) {
                        ResourceScaler.scaleBinaryXml(target, scaleFactor);
                    }
                    if (playerLeft && PlayerPositionUtil.LARGE_MAIN_PATH.equals(name)) {
                        target = PlayerPositionUtil.moveLargePlayerLeft(target);
                        progress.onProgress("LHS runtime mirror hook installed; Spotify constraints/state machine remain stock.");
                    }
                } else if ("resources.arsc".equals(name)) {
                    // IMPORTANT: never use v16's rebuilt/enlarged resource table as a visual base.
                    // Its string-pool layout is ~1.47 MB smaller than stock and it contains the old
                    // enlarged UI profile. Trying to "restore" stock values into that table by a
                    // dimension map left style/font changes behind, which is why the 100% preset
                    // still looked enlarged. Start from the exact stock resources.arsc instead.
                    if (source == null) throw new IOException("Stock resources.arsc source missing");
                    target = source;
                    target = applyPrimaryResourceIdentity(target);
                    if (modifyIcon) target = IconBrandingUtil.retargetAdaptiveForegroundResource(target);
                    if (scaleFactor != 1f) {
                        ResourceScaler.scaleResourceTable(target, scaleFactor);
                    }
                    if (bydLayout) {
                        // This map is generated against the STOCK resources.arsc offsets.
                        DimensionPresetUtil.apply(context, target, "profiles/byd_layout_dimension_map.bin");
                    }
                }

                if (isSpotifyDex(name)) {
                    // Recreate only the proven equal-length clone package string edit from the
                    // original DEX. This exactly reproduces the identity edit used by the working
                    // profile without carrying any of its old visual experiments.
                    target = applyPrimaryDexIdentity(target);

                    // Modern Spotify/Compose typography does not come only from resources.
                    // v0.1.12 keeps the Android-backed Compose Density factory patch from v0.1.11 in
                    // classes2.dex so its stored fontScale + FontScaleConverter are created from
                    // the selected preset. The classes.dex getter patch is retained as a
                    // consistency backstop for other Density implementations.
                    if (scaleFactor != 1f) {
                        if ("classes.dex".equals(name)) {
                            target = ModernUiScaleUtil.apply(target, scaleFactor);
                        } else if ("classes2.dex".equals(name)) {
                            target = ComposeFontScaleUtil.apply(target, scaleFactor);
                        }
                    }

                    // Spotify 9.1 clone compatibility: force the email one-time-code login path.
                    // This is intentionally always on for the supported 9.1.78.2215 profile.
                    if ("classes6.dex".equals(name)) {
                        target = LoginCompatibilityUtil.forceEmailOtpLogin(target);
                    }
                }

                if ("AndroidManifest.xml".equals(name) && !autoResume) {
                    target = disableAutoResumeTrigger(target);
                }
                if (iconHue != 0 || iconBadge) {
                    if (IconBrandingUtil.isDensityIcon(name)) {
                        target = IconBrandingUtil.brandDensityIcon(target, iconHue, iconBadge);
                    }
                }
                writeEntry(zout, srcEntry, target);
            }
            if (modifyIcon && adaptiveForegroundPng != null) {
                ZipEntry adaptive = new ZipEntry(IconBrandingUtil.ADAPTIVE_FOREGROUND_PNG);
                adaptive.setMethod(ZipEntry.DEFLATED);
                writeEntry(zout, adaptive, adaptiveForegroundPng);
            }
            for (PatchSpec spec : specs.values()) {
                if (!"add".equals(spec.op) || seen.contains(spec.name)) continue;
                byte[] target = IoUtil.readAll(context.getAssets().open(PATCH_ROOT + spec.asset));
                ZipEntry e = new ZipEntry(spec.name);
                e.setMethod(spec.method);
                writeEntry(zout, e, target);
            }
            if (playerLeft) {
                if (zin.getEntry(PlayerPositionUtil.HOOK_DEX_ENTRY) != null) {
                    throw new IOException("Spotify input already contains reserved LHS helper DEX slot "
                            + PlayerPositionUtil.HOOK_DEX_ENTRY);
                }
                byte[] hookDex = IoUtil.readAll(context.getAssets().open(PlayerPositionUtil.HOOK_ASSET));
                if (hookDex.length < 256 || hookDex[0] != 'd' || hookDex[1] != 'e' || hookDex[2] != 'x') {
                    throw new IOException("Generated LHS runtime hook DEX is missing or invalid");
                }
                ZipEntry hookEntry = new ZipEntry(PlayerPositionUtil.HOOK_DEX_ENTRY);
                hookEntry.setMethod(ZipEntry.DEFLATED);
                writeEntry(zout, hookEntry, hookDex);
                progress.onProgress("LHS runtime mirror helper injected as " + PlayerPositionUtil.HOOK_DEX_ENTRY + ".");
            }
        }
        String layoutText = bydLayout ? "wide-screen layout fixes" : "stock Spotify layout";
        progress.onProgress(layoutText + " + " + Math.round(scaleFactor * 100f)
                + "% interface scale applied from the full stock baseline.");
        if (iconHue != 0 || iconBadge) progress.onProgress("Launcher icon: hue " + IconBrandingUtil.normaliseHue(iconHue) + "°" + (iconBadge ? " + badge" : "") + ".");
        progress.onProgress(playerLeft ? "Player position: LHS runtime mirror (stock Spotify state machine)." : "Player position: RHS stock layout.");
        progress.onProgress(autoResume ? "Vehicle restart playback resume enabled." : "Vehicle restart playback resume disabled.");
    }


    /**
     * Queue session-modifier Shuffle/Smart Shuffle load these two vector resources
     * directly via Compose painterResource(). Scaling their intrinsic 24dp vector
     * width/height made only the Shuffle action icon grow to ~34dp at 140% and
     * ~38dp at 160%, while Repeat/Timer stayed at the Encore action size. Keep
     * those intrinsic vectors at stock 24dp; the surrounding button/text still
     * follows the selected interface scale.
     */
    private static boolean shouldScaleBinaryXml(String name) {
        return !"res/drawable/encore_icon_shuffle_24.xml".equals(name)
                && !"res/drawable/smart_shuffle_icon.xml".equals(name);
    }

    private static boolean isSpotifyDex(String name) {
        return name != null && name.matches("classes(\\d+)?\\.dex");
    }

    private static byte[] applyPrimaryDexIdentity(byte[] dex) throws Exception {
        // DEX string_data_item for the exact package/process string. Both package names are
        // 17 ASCII characters, so the one-byte ULEB128 UTF-16 length remains unchanged.
        String from = BuildConfigData.OFFICIAL_PACKAGE;
        String to = BuildConfigData.PRIMARY_PACKAGE;
        if (from.length() != to.length() || from.length() >= 0x80) {
            throw new IllegalStateException("Clone DEX package replacement must preserve short string length");
        }
        byte[] oldItem = new byte[from.length() + 2];
        byte[] newItem = new byte[to.length() + 2];
        oldItem[0] = (byte) from.length();
        newItem[0] = (byte) to.length();
        System.arraycopy(from.getBytes(StandardCharsets.US_ASCII), 0, oldItem, 1, from.length());
        System.arraycopy(to.getBytes(StandardCharsets.US_ASCII), 0, newItem, 1, to.length());
        // Last byte is the MUTF-8 NUL terminator.
        byte[] changed = IoUtil.replaceSameLength(dex, oldItem, newItem);
        return Arrays.equals(changed, dex) ? dex : DexUtil.repair(changed);
    }

    private static byte[] applyPrimaryResourceIdentity(byte[] resources) {
        byte[] out = resources;
        // Resource-table package name. music -> musia is deliberately equal length.
        out = IoUtil.replaceSameLength(out,
                BuildConfigData.OFFICIAL_PACKAGE.getBytes(StandardCharsets.UTF_16LE),
                BuildConfigData.PRIMARY_PACKAGE.getBytes(StandardCharsets.UTF_16LE));
        out = IoUtil.replaceSameLength(out,
                BuildConfigData.OFFICIAL_PACKAGE.getBytes(StandardCharsets.UTF_8),
                BuildConfigData.PRIMARY_PACKAGE.getBytes(StandardCharsets.UTF_8));

        // Stock Spotify exposes this fixed Media API provider authority. A cloned APK cannot
        // coexist with OEM Spotify unless it is unique. The replacement is exactly 35 bytes,
        // so the stock resource-table structure and all visual resource offsets stay intact.
        String authorityFrom = "com.spotify.mobile.android.mediaapi";
        String authorityTo = "com.spotify.musia.android.mediaapi_";
        out = IoUtil.replaceSameLength(out,
                authorityFrom.getBytes(StandardCharsets.UTF_8),
                authorityTo.getBytes(StandardCharsets.UTF_8));
        out = IoUtil.replaceSameLength(out,
                authorityFrom.getBytes(StandardCharsets.UTF_16LE),
                authorityTo.getBytes(StandardCharsets.UTF_16LE));
        return out;
    }

    private static byte[] disableAutoResumeTrigger(byte[] manifest) {
        String from = "byd.intent.action.RESTORE_PLAYBACK";
        String to = "byd.intent.action.RESTORE_DISABLED";
        byte[] out = IoUtil.replaceSameLength(manifest,
                from.getBytes(StandardCharsets.UTF_8), to.getBytes(StandardCharsets.UTF_8));
        return IoUtil.replaceSameLength(out,
                from.getBytes(StandardCharsets.UTF_16LE), to.getBytes(StandardCharsets.UTF_16LE));
    }

    private static void rewriteIdentityAndLabel(File input, File output, boolean secondary,
                                                String requestedName, Progress progress) throws Exception {
        byte[] dotFrom = BuildConfigData.PRIMARY_PACKAGE.getBytes(StandardCharsets.UTF_8);
        byte[] dotTo = BuildConfigData.SECONDARY_PACKAGE.getBytes(StandardCharsets.UTF_8);
        byte[] slashFrom = "com/spotify/musia/".getBytes(StandardCharsets.UTF_8);
        byte[] slashTo = "com/spotify/musib/".getBytes(StandardCharsets.UTF_8);
        byte[] utf16From = BuildConfigData.PRIMARY_PACKAGE.getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16To = BuildConfigData.SECONDARY_PACKAGE.getBytes(StandardCharsets.UTF_16LE);
        String clean = requestedName == null ? "SpotifyPlus" : requestedName.trim();
        if (clean.isEmpty()) clean = "SpotifyPlus";
        int codePoints = clean.codePointCount(0, clean.length());
        if (codePoints > 32) throw new IllegalArgumentException("App name can be up to 32 characters.");
        for (int i = 0; i < clean.length(); ) {
            int cp = clean.codePointAt(i);
            if (Character.isISOControl(cp)) throw new IllegalArgumentException("App name cannot contain control characters.");
            i += Character.charCount(cp);
        }

        try (ZipFile zin = new ZipFile(input);
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry src = en.nextElement();
                byte[] data = IoUtil.readAll(zin.getInputStream(src));
                String n = src.getName();
                if (secondary) {
                    if ("AndroidManifest.xml".equals(n)) {
                        data = IoUtil.replaceSameLength(data, utf16From, utf16To);
                        data = IoUtil.replaceSameLength(data, dotFrom, dotTo);
                    } else if ("resources.arsc".equals(n)) {
                        data = IoUtil.replaceSameLength(data, dotFrom, dotTo);
                        data = IoUtil.replaceSameLength(data, utf16From, utf16To);
                    } else if (n.matches("classes(\\d+)?\\.dex")) {
                        // Scan every DEX rather than relying on a version-specific shortlist.
                        byte[] changed = IoUtil.replaceSameLength(data, dotFrom, dotTo);
                        changed = IoUtil.replaceSameLength(changed, slashFrom, slashTo);
                        if (!Arrays.equals(changed, data)) data = DexUtil.repair(changed);
                    }
                }
                if ("resources.arsc".equals(n)) {
                    // The visual/resource base is now the exact stock table, where the single
                    // global app-name string is "Spotify". Replacing that one string also updates
                    // app_name, its content description and widget app name consistently.
                    data = BinaryStringPoolUtil.replaceFirstString(data, "Spotify", clean);
                }
                writeEntry(zout, src, data);
            }
        }
        progress.onProgress(secondary ? "Secondary package identity applied." : "App label applied.");
    }

    private static Map<String, PatchSpec> loadSpecs(Context context) throws Exception {
        String json;
        try (InputStream in = context.getAssets().open(PATCH_ROOT + "manifest.json")) {
            json = new String(IoUtil.readAll(in), StandardCharsets.UTF_8);
        }
        JSONArray arr = new JSONArray(json);
        Map<String, PatchSpec> out = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            PatchSpec s = new PatchSpec();
            s.name = o.getString("name");
            s.op = o.getString("op");
            s.asset = o.optString("asset", null);
            s.targetSize = o.getLong("targetSize");
            s.method = o.getInt("method");
            if ("patch_chunks".equals(s.op)) {
                JSONArray chunks = o.getJSONArray("chunks");
                for (int j = 0; j < chunks.length(); j++) {
                    JSONObject co = chunks.getJSONObject(j);
                    PatchChunk c = new PatchChunk();
                    c.srcStart = co.getInt("srcStart");
                    c.srcEnd = co.getInt("srcEnd");
                    c.targetSize = co.getLong("targetSize");
                    c.asset = co.getString("asset");
                    s.chunks.add(c);
                }
            }
            out.put(s.name, s);
        }
        return out;
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
            CRC32 crc = new CRC32(); crc.update(data);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        }
        zout.putNextEntry(e);
        zout.write(data);
        zout.closeEntry();
    }

    private static final class PatchSpec {
        String name, op, asset;
        long targetSize;
        int method;
        final List<PatchChunk> chunks = new ArrayList<>();
    }

    private static final class PatchChunk {
        int srcStart, srcEnd;
        long targetSize;
        String asset;
    }
}

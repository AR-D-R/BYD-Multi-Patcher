package com.bydspotifymanager.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Waze wide-screen patcher with a tested stable profile, known binary profiles, signature relocation, and explicit force fallback for untested versions. */
final class WazePatchEngine {
    interface Progress { void onProgress(String message); }

    static final class Validation {
        final boolean ok;
        final boolean tested;
        final String detail;
        final String versionName;
        final long versionCode;
        final int apkCount;
        final String baseSha256;
        final String patchProfileVersion;
        final boolean exactPatchProfile;

        Validation(boolean ok, boolean tested, String detail, String versionName,
                   long versionCode, int apkCount, String baseSha256,
                   String patchProfileVersion, boolean exactPatchProfile) {
            this.ok = ok;
            this.tested = tested;
            this.detail = detail;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkCount = apkCount;
            this.baseSha256 = baseSha256;
            this.patchProfileVersion = patchProfileVersion;
            this.exactPatchProfile = exactPatchProfile;
        }

        static Validation fail(String detail) {
            return new Validation(false, false, detail, null, -1L, 0, null, null, false);
        }

        String displayVersion() {
            return versionName == null || versionName.trim().isEmpty() ? "unknown" : versionName;
        }
    }

    private static final Set<String> REQUIRED_SPLITS = new LinkedHashSet<>(Arrays.asList(
            "base.apk",
            "split_config.arm64_v8a.apk",
            "split_config.xxhdpi.apk",
            "split_config.xxxhdpi.apk"
    ));

    private static final PatchRegion[] STABLE_CLASSES5_PATCHES = new PatchRegion[] {
            // Main map geometry: adaptive vertical car inset (height / 5) and full-width horizontal centring.
            new PatchRegion(0x473510,
                    "dd09104038090400528f41ad01f72200861b76083ad600001100000001000100000000003e03490003000000520040ad0f00000001000100000000003e03490003000000520041ad0f00000001000100000000003e0349000300000052003bad0f00000001000100000000003e0349000300000052003ead",
                    "52873baddb0707050000000000002200861b76083ad600001100000001000100000000003e03490003000000520040ad0f00000001000100000000003e03490003000000520041ad0f00000001000100000000003e0349000300000052003bad0f00000001000100000000003e0349000300000013000000")
    };

    private static final PatchRegion[] STABLE_CLASSES6_PATCHES = new PatchRegion[] {
            // Audio SDK / music button startup state: default “Show on map” to OFF at process launch.
            new PatchRegion(0x358e80,
                    "70100ef502006200d20b6e10c42c00000c001a01fa6371201bef10005b201b310e00",
                    "70100ef502006200d20b62018d476e200c2c10006e10c42c00000c005b201b310e00"),
            // Speedometer: Waze's landscape Row gives the left panel weight 0.44921875 and the map
            // section weight 1.0. The speedometer lives inside that map section, so centring the entire
            // map parent lands too far right. Fill only (1 - panelWeight) = 0.55078125 of the map parent
            // and then wrap to CenterHorizontally: the resulting centre is exactly the midpoint of the
            // complete two-section Row. The weighted runtime layout remains resolution/DPI independent; a small fixed -12dp trim aligns the speedometer with the observed vehicle marker.
            // Five ffff reference placeholders are resolved against the selected classes6.dex at patch time.
            new PatchRegion(0x3672e4,
                    "a90300000a023802080012f21a03fd567140ab030132721009230c000c0212061237120312047706c00302000c0162024f45713011ec2b030c02150390c1150440c171302d0332040c037210a00305000c02620448006e108f0304000c0433420c002202cf161304120070208dc342007220a70325001f02d8217220930315000a04d500800333900300280201a8960004087210a00305000c0439000a00620048006e108f0300000c0033040a002204fa17704038c814ed7220a70345001f04d821126612077706890402007100a90300000a0e380e09007100aa03",
                    "a9030000721009230c000c0212061237120312047706c00302000c0162024f45713011ec2b030c0215030d3f7140ffff32660c026204ffff6e10ffff04000c041228715affff42860c02150340c11504c0c17130ffff32040c032202cf161304120070208dc342001f02d82112180000000000000000000000007220930315000a04d500800333900300280201a8960004087210a00305000c0439000a00620048006e108f0300000c0033040a002204fa17704038c814ed7220a70345001f04d8211266120777068904020000000000000000000000000000000000"),
            // Landscape side-panel width ratio.
            new PatchRegion(0x37ac1c,
                    "803f",
                    "e63e",
                    "0200131a00001517803f1318000077063d03", 8),
            // Keep Waze’s existing 16dp minimum landscape side clearance when Android reports no side inset.
            new PatchRegion(0x37d6c0,
                    "3d030400",
                    "00000000",
                    "83000a03150a80413d03040001a3280201837120", 8),
            // Keep Waze’s existing 16dp minimum landscape side clearance when Android reports no side inset.
            new PatchRegion(0x37d6d2,
                    "3d070300",
                    "00000000",
                    "71207a0487000a073d0703002802018a62073e00", 8),
            // Map geometry copy path used by the wide-screen layout.
            new PatchRegion(0x3811f0,
                    "8229150a003fc8a9150a804172207504ab000a052200c11c711021ef09000a041207",
                    "db04010500000000150a804172207504ab000a052200c11c01470000000000000000"),
            // Keep the same 16dp minimum side clearance in pre-drive / route-preview landscape.
            new PatchRegion(0x393878,
                    "3d020500",
                    "00000000",
                    "71207a04a5000a023d02050015058041280201a5", 8),
            // Pre-drive side-panel width ratio.
            new PatchRegion(0x3938a2,
                    "803f",
                    "e63e",
                    "0c1513180200131900001516803f1317000077063d0314000c02", 12),
            // Keep the same 16dp minimum side clearance in pre-drive / route-preview landscape.
            new PatchRegion(0x39390c,
                    "3d010500",
                    "00000000",
                    "71207a04ac000a013d01050015058041280201a5", 8),
            // Pre-drive / overview vehicle anchor: height / 4 -> height / 3.
            new PatchRegion(0x396570,
                    "722091035a000a06b661",
                    "db0509031216b6610000"),
            // Vehicle-type pill: replace the test-tag modifier append with 20dp Compose padding.
            new PatchRegion(0x3a8156,
                    "620157451203713011ec19030c03",
                    "1501a04100007120350319000c03"),
            // Active-navigation final viewport placement: use 0.56f.
            new PatchRegion(0x3bc4fa,
                    "1205713029ef56010a05",
                    "1405295c0f3f00000000"),
            // Landscape NavigationInstructionsBarColumn: shift the actual manoeuvre/instruction
            // column down by 16dp before it is passed into NavigationInstructionsBarColumn.
            // This targets the visible black navigation card rather than unrelated safe-content
            // padding used by the ETA drawer. The surrounding register shuffle is compressed so
            // the DEX code item remains exactly the same size.
            new PatchRegion(0x37d2e6,
                    "0754076512060178120701891208021009001209081e040002011000072408022200",
                    "081e05000765017112061508804171302d0365080c05120712081209072408022200"),
            // Landscape top-right ButtonPack (music/audio + speaker and any other pack buttons):
            // its real MidDrive caller anchors the pack at TopEnd and passes that modifier straight
            // through ButtonPackWrapper. Add a +16dp Y offset immediately after TopEnd alignment.
            // The old dirty-flag shuffle is safely replaced with changed=0, which makes the child
            // composable recompute its parameter-change bits. The ffff method ref is resolved to
            // OffsetKt.offset-VpY3zN4 at patch time.
            new PatchRegion(0x37dbe4,
                    "e1020006e103000ce1000009dd02027096021302d5338003b6329500001296100200",
                    "1202150480417130ffff2d040c0d1310000000000000000000000000000000000000"),
            // Optional compass is not part of ButtonPack. MidDriveOverMapComponents creates it via
            // CompassV1 with Modifier.align(TopStart).padding(start=16dp). Make that existing
            // four-edge padding explicit for both start and top, preserving the 16dp start inset
            // and adding the same +16dp top clearance as the ButtonPack.
            new PatchRegion(0x37f7cc,
                    "13180e001319000015148041131500001316000013170000",
                    "13180c001319000015148041151580411316000013170000"),
            // Landscape menu/notification Box: retain full width and offset +16dp. The enclosing
            // Column supplies zero minimum height, so this Box's wrapContentHeight(default) is
            // redundant. Preserve v10=0 for its measure policy and restore v3=3 because a later
            // wrapContentHeight(default) call in the same method reuses that default-mask register.
            new PatchRegion(0x395f64,
                    "62004b001f00950171404203b05f0c001233120a71554c03503a0c00",
                    "62004b0071404203b05f0c00150380417130ffffb0030c00120a1233"),
            // Landscape pre-drive ButtonPack: keep all callback/state arguments; use changed=0 and append offset(0dp,16dp) to the existing aligned modifier. v13 was zero on both incoming paths.
            new PatchRegion(0x3964e6,
                    "15150700951504151f08d7210213150002150d00020d13000213040007b402160c00080b1c0007ac078a07181211",
                    "1f08d7210213040002150d0007b402160c00080b1c0007ac078a0718120d150180417130ffffd8010c0812110000"),
            // Pre-drive compass: keep 16dp start padding; add 16dp top only when v29 says landscape. TopStart is already the enclosing Box default. Portrait keeps zero top padding.
            new PatchRegion(0x3978d6,
                    "62084b001f089501620b4a006e10db030b000c0b72302403810b0c0e13130e0013140000150f8041131000001311000013120000770739030e00",
                    "620e4b0013130c0013140000150f804113100000381d040015108041131100001312000000000000000000000000000000000000770739030e00")
    };

    private static final PatchRegion[] STABLE_CLASSES7_PATCHES = new PatchRegion[] {
            // Start-state drawer collapsed peek height: 80dp -> 60dp.
            new PatchRegion(0x419ec8,
                    "380005001500dc420f001500a0420f00",
                    "380005001500dc420f00150070420f00")
    };

    /**
     * Optional defaults for the exact tested stable DEX. Waze stores connector selections as
     * pipe-delimited internal ids; CCS2 is CCS_TYPE2. The two string-data swaps keep the DEX
     * string table sorted and keep every item the same size, which preserves all following data
     * offsets. The displaced four-character literal is only part of a diagnostic log message.
     */
    private static final PatchRegion[] STABLE_EV_CLASSES6_PATCHES = new PatchRegion[] {
            // Redirect string id 265 from " action: " to the old four-byte CC21 slot.
            new PatchRegion(0x494,
                    "d69c4200",
                    "5a4e4400"),
            // Redirect the old CC21 string id to the repurposed nine-byte string-data item.
            new PatchRegion(0x4998,
                    "5a4e4400",
                    "d69c4200"),
            // Reuse the nine-byte log literal as CCS_TYPE2 without changing string-data size.
            new PatchRegion(0x429cd6,
                    "0920616374696f6e3a2000",
                    "094343535f545950453200"),
            // Keep string id 265 sorted by replacing the displaced CC21 bytes with " act".
            new PatchRegion(0x444e5a,
                    "044343323100",
                    "042061637400"),
            // Default selected EV connectors from empty to CCS_TYPE2 (string id 0x124a).
            new PatchRegion(0x156394,
                    "1a000000",
                    "1a004a12"),
            // Default CONFIG_VALUE_EV_HAS_EV_CAR from Boolean.FALSE to Boolean.TRUE.
            new PatchRegion(0x14d104,
                    "62008d47",
                    "62008e47"),
            // On EV repository startup, persist the enabled flag and CCS_TYPE2 selection before
            // constructing the UI state. This also upgrades an existing patched installation whose
            // saved Waze settings previously contained EV=false or another connector selection.
            new PatchRegion(0x1d1ba8,
                    "54701c176e10883500000c01721046f601000c011f0173226e10d1f401000a016e10893500000c02721046f602000c021f028e22",
                    "54701c1712116e208b35100012110000000000000000000000000000000000001a024a126e208c3520001a024a12000000000000")
    };


private static final String BETA_PROFILE_VERSION = "5.24.4.900";
private static final long BETA_PROFILE_VERSION_CODE = 1030731L;
private static final String BETA_PROFILE_SHA256 =
        "fa6eda7b54c58e3200ccbd55d95b7782c442c07918b4969651521b9a1fd3b4fb";

private static final PatchRegion[] BETA_CLASSES5_PATCHES = new PatchRegion[] {
            // Main map geometry: adaptive vertical car inset (height / 5) and full-width horizontal centring.
            new PatchRegion(0x4dee5c,
                    "dd09104038090400528f24ce01f72200051c76083ed80000110000000100010000000000d6a74f0003000000520023ce0f0000000100010000000000d6a74f0003000000520024ce0f0000000100010000000000d6a74f000300000052001ece0f0000000100010000000000d6a74f0003000000520021ce",
                    "52871ecedb0707050000000000002200051c76083ed80000110000000100010000000000d6a74f0003000000520023ce0f0000000100010000000000d6a74f0003000000520024ce0f0000000100010000000000d6a74f000300000052001ece0f0000000100010000000000d6a74f000300000013000000")
    };

private static final PatchRegion[] BETA_CLASSES6_PATCHES = new PatchRegion[] {
            // Audio SDK / music button startup state: default “Show on map” to OFF at process launch.
            new PatchRegion(0x36f058,
                    "7010d4f702006200630c6e100c2e00000c001a01ef657120d3f110005b2003320e00",
                    "7010d4f702006200630c6201e8486e20562d10006e100c2e00000c005b2003320e00"),
            // Speedometer: use the stable runtime-width calculation, but match Waze's real
            // MidDrive map viewport instead of an observed screenshot position. Waze keeps a
            // 16dp right map margin and places the vehicle at 50% of that viewport, so its exact
            // horizontal target is 8dp left of the reconstructed geometric centre. Y is -24dp.
            // References are resolved from this DEX at patch time.
            new PatchRegion(0x37d4bc,
                    "f70300000a023802080012f21a03c3587140f903013272103f240c000c02120612371203120477060e0402000c0162027e4671309cee2b030c02150390c1150440c17130740332040c037210ee0305000c02620452006e10dd0304000c0433420c0022024e171304120070205fc542007220f50325001f0299227220e10315000a04d500800333900300280201a8960004087210ee0305000c0439000a00620052006e10dd0300000c0033040a0022047a1870401dca14ed7220f50345001f049922126612077706e30402007100f70300000a0e380e09007100f803",
                    "f703000072103f240c000c02120612371203120477060e0402000c0162027e4671309cee2b030c0215030d3f7140ffff32660c026204ffff6e10ffff04000c041228715affff42860c02150300c11504c0c17130ffff32040c0322024e171304120070205fc542001f02992212180000000000000000000000007220e10315000a04d500800333900300280201a8960004087210ee0305000c0439000a00620052006e10dd0300000c0033040a0022047a1870401dca14ed7220f50345001f049922126612077706e304020000000000000000000000000000000000"),
            // Landscape side-panel width ratio.
            new PatchRegion(0x390df4,
                    "803f",
                    "e63e"),
            // Keep Waze’s existing 16dp minimum landscape side clearance when Android reports no side inset.
            new PatchRegion(0x393898,
                    "3d030400",
                    "00000000"),
            new PatchRegion(0x3938aa,
                    "3d070300",
                    "00000000"),
            // Map geometry copy path used by the wide-screen layout.
            new PatchRegion(0x3973c8,
                    "8229150a003fc8a9150a80417220ce04ab000a052200421d7110d9f109000a041207",
                    "db04010500000000150a80417220ce04ab000a052200421d01470000000000000000"),
            // Keep the same 16dp minimum side clearance in pre-drive / route-preview landscape.
            new PatchRegion(0x3a9fb0,
                    "3d020500",
                    "00000000"),
            // Pre-drive side-panel width ratio.
            new PatchRegion(0x3a9fda,
                    "803f",
                    "e63e"),
            // Keep the same 16dp minimum side clearance in pre-drive / route-preview landscape.
            new PatchRegion(0x3aa044,
                    "3d010500",
                    "00000000"),
            // Pre-drive / overview vehicle anchor: height / 4 -> height / 3.
            new PatchRegion(0x3acca8,
                    "7220df035a000a06b661",
                    "db0509031216b6610000"),
            // PreDrive-only horizontal correction derived from Waze's own layout geometry.
            // This caller already applies BoxScope.align(TopStart) to a fresh Modifier; TopStart is
            // the Box default, so replace that redundant 28-byte sequence with offset(-8dp, 0dp).
            // Combined with the shared MidDrive-derived -8dp, PreDrive lands at -16dp: half of the
            // 16dp Row gap plus half of Waze's 16dp right viewport margin, with no screen/DPI math.
            // The ffff method reference is resolved to OffsetKt.offset-VpY3zN4 at patch time.
            new PatchRegion(0x3adf68,
                    "620955001f09b001620b54006e1029040b000c0b72306803930b0c03",
                    "620955001f09b001140b000000c112037130ffffb9030c0300000000"),
            // Vehicle-type pill: replace the test-tag modifier append with 20dp Compose padding.
            new PatchRegion(0x3be88e,
                    "62018646120371309cee19030c03",
                    "1501a041000071207d0319000c03"),
            // Active-navigation final viewport placement: use 0.56f.
            new PatchRegion(0x3d2c32,
                    "12057130e2f156010a05",
                    "1405295c0f3f00000000")
    };

private static final PatchRegion[] BETA_CLASSES7_PATCHES = new PatchRegion[] {
            // Start-state drawer collapsed peek height: 80dp -> 60dp.
            new PatchRegion(0x3f5304,
                    "380005001500dc420f001500a0420f00",
                    "380005001500dc420f00150070420f00")
    };

private static final PatchProfile STABLE_PROFILE = new PatchProfile(
        BuildConfigData.WAZE_SUPPORTED_VERSION,
        STABLE_CLASSES5_PATCHES, STABLE_CLASSES6_PATCHES, STABLE_CLASSES7_PATCHES);

private static final PatchProfile BETA_PROFILE = new PatchProfile(
        BETA_PROFILE_VERSION,
        BETA_CLASSES5_PATCHES, BETA_CLASSES6_PATCHES, BETA_CLASSES7_PATCHES);


    private WazePatchEngine() {}

    static Validation validateSource(File apkm) {
        if (apkm == null || !apkm.isFile()) return Validation.fail("Waze APKM file not found.");
        try (ZipFile zip = new ZipFile(apkm)) {
            ZipEntry base = zip.getEntry("base.apk");
            if (base == null) return Validation.fail("This is not a Waze APKM bundle: base.apk is missing.");

            List<String> apkNames = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (isApkEntry(name)) apkNames.add(name);
            }
            for (String required : REQUIRED_SPLITS) {
                if (!apkNames.contains(required)) {
                    return Validation.fail("Waze bundle variant is not compatible with this profile: missing " + required
                            + ". Use the Android 10+ · arm64-v8a · 480-640dpi APKM variant.");
                }
            }

            ManifestInfo manifest = readBaseManifest(zip, base);
            if (!BuildConfigData.WAZE_PACKAGE.equals(manifest.packageName)) {
                return Validation.fail("Selected APKM is not Waze. Package: "
                        + (manifest.packageName == null ? "unknown" : manifest.packageName));
            }
            if (manifest.versionName == null || manifest.versionName.trim().isEmpty() || manifest.versionCode < 0) {
                return Validation.fail("Could not read the Waze version from base.apk.");
            }

            String baseHash;
            try (InputStream in = zip.getInputStream(base)) { baseHash = sha256(in); }

            boolean tested = BuildConfigData.WAZE_SUPPORTED_VERSION.equals(manifest.versionName)
                    && BuildConfigData.WAZE_SUPPORTED_VERSION_CODE == manifest.versionCode
                    && BuildConfigData.WAZE_BASE_SHA256.equalsIgnoreCase(baseHash);

            PatchProfile profile = chooseProfile(manifest.versionName);
            boolean exactKnownProfile = tested
                    || (BETA_PROFILE_VERSION.equals(manifest.versionName)
                    && BETA_PROFILE_VERSION_CODE == manifest.versionCode
                    && BETA_PROFILE_SHA256.equalsIgnoreCase(baseHash));

            String detail;
            if (tested) {
                detail = "TESTED · Waze " + manifest.versionName
                        + " · patch profile " + profile.version
                        + " · " + apkNames.size() + " APK splits";
            } else if (exactKnownProfile) {
                detail = "UNTESTED · Waze " + manifest.versionName
                        + " · exact binary profile " + profile.version
                        + " · " + apkNames.size() + " APK splits";
            } else {
                detail = "UNTESTED · Waze " + manifest.versionName
                        + " · fallback profile " + profile.version
                        + " · signature search + force mode · " + apkNames.size() + " APK splits";
            }
            return new Validation(true, tested, detail, manifest.versionName,
                    manifest.versionCode, apkNames.size(), baseHash, profile.version, exactKnownProfile);
        } catch (ZipException e) {
            return Validation.fail("Selected file is not a valid APKM/ZIP bundle.");
        } catch (Exception e) {
            return Validation.fail("Waze source check failed: " + e.getMessage());
        }
    }

    private static ManifestInfo readBaseManifest(ZipFile bundle, ZipEntry base) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(bundle.getInputStream(base)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if ("AndroidManifest.xml".equals(e.getName())) {
                    byte[] manifest = readAll(in, 4 * 1024 * 1024);
                    return parseManifestInfo(manifest);
                }
            }
        }
        throw new IOException("base.apk AndroidManifest.xml is missing.");
    }

    private static ManifestInfo parseManifestInfo(byte[] data) {
        if (data == null || data.length < 36) throw new IllegalArgumentException("AndroidManifest.xml is unexpectedly small");
        int rootHeaderSize = Spotify89BinaryUtil.u16(data, 2);
        Spotify89BinaryUtil.Pool pool = Spotify89BinaryUtil.parsePool(data, rootHeaderSize);
        List<String> strings = pool.strings;
        int o = rootHeaderSize + pool.size;
        if (o + 8 <= data.length && Spotify89BinaryUtil.u16(data, o) == 0x0180) {
            o += Spotify89BinaryUtil.i32(data, o + 4);
        }
        while (o + 8 <= data.length) {
            int type = Spotify89BinaryUtil.u16(data, o);
            int size = Spotify89BinaryUtil.i32(data, o + 4);
            if (size < 8 || o + size > data.length) break;
            if (type == 0x0102) {
                int tagIndex = Spotify89BinaryUtil.i32(data, o + 20);
                if (tagIndex >= 0 && tagIndex < strings.size() && "manifest".equals(strings.get(tagIndex))) {
                    int attrStart = Spotify89BinaryUtil.u16(data, o + 24);
                    int attrSize = Spotify89BinaryUtil.u16(data, o + 26);
                    int attrCount = Spotify89BinaryUtil.u16(data, o + 28);
                    int ap = o + 16 + attrStart;
                    String packageName = null;
                    String versionName = null;
                    long versionCode = -1L;
                    long versionCodeMajor = 0L;
                    for (int i = 0; i < attrCount; i++) {
                        int ao = ap + i * attrSize;
                        int nameIdx = Spotify89BinaryUtil.i32(data, ao + 4);
                        int rawIdx = Spotify89BinaryUtil.i32(data, ao + 8);
                        int dataType = data[ao + 15] & 0xff;
                        int dataValue = Spotify89BinaryUtil.i32(data, ao + 16);
                        if (nameIdx < 0 || nameIdx >= strings.size()) continue;
                        String name = strings.get(nameIdx);
                        String stringValue = null;
                        if (rawIdx != Spotify89BinaryUtil.NO_INDEX && rawIdx >= 0 && rawIdx < strings.size()) {
                            stringValue = strings.get(rawIdx);
                        } else if (dataType == 3 && dataValue >= 0 && dataValue < strings.size()) {
                            stringValue = strings.get(dataValue);
                        }
                        if ("package".equals(name)) packageName = stringValue;
                        else if ("versionName".equals(name)) versionName = stringValue;
                        else if ("versionCode".equals(name)) versionCode = dataValue & 0xffffffffL;
                        else if ("versionCodeMajor".equals(name)) versionCodeMajor = dataValue & 0xffffffffL;
                    }
                    if (versionCode >= 0 && versionCodeMajor != 0) {
                        versionCode |= (versionCodeMajor << 32);
                    }
                    return new ManifestInfo(packageName, versionName, versionCode);
                }
            }
            o += size;
        }
        throw new IllegalArgumentException("Manifest root element was not found");
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (n <= 0) continue;
            total += n;
            if (total > maxBytes) throw new IOException("Manifest is unexpectedly large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Extracts the APKM, patches base.apk, and signs base + every split with the Manager key.
     * The returned files are ready for one PackageInstaller split-APK session.
     */
    static List<File> buildSignedBundle(File sourceApkm, File outputDir, boolean allowForceFallback,
                                        boolean enableEvFeatures, boolean removeMicrophoneButton, Progress progress) throws Exception {
        Validation validation = validateSource(sourceApkm);
        if (!validation.ok) throw new IllegalArgumentException(validation.detail);
        if (enableEvFeatures && !validation.tested) {
            throw new IllegalArgumentException("Enable EV features + CCS2 requires the exact tested Waze "
                    + BuildConfigData.WAZE_SUPPORTED_VERSION + " APKM bundle.");
        }
        PatchProfile profile = chooseProfile(validation.versionName);
        if (!validation.tested) {
            progress.onProgress("Compatibility mode · Waze " + validation.displayVersion()
                    + " using patch profile " + profile.version
                    + (allowForceFallback ? " · force fallback enabled…" : "…"));
        }
        if (outputDir.exists()) deleteTree(outputDir);
        if (!outputDir.mkdirs()) throw new IOException("Cannot create Waze build directory");

        List<String> names = new ArrayList<>();
        try (ZipFile bundle = new ZipFile(sourceApkm)) {
            Enumeration<? extends ZipEntry> en = bundle.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (isApkEntry(name)) names.add(name);
            }
            names.sort((a, b) -> {
                if ("base.apk".equals(a)) return -1;
                if ("base.apk".equals(b)) return 1;
                return a.compareToIgnoreCase(b);
            });

            List<File> signed = new ArrayList<>();
            File stage = new File(outputDir, ".stage");
            if (!stage.mkdirs()) throw new IOException("Cannot create Waze staging directory");

            int index = 0;
            for (String name : names) {
                index++;
                ZipEntry entry = bundle.getEntry(name);
                if (entry == null) throw new IOException("Bundle entry disappeared: " + name);
                String safeName = new File(name).getName();
                File raw = new File(stage, safeName + ".raw");
                try (InputStream in = new BufferedInputStream(bundle.getInputStream(entry));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(raw))) {
                    IoUtil.copy(in, out);
                }

                File unsignedInput = raw;
                File patchedBase = null;
                if ("base.apk".equals(name)) {
                    String patchMessage = enableEvFeatures
                            ? "Patching Waze wide-screen layout, top-control clearance, and EV + CCS2 defaults"
                            : "Patching Waze wide-screen layout + top-control clearance";
                    if (removeMicrophoneButton) patchMessage += ", removing unsupported microphone button";
                    progress.onProgress(patchMessage + "…");
                    patchedBase = new File(stage, "base.patched.apk");
                    patchBaseApk(raw, patchedBase, profile, allowForceFallback && !validation.tested,
                            enableEvFeatures, removeMicrophoneButton, progress);
                    unsignedInput = patchedBase;
                }

                progress.onProgress("Signing Waze APK " + index + "/" + names.size() + " · " + safeName);
                File signedApk = new File(outputDir, safeName);
                ApkSignerUtil.sign(unsignedInput, signedApk);
                signed.add(signedApk);

                if (!raw.delete()) raw.deleteOnExit();
                if (patchedBase != null && !patchedBase.delete()) patchedBase.deleteOnExit();
            }
            deleteTree(stage);
            progress.onProgress("Waze base + all " + (names.size() - 1) + " splits signed with one local certificate.");
            return signed;
        }
    }

    /**
     * Patches the required stored DEX entries in place so every ZIP entry keeps its original
     * offset/alignment. This is important for modern APKs whose uncompressed resources
     * and native libraries are expected to remain aligned for mmap/direct loading.
     */
    private static void patchBaseApk(File input, File output, PatchProfile profile, boolean allowForceFallback,
                                     boolean enableEvFeatures, boolean removeMicrophoneButton, Progress progress) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(input));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            IoUtil.copy(in, out);
        }

        boolean patched5 = false, patched6 = false, patched7 = false;
        boolean microphonePatched = !removeMicrophoneButton;
        PatchRegion[] classes6Regions = enableEvFeatures
                ? concatRegions(profile.classes6, STABLE_EV_CLASSES6_PATCHES)
                : profile.classes6;
        int patchRegionCount = profile.classes5.length + classes6Regions.length + profile.classes7.length;
        PatchState patchState = new PatchState(patchRegionCount);
        progress.onProgress("Locating " + patchRegionCount + " Waze patch signatures…");
        try (RandomAccessFile raf = new RandomAccessFile(output, "rw")) {
            long eocd = findEocd(raf);
            raf.seek(eocd + 16);
            long centralOffset = readU32LE(raf);
            long pos = centralOffset;

            while (pos < eocd) {
                raf.seek(pos);
                if (readU32LE(raf) != 0x02014b50L) break;
                raf.seek(pos + 10);
                int method = readU16LE(raf);
                raf.seek(pos + 16);
                long oldCrc = readU32LE(raf);
                long compressedSize = readU32LE(raf);
                long uncompressedSize = readU32LE(raf);
                int nameLen = readU16LE(raf);
                int extraLen = readU16LE(raf);
                int commentLen = readU16LE(raf);
                raf.seek(pos + 42);
                long localOffset = readU32LE(raf);
                raf.seek(pos + 46);
                byte[] nameBytes = new byte[nameLen];
                raf.readFully(nameBytes);
                String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

                if ("classes5.dex".equals(name) || "classes6.dex".equals(name) || "classes7.dex".equals(name)) {
                    if (method != ZipEntry.STORED || compressedSize != uncompressedSize || uncompressedSize > Integer.MAX_VALUE) {
                        throw new IOException(name + " is not a supported stored DEX entry");
                    }
                    raf.seek(localOffset);
                    if (readU32LE(raf) != 0x04034b50L) throw new IOException("Bad local ZIP header for " + name);
                    raf.seek(localOffset + 26);
                    int localNameLen = readU16LE(raf);
                    int localExtraLen = readU16LE(raf);
                    long dataOffset = localOffset + 30L + localNameLen + localExtraLen;
                    byte[] data = new byte[(int) uncompressedSize];
                    raf.seek(dataOffset);
                    raf.readFully(data);

                    PatchRegion[] regions;
                    if ("classes5.dex".equals(name)) {
                        regions = profile.classes5;
                    } else if ("classes6.dex".equals(name)) {
                        regions = classes6Regions;
                    } else {
                        regions = profile.classes7;
                    }
                    data = applyRegions(data, regions, name, patchState, allowForceFallback, progress);
                    if ("classes6.dex".equals(name) && removeMicrophoneButton) {
                        data = removeUnsupportedMicrophoneButton(data, progress);
                        microphonePatched = true;
                    }
                    data = DexUtil.repair(data);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    long newCrc = crc.getValue();

                    raf.seek(dataOffset);
                    raf.write(data);
                    raf.seek(localOffset + 14);
                    writeU32LE(raf, newCrc);
                    raf.seek(pos + 16);
                    writeU32LE(raf, newCrc);

                    if ("classes5.dex".equals(name)) {
                        patched5 = true;
                    } else if ("classes6.dex".equals(name)) {
                        patched6 = true;
                    } else {
                        patched7 = true;
                    }
                }
                pos += 46L + nameLen + extraLen + commentLen;
            }
        }
        if (!patched5 || !patched6 || !patched7) throw new IOException("Expected Waze DEX files were not found.");
        if (!microphonePatched) throw new IllegalArgumentException("Waze microphone button removal was requested but no patch was applied.");
        if (patchState.recognised + patchState.forced != patchState.total) {
            throw new IllegalArgumentException("Compatibility patch failed: applied "
                    + (patchState.recognised + patchState.forced) + "/" + patchState.total + " patch regions.");
        }
        if (patchState.forced > 0) {
            progress.onProgress("FORCE MODE · " + patchState.recognised + "/" + patchState.total
                    + " regions recognised, " + patchState.forced + " written at profile offsets.");
        } else {
            progress.onProgress("Compatibility check passed · " + patchState.recognised + "/"
                    + patchState.total + " patch locations recognised.");
        }

        // Let ZipFile verify the central-directory CRCs before the APK is handed to apksig.
        try (ZipFile verify = new ZipFile(output)) {
            verifyEntry(verify, "classes5.dex");
            verifyEntry(verify, "classes6.dex");
            verifyEntry(verify, "classes7.dex");
        }
    }

    /**
     * Removes the actual right-side microphone action from Waze's Compose search field.
     *
     * Waze 5.24 renders that control in SearchFieldIdleAction. t3_voice_microphone is a
     * different icon used by Voice Settings, so drawable-based matching is intentionally not
     * used here. The Compose method has a no-action branch and two voice-action enum cases
     * (Google Assistant mic / Waze recognition mic) that share one IconButton path. Redirect
     * those two cases to the existing no-action branch. This preserves other idle actions while
     * removing both the microphone icon and its click target.
     */
    private static byte[] removeUnsupportedMicrophoneButton(byte[] input, Progress progress) {
        final String tracePrefix =
                "com.waze.design_components_compose.components.search_field.SearchFieldIdleAction (SearchField.kt:";
        int traceStringIndex = dexFindUniqueStringIndexContaining(input, tracePrefix);
        int codeStart = dexFindUniqueCodeStartUsingString(input, traceStringIndex);
        if (codeStart < 16 || codeStart >= input.length) {
            throw new IllegalArgumentException("Waze SearchFieldIdleAction code item was not found in classes6.dex.");
        }
        long codeEndLong = (long) codeStart + (long) dexU32(input, codeStart - 4) * 2L;
        if (codeEndLong > input.length) {
            throw new IllegalArgumentException("Invalid SearchFieldIdleAction instruction range in classes6.dex.");
        }
        int codeEnd = (int) codeEndLong;

        int traceUse = -1;
        for (int p = codeStart; p + 4 <= codeEnd; p += 2) {
            int opcode = input[p] & 0xff;
            boolean hit = opcode == 0x1a && dexU16(input, p + 2) == traceStringIndex;
            if (!hit && opcode == 0x1b && p + 6 <= codeEnd) {
                hit = dexU32(input, p + 2) == traceStringIndex;
            }
            if (!hit) continue;
            if (traceUse >= 0) throw new IllegalArgumentException("SearchFieldIdleAction trace use is ambiguous.");
            traceUse = p;
        }
        if (traceUse < 0) throw new IllegalArgumentException("SearchFieldIdleAction trace use was not found.");

        // Immediately after tracing, Kotlin checks whether an idle action is present. Its
        // fall-through is the already-generated Compose 'no action' group; the taken branch
        // enters the enum switch that eventually renders IconButton.
        int presenceBranch = -1;
        int nonNullStart = -1;
        int searchEnd = Math.min(codeEnd, traceUse + 0x80);
        for (int p = traceUse + 4; p + 4 <= searchEnd; p += 2) {
            if ((input[p] & 0xff) != 0x39) continue; // if-nez vAA, +BBBB
            int target = p + (short) dexU16(input, p + 2) * 2;
            if (target <= p || target >= codeEnd) continue;
            if (presenceBranch >= 0) {
                throw new IllegalArgumentException("SearchFieldIdleAction presence branch is ambiguous.");
            }
            presenceBranch = p;
            nonNullStart = target;
        }
        if (presenceBranch < 0) {
            throw new IllegalArgumentException("SearchFieldIdleAction presence branch was not found.");
        }
        int noActionStart = presenceBranch + 4;

        // Voice enum cases 1 and 2 are emitted as adjacent if-eq instructions and both jump
        // to the same microphone IconButton block. Redirect both to the existing no-action path.
        int voiceBranch1 = -1;
        int voiceBranch2 = -1;
        int enumSearchEnd = Math.min(codeEnd, nonNullStart + 0x80);
        for (int p = nonNullStart; p + 8 <= enumSearchEnd; p += 2) {
            if ((input[p] & 0xff) != 0x32 || (input[p + 4] & 0xff) != 0x32) continue;
            int target1 = p + (short) dexU16(input, p + 2) * 2;
            int target2 = p + 4 + (short) dexU16(input, p + 6) * 2;
            if (target1 != target2 || target1 <= p + 4 || target1 >= codeEnd) continue;
            if (voiceBranch1 >= 0) {
                throw new IllegalArgumentException("SearchFieldIdleAction voice branches are ambiguous.");
            }
            voiceBranch1 = p;
            voiceBranch2 = p + 4;
        }
        if (voiceBranch1 < 0) {
            throw new IllegalArgumentException("SearchFieldIdleAction voice branches were not found.");
        }

        byte[] out = input.clone();
        putDexBranchOffset16(out, voiceBranch1, noActionStart);
        putDexBranchOffset16(out, voiceBranch2, noActionStart);
        progress.onProgress("Removed unsupported Waze microphone button (SearchFieldIdleAction voice cases redirected at 0x"
                + Integer.toHexString(voiceBranch1) + ").");
        return out;
    }

    private static void putDexBranchOffset16(byte[] dex, int instructionOffset, int targetOffset) {
        int deltaBytes = targetOffset - instructionOffset;
        if ((deltaBytes & 1) != 0) throw new IllegalArgumentException("Unaligned Waze DEX branch target.");
        int units = deltaBytes / 2;
        if (units < Short.MIN_VALUE || units > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Waze DEX branch target is out of range.");
        }
        putU16LE(dex, instructionOffset + 2, units & 0xffff);
    }

    private static int dexFindUniqueStringIndexContaining(byte[] dex, String needle) {
        int size = dexU32(dex, 0x38);
        int off = dexU32(dex, 0x3c);
        int found = -1;
        for (int i = 0; i < size; i++) {
            int dataOff = dexU32(dex, off + i * 4);
            String value = dexReadString(dex, dataOff);
            if (!value.contains(needle)) continue;
            if (found >= 0) {
                throw new IllegalArgumentException("Waze SearchFieldIdleAction trace string is ambiguous in classes6.dex.");
            }
            found = i;
        }
        if (found < 0) {
            throw new IllegalArgumentException("Waze SearchFieldIdleAction trace string is missing from classes6.dex.");
        }
        return found;
    }

    private static int dexFindUniqueCodeStartUsingString(byte[] dex, int stringIndex) {
        int classDefsSize = dexU32(dex, 0x60);
        int classDefsOff = dexU32(dex, 0x64);
        int found = -1;

        for (int c = 0; c < classDefsSize; c++) {
            int classDef = classDefsOff + c * 32;
            int classDataOff = dexU32(dex, classDef + 24);
            if (classDataOff == 0) continue;

            int[] cursor = new int[]{classDataOff};
            int staticFields = dexReadUleb128(dex, cursor);
            int instanceFields = dexReadUleb128(dex, cursor);
            int directMethods = dexReadUleb128(dex, cursor);
            int virtualMethods = dexReadUleb128(dex, cursor);

            for (int i = 0; i < staticFields + instanceFields; i++) {
                dexReadUleb128(dex, cursor); // field_idx_diff
                dexReadUleb128(dex, cursor); // access_flags
            }

            int[] counts = new int[]{directMethods, virtualMethods};
            for (int count : counts) {
                int methodIndex = 0;
                for (int i = 0; i < count; i++) {
                    methodIndex += dexReadUleb128(dex, cursor);
                    dexReadUleb128(dex, cursor); // access_flags
                    int codeOff = dexReadUleb128(dex, cursor);
                    if (codeOff == 0) continue;
                    if (codeOff < 0 || codeOff + 16 > dex.length) {
                        throw new IllegalArgumentException("Invalid Waze DEX code item while locating SearchFieldIdleAction.");
                    }
                    int insnsSize = dexU32(dex, codeOff + 12);
                    long codeStartLong = (long) codeOff + 16L;
                    long codeEndLong = codeStartLong + (long) insnsSize * 2L;
                    if (codeStartLong < 0 || codeEndLong > dex.length) {
                        throw new IllegalArgumentException("Invalid Waze DEX instruction range while locating SearchFieldIdleAction.");
                    }
                    int codeStart = (int) codeStartLong;
                    int codeEnd = (int) codeEndLong;
                    if (!dexCodeUsesString(dex, codeStart, codeEnd, stringIndex)) continue;
                    if (found >= 0 && found != codeStart) {
                        throw new IllegalArgumentException("Waze SearchFieldIdleAction code item is ambiguous in classes6.dex.");
                    }
                    found = codeStart;
                }
            }
        }

        if (found < 0) {
            throw new IllegalArgumentException("Waze SearchFieldIdleAction string is not referenced by executable code in classes6.dex.");
        }
        return found;
    }

    private static boolean dexCodeUsesString(byte[] dex, int start, int end, int stringIndex) {
        for (int p = start; p + 4 <= end; p += 2) {
            int opcode = dex[p] & 0xff;
            if (opcode == 0x1a && dexU16(dex, p + 2) == stringIndex) return true; // const-string
            if (opcode == 0x1b && p + 6 <= end && dexU32(dex, p + 2) == stringIndex) return true; // jumbo
        }
        return false;
    }

    private static int dexReadUleb128(byte[] dex, int[] cursor) {
        int p = cursor[0];
        int result = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            if (p >= dex.length) throw new IllegalArgumentException("Truncated Waze DEX ULEB128 value.");
            int value = dex[p++] & 0xff;
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                cursor[0] = p;
                return result;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Invalid Waze DEX ULEB128 value.");
    }

    /** Resolve a resource ID by type/key name from Android's binary resources table. */
    private static int findResourceId(byte[] table, String wantedType, String wantedKey) {
        if (table == null || table.length < 12 || resU16(table, 0) != 0x0002) return -1;
        int tableSize = resU32(table, 4);
        int pos = resU16(table, 2);
        if (tableSize <= 0 || tableSize > table.length) tableSize = table.length;

        while (pos + 8 <= tableSize) {
            int chunkType = resU16(table, pos);
            int headerSize = resU16(table, pos + 2);
            int chunkSize = resU32(table, pos + 4);
            if (chunkSize < headerSize || chunkSize <= 0 || pos + chunkSize > tableSize) break;

            if (chunkType == 0x0200 && headerSize >= 284) { // RES_TABLE_PACKAGE_TYPE
                int packageId = resU32(table, pos + 8) & 0xff;
                int typeStringsOffset = resU32(table, pos + 268);
                int keyStringsOffset = resU32(table, pos + 276);
                int typeIdOffset = headerSize >= 288 ? resU32(table, pos + 284) : 0;
                ResStringPool typePool = readStringPool(table, pos + typeStringsOffset);
                ResStringPool keyPool = readStringPool(table, pos + keyStringsOffset);
                if (typePool == null || keyPool == null) return -1;

                int child = pos + headerSize;
                int packageEnd = pos + chunkSize;
                while (child + 8 <= packageEnd) {
                    int childType = resU16(table, child);
                    int childHeader = resU16(table, child + 2);
                    int childSize = resU32(table, child + 4);
                    if (childSize < childHeader || childSize <= 0 || child + childSize > packageEnd) break;

                    if (childType == 0x0201 && childHeader >= 20) { // RES_TABLE_TYPE_TYPE
                        int typeId = table[child + 8] & 0xff;
                        int flags = table[child + 9] & 0xff;
                        int entryCount = resU32(table, child + 12);
                        int entriesStart = resU32(table, child + 16);
                        String typeName = typePool.get(typeId - 1);
                        if (wantedType.equals(typeName)) {
                            if ((flags & 0x01) != 0) { // FLAG_SPARSE
                                int sparseCount = Math.max(0, (entriesStart - childHeader) / 4);
                                for (int i = 0; i < sparseCount; i++) {
                                    int pair = child + childHeader + i * 4;
                                    int entryIndex = resU16(table, pair);
                                    int entryOffset = resU16(table, pair + 2) * 4;
                                    int id = matchResourceEntry(table, child, childSize, entriesStart,
                                            entryOffset, packageId, typeId + typeIdOffset, entryIndex,
                                            keyPool, wantedKey);
                                    if (id >= 0) return id;
                                }
                            } else if ((flags & 0x02) != 0) { // FLAG_OFFSET16
                                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                                    int raw = resU16(table, child + childHeader + entryIndex * 2);
                                    if (raw == 0xffff) continue;
                                    int id = matchResourceEntry(table, child, childSize, entriesStart,
                                            raw * 4, packageId, typeId + typeIdOffset, entryIndex,
                                            keyPool, wantedKey);
                                    if (id >= 0) return id;
                                }
                            } else {
                                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                                    int raw = resU32(table, child + childHeader + entryIndex * 4);
                                    if (raw == 0xffffffff) continue;
                                    int id = matchResourceEntry(table, child, childSize, entriesStart,
                                            raw, packageId, typeId + typeIdOffset, entryIndex,
                                            keyPool, wantedKey);
                                    if (id >= 0) return id;
                                }
                            }
                        }
                    }
                    child += childSize;
                }
            }
            pos += chunkSize;
        }
        return -1;
    }

    private static int matchResourceEntry(byte[] table, int typeChunk, int typeChunkSize,
                                          int entriesStart, int entryOffset,
                                          int packageId, int typeId, int entryIndex,
                                          ResStringPool keyPool, String wantedKey) {
        int entry = typeChunk + entriesStart + entryOffset;
        if (entry < typeChunk || entry + 8 > typeChunk + typeChunkSize || entry + 8 > table.length) return -1;
        int keyIndex = resU32(table, entry + 4);
        if (!wantedKey.equals(keyPool.get(keyIndex))) return -1;
        if (typeId <= 0 || typeId > 0xff || entryIndex < 0 || entryIndex > 0xffff) return -1;
        return (packageId << 24) | (typeId << 16) | entryIndex;
    }

    private static ResStringPool readStringPool(byte[] data, int offset) {
        if (offset < 0 || offset + 28 > data.length || resU16(data, offset) != 0x0001) return null;
        int headerSize = resU16(data, offset + 2);
        int chunkSize = resU32(data, offset + 4);
        int count = resU32(data, offset + 8);
        int flags = resU32(data, offset + 16);
        int stringsStart = resU32(data, offset + 20);
        if (headerSize < 28 || chunkSize < headerSize || offset + chunkSize > data.length
                || count < 0 || headerSize + (long) count * 4L > chunkSize) return null;
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) offsets[i] = resU32(data, offset + headerSize + i * 4);
        return new ResStringPool(data, offset, chunkSize, stringsStart, (flags & 0x100) != 0, offsets);
    }

    private static final class ResStringPool {
        final byte[] data;
        final int base;
        final int size;
        final int stringsStart;
        final boolean utf8;
        final int[] offsets;

        ResStringPool(byte[] data, int base, int size, int stringsStart, boolean utf8, int[] offsets) {
            this.data = data;
            this.base = base;
            this.size = size;
            this.stringsStart = stringsStart;
            this.utf8 = utf8;
            this.offsets = offsets;
        }

        String get(int index) {
            if (index < 0 || index >= offsets.length) return null;
            int p = base + stringsStart + offsets[index];
            int end = base + size;
            if (p < base || p >= end || p >= data.length) return null;
            try {
                if (utf8) {
                    int[] charLen = readUtf8Length(data, p, end);
                    int[] byteLen = readUtf8Length(data, charLen[1], end);
                    int len = byteLen[0];
                    int start = byteLen[1];
                    if (len < 0 || start + len > end || start + len > data.length) return null;
                    return new String(data, start, len, StandardCharsets.UTF_8);
                }
                int[] charLen = readUtf16Length(data, p, end);
                int len = charLen[0];
                int start = charLen[1];
                if (len < 0 || start + len * 2L > end || start + len * 2L > data.length) return null;
                return new String(data, start, len * 2, StandardCharsets.UTF_16LE);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static int[] readUtf8Length(byte[] data, int p, int end) {
        if (p >= end) throw new IllegalArgumentException("Bad resource string length");
        int first = data[p++] & 0xff;
        if ((first & 0x80) == 0) return new int[]{first, p};
        if (p >= end) throw new IllegalArgumentException("Bad resource string length");
        return new int[]{((first & 0x7f) << 8) | (data[p] & 0xff), p + 1};
    }

    private static int[] readUtf16Length(byte[] data, int p, int end) {
        if (p + 2 > end) throw new IllegalArgumentException("Bad resource string length");
        int first = resU16(data, p);
        p += 2;
        if ((first & 0x8000) == 0) return new int[]{first, p};
        if (p + 2 > end) throw new IllegalArgumentException("Bad resource string length");
        int second = resU16(data, p);
        return new int[]{((first & 0x7fff) << 16) | second, p + 2};
    }

    private static int resU16(byte[] data, int off) {
        if (off < 0 || off + 2 > data.length) throw new IllegalArgumentException("Resource read outside file");
        return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
    }

    private static int resU32(byte[] data, int off) {
        if (off < 0 || off + 4 > data.length) throw new IllegalArgumentException("Resource read outside file");
        return (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
    }

    private static PatchRegion[] concatRegions(PatchRegion[] first, PatchRegion[] second) {
        PatchRegion[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void verifyEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("Missing patched entry " + name);
        try (InputStream in = zip.getInputStream(e)) {
            byte[] buf = new byte[1024 * 1024];
            while (in.read(buf) != -1) { /* force CRC validation */ }
        }
    }

    private static long findEocd(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        int window = (int) Math.min(len, 65557L);
        byte[] tail = new byte[window];
        raf.seek(len - window);
        raf.readFully(tail);
        for (int i = tail.length - 22; i >= 0; i--) {
            if ((tail[i] & 0xff) == 0x50 && (tail[i + 1] & 0xff) == 0x4b
                    && (tail[i + 2] & 0xff) == 0x05 && (tail[i + 3] & 0xff) == 0x06) {
                return len - window + i;
            }
        }
        throw new IOException("ZIP end-of-central-directory record not found");
    }

    private static int readU16LE(RandomAccessFile raf) throws IOException {
        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static long readU32LE(RandomAccessFile raf) throws IOException {
        long b0 = raf.readUnsignedByte();
        long b1 = raf.readUnsignedByte();
        long b2 = raf.readUnsignedByte();
        long b3 = raf.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void writeU32LE(RandomAccessFile raf, long value) throws IOException {
        raf.write((int) (value & 0xff));
        raf.write((int) ((value >>> 8) & 0xff));
        raf.write((int) ((value >>> 16) & 0xff));
        raf.write((int) ((value >>> 24) & 0xff));
    }

    private static byte[] applyRegions(byte[] input, PatchRegion[] regions, String dexName,
                                       PatchState state, boolean allowForceFallback, Progress progress) {
        byte[] out = input.clone();
        for (int regionIndex = 0; regionIndex < regions.length; regionIndex++) {
            PatchRegion region = regions[regionIndex];
            int patchOffset = locateRegion(out, region);
            boolean forced = false;

            if (patchOffset < 0 || patchOffset == Integer.MAX_VALUE) {
                if (!allowForceFallback) {
                    String reason = patchOffset == Integer.MAX_VALUE
                            ? "stock signature is ambiguous in this Waze build"
                            : "stock signature was not found";
                    throw compatibilityFailure(state.recognised, state.forced, state.total, dexName, regionIndex + 1, reason);
                }
                patchOffset = region.offset;
                if (patchOffset < 0 || patchOffset + region.after.length > out.length) {
                    throw compatibilityFailure(state.recognised, state.forced, state.total, dexName, regionIndex + 1,
                            "profile offset 0x" + Integer.toHexString(region.offset) + " is outside this DEX");
                }
                forced = true;
                progress.onProgress("FORCE MODE · " + dexName + " patch " + (regionIndex + 1)
                        + " signature differs; writing profile bytes at 0x"
                        + Integer.toHexString(patchOffset));
            } else if (patchOffset != region.offset) {
                progress.onProgress("Compatibility mode · relocated " + dexName + " patch "
                        + (regionIndex + 1) + " from 0x" + Integer.toHexString(region.offset)
                        + " to 0x" + Integer.toHexString(patchOffset));
            }

            byte[] replacement = region.after;
            if ("classes6.dex".equals(dexName) && regionIndex == 1 && region.after.length == 220) {
                replacement = resolveSpeedometerAnchorRefs(out, region.after);
            } else if ("classes6.dex".equals(dexName) && region.after.length == 28 && region.after[0] == 0x62 && region.after[1] == 0x09
                    && containsWord(region.after, 0xffff)) {
                replacement = resolvePreDriveSpeedometerOffsetRef(out, region.after);
            } else if ("classes6.dex".equals(dexName) && region.after.length == 34
                    && containsWord(region.after, 0xffff)) {
                replacement = resolveButtonPackTopOffsetRef(out, region.after);
            } else if ("classes6.dex".equals(dexName) && containsWord(region.after, 0xffff)) {
                replacement = resolvePreDriveTopOffsetRef(out, region.after);
            }
            System.arraycopy(replacement, 0, out, patchOffset, replacement.length);
            if (forced) state.forced++;
            else state.recognised++;
        }
        return out;
    }

    private static int locateRegion(byte[] data, PatchRegion region) {
        if (matchesAt(data, region.offset, region.before)) return region.offset;

        if (region.searchAnchor != null) {
            int anchor = findUnique(data, region.searchAnchor);
            if (anchor == Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (anchor >= 0) {
                int candidate = anchor + region.patchOffsetInAnchor;
                if (matchesAt(data, candidate, region.before)) return candidate;
                return -1;
            }
        }

        int direct = findUnique(data, region.before);
        return direct;
    }

    private static boolean matchesAt(byte[] data, int offset, byte[] pattern) {
        if (offset < 0 || pattern == null || offset + pattern.length > data.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) return false;
        }
        return true;
    }

    /**
     * @return unique match offset, -1 for no match, Integer.MAX_VALUE for more than one match.
     */
    private static int findUnique(byte[] data, byte[] pattern) {
        if (pattern == null || pattern.length == 0 || pattern.length > data.length) return -1;
        int found = -1;
        int limit = data.length - pattern.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            if (data[i] != pattern[0]) continue;
            for (int j = 1; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            if (found >= 0) return Integer.MAX_VALUE;
            found = i;
        }
        return found;
    }

    /**
     * Resolves the five Compose pool references used by the speedometer patch. The patch fills
     * 0.55078125 of the runtime map-section width (1 - the patched 0.44921875 left-panel Row weight)
     * and then wraps the AndroidView to Alignment.CenterHorizontally. The profile template supplies
     * the final dp offset; no Configuration.screenWidthDp or raw-pixel positioning is used.
     */
    private static byte[] resolveSpeedometerAnchorRefs(byte[] dex, byte[] template) {
        if (template.length != 220) throw new IllegalArgumentException("Unexpected Waze speedometer patch size");

        int fillMaxWidth = dexFindMethodIndex(dex,
                "Landroidx/compose/foundation/layout/SizeKt;", "fillMaxWidth$default",
                "Landroidx/compose/ui/Modifier;",
                "Landroidx/compose/ui/Modifier;", "F", "I", "Ljava/lang/Object;");
        int alignmentCompanion = dexFindFieldIndex(dex,
                "Landroidx/compose/ui/Alignment;", "Companion",
                "Landroidx/compose/ui/Alignment$Companion;");
        int getCenterHorizontally = dexFindMethodIndex(dex,
                "Landroidx/compose/ui/Alignment$Companion;", "getCenterHorizontally",
                "Landroidx/compose/ui/Alignment$Horizontal;");
        int wrapContentWidth = dexFindMethodIndex(dex,
                "Landroidx/compose/foundation/layout/SizeKt;", "wrapContentWidth$default",
                "Landroidx/compose/ui/Modifier;",
                "Landroidx/compose/ui/Modifier;", "Landroidx/compose/ui/Alignment$Horizontal;",
                "Z", "I", "Ljava/lang/Object;");
        int offset = dexFindMethodIndex(dex,
                "Landroidx/compose/foundation/layout/OffsetKt;", "offset-VpY3zN4",
                "Landroidx/compose/ui/Modifier;",
                "Landroidx/compose/ui/Modifier;", "F", "F");

        byte[] resolved = template.clone();
        // Word offsets inside the fixed 220-byte replacement template.
        putU16LE(resolved, 23 * 2, fillMaxWidth);
        putU16LE(resolved, 27 * 2, alignmentCompanion);
        putU16LE(resolved, 29 * 2, getCenterHorizontally);
        putU16LE(resolved, 34 * 2, wrapContentWidth);
        putU16LE(resolved, 42 * 2, offset);
        return resolved;
    }

    private static byte[] resolvePreDriveSpeedometerOffsetRef(byte[] dex, byte[] template) {
        if (template.length != 28) throw new IllegalArgumentException("Unexpected Waze PreDrive speedometer patch size");
        int offset = dexFindMethodIndex(dex,
                "Landroidx/compose/foundation/layout/OffsetKt;", "offset-VpY3zN4",
                "Landroidx/compose/ui/Modifier;",
                "Landroidx/compose/ui/Modifier;", "F", "F");
        byte[] resolved = template.clone();
        // invoke-static begins at word 8; its method reference is word 9.
        putU16LE(resolved, 9 * 2, offset);
        return resolved;
    }

    private static byte[] resolveButtonPackTopOffsetRef(byte[] dex, byte[] template) {
        if (template.length != 34) throw new IllegalArgumentException("Unexpected Waze ButtonPack top-offset patch size");
        int offset = dexFindMethodIndex(dex,
                "Landroidx/compose/foundation/layout/OffsetKt;", "offset-VpY3zN4",
                "Landroidx/compose/ui/Modifier;",
                "Landroidx/compose/ui/Modifier;", "F", "F");
        byte[] resolved = template.clone();
        // invoke-static begins at byte 6; its method reference is bytes 8..9.
        putU16LE(resolved, 8, offset);
        return resolved;
    }

    private static byte[] resolvePreDriveTopOffsetRef(byte[] dex, byte[] template) {
        int offset = dexFindMethodIndex(dex,
                "Landroidx/compose/foundation/layout/OffsetKt;", "offset-VpY3zN4",
                "Landroidx/compose/ui/Modifier;", "Landroidx/compose/ui/Modifier;", "F", "F");
        byte[] resolved = template.clone();
        int references = 0;
        for (int i = 2; i + 1 < resolved.length; i += 2) {
            if (((resolved[i] & 0xff) | ((resolved[i + 1] & 0xff) << 8)) == 0xffff) {
                if (((resolved[i - 2] & 0xff) | ((resolved[i - 1] & 0xff) << 8)) != 0x3071) {
                    throw new IllegalArgumentException("Unexpected pre-drive offset reference");
                }
                putU16LE(resolved, i, offset);
                references++;
            }
        }
        if (references != 1) throw new IllegalArgumentException("Expected one pre-drive offset reference");
        return resolved;
    }

    private static boolean containsWord(byte[] data, int value) {
        for (int i = 0; i + 1 < data.length; i += 2) {
            if (((data[i] & 0xff) | ((data[i + 1] & 0xff) << 8)) == value) return true;
        }
        return false;
    }

    private static int dexFindFieldIndex(byte[] dex, String owner, String name, String type) {
        int ownerType = dexFindTypeIndex(dex, owner);
        int fieldType = dexFindTypeIndex(dex, type);
        int nameString = dexFindStringIndex(dex, name);
        int size = dexU32(dex, 0x50);
        int off = dexU32(dex, 0x54);
        for (int i = 0; i < size; i++) {
            int p = off + i * 8;
            if (dexU16(dex, p) == ownerType
                    && dexU16(dex, p + 2) == fieldType
                    && dexU32(dex, p + 4) == nameString) return i;
        }
        throw new IllegalArgumentException("Waze DEX is missing Compose field " + owner + "->" + name);
    }

    private static int dexFindMethodIndex(byte[] dex, String owner, String name,
                                          String returnType, String... parameterTypes) {
        int ownerType = dexFindTypeIndex(dex, owner);
        int nameString = dexFindStringIndex(dex, name);
        int returnTypeIdx = dexFindTypeIndex(dex, returnType);
        int[] params = new int[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) params[i] = dexFindTypeIndex(dex, parameterTypes[i]);

        int size = dexU32(dex, 0x58);
        int off = dexU32(dex, 0x5c);
        for (int i = 0; i < size; i++) {
            int p = off + i * 8;
            if (dexU16(dex, p) != ownerType || dexU32(dex, p + 4) != nameString) continue;
            int protoIdx = dexU16(dex, p + 2);
            if (dexProtoMatches(dex, protoIdx, returnTypeIdx, params)) return i;
        }
        throw new IllegalArgumentException("Waze DEX is missing Compose method " + owner + "->" + name);
    }

    private static boolean dexProtoMatches(byte[] dex, int protoIdx, int returnTypeIdx, int[] params) {
        int protoOff = dexU32(dex, 0x4c) + protoIdx * 12;
        if (dexU32(dex, protoOff + 4) != returnTypeIdx) return false;
        int paramsOff = dexU32(dex, protoOff + 8);
        if (params.length == 0) return paramsOff == 0 || dexU32(dex, paramsOff) == 0;
        if (paramsOff == 0 || dexU32(dex, paramsOff) != params.length) return false;
        for (int i = 0; i < params.length; i++) {
            if (dexU16(dex, paramsOff + 4 + i * 2) != params[i]) return false;
        }
        return true;
    }

    private static int dexFindTypeIndex(byte[] dex, String descriptor) {
        int descriptorString = dexFindStringIndex(dex, descriptor);
        int size = dexU32(dex, 0x40);
        int off = dexU32(dex, 0x44);
        for (int i = 0; i < size; i++) if (dexU32(dex, off + i * 4) == descriptorString) return i;
        throw new IllegalArgumentException("Waze DEX is missing type " + descriptor);
    }

    private static int dexFindStringIndex(byte[] dex, String wanted) {
        int size = dexU32(dex, 0x38);
        int off = dexU32(dex, 0x3c);
        for (int i = 0; i < size; i++) {
            int dataOff = dexU32(dex, off + i * 4);
            if (wanted.equals(dexReadString(dex, dataOff))) return i;
        }
        throw new IllegalArgumentException("Waze DEX is missing string " + wanted);
    }

    private static String dexReadString(byte[] dex, int dataOff) {
        int p = dataOff;
        int b;
        do {
            if (p >= dex.length) throw new IllegalArgumentException("Invalid Waze DEX string offset");
            b = dex[p++] & 0xff;
        } while ((b & 0x80) != 0); // skip UTF-16 length ULEB128
        int end = p;
        while (end < dex.length && dex[end] != 0) end++;
        if (end >= dex.length) throw new IllegalArgumentException("Unterminated Waze DEX string");
        return new String(dex, p, end - p, StandardCharsets.UTF_8);
    }

    private static int dexU16(byte[] data, int off) {
        if (off < 0 || off + 2 > data.length) throw new IllegalArgumentException("Waze DEX read outside file");
        return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
    }

    private static int dexU32(byte[] data, int off) {
        if (off < 0 || off + 4 > data.length) throw new IllegalArgumentException("Waze DEX read outside file");
        return (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
    }

    private static void putU16LE(byte[] data, int off, int value) {
        if (value < 0 || value > 0xffff || off < 0 || off + 2 > data.length) {
            throw new IllegalArgumentException("Invalid Waze DEX 16-bit reference");
        }
        data[off] = (byte) value;
        data[off + 1] = (byte) (value >>> 8);
    }

    private static IllegalArgumentException compatibilityFailure(int recognised, int forced, int total, String dexName,
                                                                 int regionNumber, String reason) {
        return new IllegalArgumentException("Compatibility patch failed after "
                + (recognised + forced) + "/" + total + " regions. " + dexName + " patch "
                + regionNumber + " " + reason + ".");
    }

    private static PatchProfile chooseProfile(String versionName) {
        if (versionName != null && versionName.startsWith("5.24.4.")) return BETA_PROFILE;
        return STABLE_PROFILE;
    }

    private static boolean isApkEntry(String name) {
        return name != null && !name.contains("/") && name.toLowerCase(Locale.US).endsWith(".apk");
    }

    private static String sha256(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) if (n > 0) md.update(buf, 0, n);
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static byte[] hex(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) f.deleteOnExit();
    }

    private static final class PatchState {
        final int total;
        int recognised;
        int forced;
        PatchState(int total) { this.total = total; }
    }

    private static final class ManifestInfo {
        final String packageName;
        final String versionName;
        final long versionCode;
        ManifestInfo(String packageName, String versionName, long versionCode) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }

    private static final class PatchProfile {
        final String version;
        final PatchRegion[] classes5;
        final PatchRegion[] classes6;
        final PatchRegion[] classes7;

        PatchProfile(String version, PatchRegion[] classes5, PatchRegion[] classes6, PatchRegion[] classes7) {
            this.version = version;
            this.classes5 = classes5;
            this.classes6 = classes6;
            this.classes7 = classes7;
        }
    }

    private static final class PatchRegion {
        final int offset;
        final byte[] before;
        final byte[] after;
        final byte[] searchAnchor;
        final int patchOffsetInAnchor;

        PatchRegion(int offset, String beforeHex, String afterHex) {
            this(offset, beforeHex, afterHex, null, 0);
        }

        PatchRegion(int offset, String beforeHex, String afterHex,
                    String searchAnchorHex, int patchOffsetInAnchor) {
            this.offset = offset;
            this.before = hex(beforeHex);
            this.after = hex(afterHex);
            this.searchAnchor = searchAnchorHex == null ? null : hex(searchAnchorHex);
            this.patchOffsetInAnchor = patchOffsetInAnchor;
            if (before.length != after.length) throw new IllegalArgumentException("Waze patch length mismatch");
            if (searchAnchor != null) {
                if (patchOffsetInAnchor < 0 || patchOffsetInAnchor + before.length > searchAnchor.length) {
                    throw new IllegalArgumentException("Waze search anchor does not contain patch bytes");
                }
                for (int i = 0; i < before.length; i++) {
                    if (searchAnchor[patchOffsetInAnchor + i] != before[i]) {
                        throw new IllegalArgumentException("Waze search anchor target mismatch");
                    }
                }
            }
        }
    }
}

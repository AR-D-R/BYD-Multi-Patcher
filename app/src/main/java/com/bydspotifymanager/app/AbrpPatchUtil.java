package com.bydspotifymanager.app;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact, version-locked BYD layout edits for ABRP 7.1.7 (Hermes bytecode v98). */
final class AbrpPatchUtil {
    static final int PANEL_WIDTH_DP = 360;
    static final int BYD_BOTTOM_SAFE_AREA_DP = 76;

    private static final long HERMES_MAGIC = 0x1f1903c103bc1fc6L;
    private static final int HERMES_VERSION = 98;
    private static final int HERMES_LENGTH = 21_850_384;

    // These offsets are protected by AbrpSourceValidator's exact base.apk hash and by
    // per-edit old-value assertions below. They refer to ABRP 7.1.7's index.android.bundle.
    private static final int[] PANEL_WIDTH_IMMEDIATE_OFFSETS = {
            0x00bc1d3b, // BOTTOM_SHEET_MAX_WIDTH
            0x00f787e7, // SettingsNavigationView panelWidth
            0x0115f51c, // map-padding panel-width fallback
            0x008a7dd5  // Settings largeScreenPanel style literal
    };
    private static final int CAMERA_LEFT_SOURCE_OFFSET = 0x0115f585;
    private static final int CAMERA_RIGHT_SOURCE_OFFSET = 0x0115f5cb;
    private static final int DRIVING_CAMERA_VERTICAL_BIAS_OFFSET = 0x00d822df;
    private static final int ANDROID_BOTTOM_MINIMUM_OFFSET = 0x00deb0dd;
    private static final int AUTOMOTIVE_BOTTOM_FALLBACK_OFFSET = 0x00deb120;
    private static final int[] MAP_LANDSCAPE_BOTTOM_OFFSETS = {
            0x00fd058c, // MapOverlays: right-hand map button stack
            0x00fd05ea  // MapOverlays: left-hand/alternate button stack
    };
    private static final int MAP_LANDSCAPE_SPEED_BOTTOM_OFFSET = 0x00fd08b5;
    private static final int DRIVING_CONTENT_STYLE_KEYS_OFFSET = 0x00bd5dba;
    private static final int DRIVING_CONTENT_BOTTOM_PADDING_OFFSET = 0x00bd5dc7;
    private static final int DRIVING_MINIMIZED_SNAP_SPACING_OFFSET = 0x0112af89;
    private static final int DRIVING_MINIMIZED_SNAP_SUBTRACT_OFFSET = 0x0112af8e;
    private static final int DRIVING_MINIMIZED_SNAP_VALUE_OFFSET = 0x0112af98;

    // Device-settings initial Redux state, field `displayMode`. The stock HBC98 literal
    // points at string ID 36642 ("system"). Optional first-run patch redirects only this
    // literal to string ID 4874 ("automatic"); persisted user settings still override it.
    private static final int DISPLAY_MODE_DEFAULT_STRING_ID_OFFSET = 0x0091e43a;
    private static final int DISPLAY_MODE_SYSTEM_ID_LO = 0x22;
    private static final int DISPLAY_MODE_SYSTEM_ID_HI = 0x8f;
    private static final int DISPLAY_MODE_AUTOMATIC_ID_LO = 0x0a;
    private static final int DISPLAY_MODE_AUTOMATIC_ID_HI = 0x13;

    // Tail of the same 68-field fresh device-settings object, beginning at
    // routesPlanned (field 36). Stock voiceEngine / voiceId are both null.
    // Native-TTS mode rewrites only this fixed-size 24-byte SLP tail so fresh
    // ABRP starts with engine alias "native" (string ID 36501 / 0x8e95) and
    // voice alias "en" (string ID 36004 / 0x8ca4) already selected. Both
    // aliases already exist in the stock Hermes string table; the injected
    // TextToSpeech facade maps them to BYD Cerence; "en" is the fresh default while
    // the facade exposes every Cerence voice installed in the vehicle. To keep the HBC
    // literal buffer byte-for-byte the same size, releaseNotesVersion changes
    // from integer 0 to null (equivalent first-run/no-version state here) and
    // one later Undefined run uses the valid two-byte extended SLP header.
    private static final int DEVICE_SETTINGS_TTS_TAIL_OFFSET = 0x0091e459;
    private static final int[] DEVICE_SETTINGS_TTS_TAIL_STOCK = {
            0x01, 0x71, 0x00, 0x00, 0x00, 0x00, 0x21, 0x01,
            0x22, 0x12, 0x25, 0x61, 0x02, 0x26, 0x71, 0x01,
            0x00, 0x00, 0x00, 0x24, 0x01, 0x21, 0x02, 0x61
    };
    private static final int[] DEVICE_SETTINGS_TTS_TAIL_BYD = {
            0x02, 0x21, 0x01, 0x22, 0x12, 0x25, 0xe0, 0x01,
            0x52, 0x95, 0x8e, 0xa4, 0x8c, 0x26, 0x71, 0x01,
            0x00, 0x00, 0x00, 0x24, 0x01, 0x21, 0x02, 0x61
    };

    private AbrpPatchUtil() {}

    static byte[] patchHermesBundle(byte[] input, boolean autoDayNightDefault, boolean nativeBydTts) throws Exception {
        if (input == null || input.length != HERMES_LENGTH) {
            throw new IllegalArgumentException("Unexpected ABRP Hermes bundle length");
        }
        if (readLongLe(input, 0) != HERMES_MAGIC || readIntLe(input, 8) != HERMES_VERSION) {
            throw new IllegalArgumentException("Expected ABRP Hermes bytecode v98");
        }
        verifyFooter(input);

        byte[] out = input.clone();

        // This is the default value in ABRP's fresh device-settings state only. Do not
        // alter runtime mode-selection logic: once ABRP persists a user's choice, normal
        // state rehydration wins over this initializer.
        requireByte(out, DISPLAY_MODE_DEFAULT_STRING_ID_OFFSET,
                DISPLAY_MODE_SYSTEM_ID_LO, "stock appearance default string ID low byte");
        requireByte(out, DISPLAY_MODE_DEFAULT_STRING_ID_OFFSET + 1,
                DISPLAY_MODE_SYSTEM_ID_HI, "stock appearance default string ID high byte");
        if (autoDayNightDefault) {
            putBytes(out, DISPLAY_MODE_DEFAULT_STRING_ID_OFFSET,
                    DISPLAY_MODE_AUTOMATIC_ID_LO, DISPLAY_MODE_AUTOMATIC_ID_HI);
        }

        requireBytes(out, DEVICE_SETTINGS_TTS_TAIL_OFFSET, DEVICE_SETTINGS_TTS_TAIL_STOCK);
        if (nativeBydTts) {
            putBytes(out, DEVICE_SETTINGS_TTS_TAIL_OFFSET, DEVICE_SETTINGS_TTS_TAIL_BYD);
        }

        for (int offset : PANEL_WIDTH_IMMEDIATE_OFFSETS) {
            requireInt(out, offset, 460, "460dp large-screen panel width");
            putIntLe(out, offset, PANEL_WIDTH_DP);
        }

        // ABRP normally copies the left/right overlay width into Mapbox camera padding,
        // moving the vehicle into the unobscured part of the map. On a very wide BYD
        // display the overlays can remain on the left while the vehicle stays centred in
        // the physical screen. These are the source-register bytes of the two Mov ops.
        requireByte(out, CAMERA_LEFT_SOURCE_OFFSET, 18, "left camera-padding source");
        out[CAMERA_LEFT_SOURCE_OFFSET] = 16; // register already holding zero
        requireByte(out, CAMERA_RIGHT_SOURCE_OFFSET, 18, "right camera-padding source");
        out[CAMERA_RIGHT_SOURCE_OFFSET] = 14; // register already holding zero

        // Rotating navigation camera: ABRP biases the car to roughly 90% of the
        // unobscured map height by adding 0.8 * usableHeight to its top padding.
        // 0.6 places it roughly 80% down, nearer the vertical centre while still
        // leaving road ahead visible. Non-rotating map framing is unchanged.
        requireBytes(out, DRIVING_CAMERA_VERTICAL_BIAS_OFFSET - 2, 0x8d, 0x00);
        requireLong(out, DRIVING_CAMERA_VERTICAL_BIAS_OFFSET,
                Double.doubleToRawLongBits(0.8), "driving camera vertical bias");
        putLongLe(out, DRIVING_CAMERA_VERTICAL_BIAS_OFFSET,
                Double.doubleToRawLongBits(0.6));

        // The BYD build can present as either normal Android or Android Automotive.
        // Both paths receive a 76dp minimum: the 60dp bar plus ABRP's original 16dp
        // margin. Each replacement keeps the original five-byte instruction footprint.
        requireBytes(out, ANDROID_BOTTOM_MINIMUM_OFFSET,
                0x44, 0x03, 0x03, 0x0b, 0x9c); // GetByIdShort r3, r3, "lg"
        putBytes(out, ANDROID_BOTTOM_MINIMUM_OFFSET,
                0x8b, 0x03, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x18);
        requireBytes(out, AUTOMOTIVE_BOTTOM_FALLBACK_OFFSET,
                0x44, 0x0a, 0x0b, 0x0b, 0x9c); // GetByIdShort r10, r11, "lg"
        putBytes(out, AUTOMOTIVE_BOTTOM_FALLBACK_OFFSET,
                0x8b, 0x0a, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x18);

        // MapOverlays uses fixed landscape offsets instead of its safe-area hook.
        // Move the two button-stack placements and the driving speed display above
        // the persistent BYD navigation bar. r44 is unused in this function.
        for (int offset : MAP_LANDSCAPE_BOTTOM_OFFSETS) {
            requireBytes(out, offset, 0x44, 0x1c, 0x1c, 0x12, 0x9c);
            putBytes(out, offset, 0x8b, 0x1c, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x2c);
        }
        requireBytes(out, MAP_LANDSCAPE_SPEED_BOTTOM_OFFSET,
                0x44, 0x17, 0x10, 0x12, 0x9c);
        putBytes(out, MAP_LANDSCAPE_SPEED_BOTTOM_OFFSET,
                0x8b, 0x17, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x2c);

        // The expanded trip panel uses a native SafeAreaView, which receives a zero
        // bottom inset on BYD. Give its content a real bottom pad, preserving a
        // scrollable area for the final action labels above the navigation bar.
        // The key-buffer index 3218 is the existing {flex: 1, paddingBottom: null}
        // shape with the same value buffer as this component's original style.
        requireBytes(out, DRIVING_CONTENT_STYLE_KEYS_OFFSET, 0x6f, 0x09, 0x00, 0x00);
        putBytes(out, DRIVING_CONTENT_STYLE_KEYS_OFFSET, 0x92, 0x0c, 0x00, 0x00);
        requireBytes(out, DRIVING_CONTENT_BOTTOM_PADDING_OFFSET,
                0x44, 0x0a, 0x0a, 0x06, 0xd5); // spacing.sm (8dp)
        putBytes(out, DRIVING_CONTENT_BOTTOM_PADDING_OFFSET,
                0x8b, 0x0a, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x15);

        // The collapsed driving sheet's first snap height is the measured summary
        // height; subsequent snap heights add the normal 16dp row gap. Subtract that
        // gap from only the first array entry, lowering the minimized sheet 16dp.
        // The expanded heights still use the original 16dp gap and are unchanged.
        // r19 is unused in this function and fills the two spare instruction bytes.
        requireBytes(out, DRIVING_MINIMIZED_SNAP_SPACING_OFFSET,
                0x44, 0x04, 0x04, 0x00, 0x57); // style.container
        putBytes(out, DRIVING_MINIMIZED_SNAP_SPACING_OFFSET,
                0x8b, 0x08, 0x10, 0x93, 0x13); // r8 = spacing.lg = 16
        requireBytes(out, DRIVING_MINIMIZED_SNAP_SUBTRACT_OFFSET,
                0x45, 0x08, 0x04, 0x01, 0x20, 0xda); // style.container.rowGap
        putBytes(out, DRIVING_MINIMIZED_SNAP_SUBTRACT_OFFSET,
                0x26, 0x04, 0x06, 0x08, 0x93, 0x13); // r4 = summary - 16
        requireBytes(out, DRIVING_MINIMIZED_SNAP_VALUE_OFFSET,
                0x5a, 0x05, 0x06, 0x00); // heights[0] = summary
        putBytes(out, DRIVING_MINIMIZED_SNAP_VALUE_OFFSET,
                0x5a, 0x05, 0x04, 0x00); // heights[0] = summary - 16

        repairFooter(out);
        verifyPatchedHermes(out, autoDayNightDefault, nativeBydTts);
        return out;
    }

    /**
     * Target API 34 keeps the system bar from covering ABRP's controls. Disable Expo
     * Updates as well: an over-the-air bundle otherwise replaces our patched embedded
     * Hermes bundle on the next launch, undoing every BYD layout adjustment.
     *
     * When direct BYD telemetry is enabled, the manifest Application is changed to our
     * tiny subclass and the same read-only BYD permissions/provider visibility formerly
     * carried by the companion app are declared directly on ABRP.
     */
    private static final String STOCK_APPLICATION = "com.iternio.abrpapp.MainApplication";
    private static final String DIRECT_APPLICATION = "com.iternio.abrpapp.DirectTelemetryApplication";
    private static final String BYD_PROVIDER_PACKAGE = "com.byd.data.collect";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String[] BYD_READ_PERMISSIONS = {
            "android.permission.BYDAUTO_STATISTIC_GET",
            "android.permission.BYDAUTO_STATISTIC_COMMON",
            "android.permission.BYDAUTO_INSTRUMENT_GET",
            "android.permission.BYDAUTO_INSTRUMENT_COMMON"
    };

    static byte[] patchManifestForByd(byte[] input, boolean directBydBattery, boolean nativeBydTts) {
        int targetSdk = nativeBydTts ? 28 : 34;
        byte[] out = inspectManifest(input, true, targetSdk);
        if (!directBydBattery) {
            verifyApplication(out, false);
            verifyDirectManifestExtras(out, false);
            return out;
        }

        byte[] replaced = BinaryStringPoolUtil.replaceFirstString(out,
                STOCK_APPLICATION, DIRECT_APPLICATION);
        if (Arrays.equals(out, replaced)) {
            throw new IllegalArgumentException("ABRP MainApplication manifest string was not found");
        }
        out = addRootStringElements(replaced, "uses-permission", BYD_READ_PERMISSIONS);
        out = addPackageVisibility(out, BYD_PROVIDER_PACKAGE);
        verifyApplication(out, true);
        verifyDirectManifestExtras(out, true);
        return out;
    }

    static void verifyPatchedManifest(byte[] input, boolean directBydBattery, boolean nativeBydTts) {
        inspectManifest(input, false, nativeBydTts ? 28 : 34);
        verifyApplication(input, directBydBattery);
        verifyDirectManifestExtras(input, directBydBattery);
    }

    private static void verifyApplication(byte[] input, boolean directBydBattery) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool pool = Spotify89BinaryUtil.parsePool(input, rootHeader);
        int stock = 0;
        int direct = 0;
        for (String value : pool.strings) {
            if (STOCK_APPLICATION.equals(value)) stock++;
            if (DIRECT_APPLICATION.equals(value)) direct++;
            if ("com.iternio.abrpapp.DiagApplication".equals(value)
                    || "com.iternio.abrpapp.LinkApplication".equals(value)) {
                throw new IllegalArgumentException("Obsolete ABRP diagnostic/link Application is still present");
            }
        }
        int expectedStock = directBydBattery ? 0 : 1;
        int expectedDirect = directBydBattery ? 1 : 0;
        if (stock != expectedStock || direct != expectedDirect) {
            throw new IllegalArgumentException("Unexpected ABRP Application manifest state; stock="
                    + stock + " direct=" + direct + " expectedDirect=" + directBydBattery);
        }
    }

    /** Add one or more root-level string-valued elements such as uses-permission. */
    private static byte[] addRootStringElements(byte[] input, String tagName, String[] values) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool meta = Spotify89BinaryUtil.parsePool(input, rootHeader);
        List<String> strings = new ArrayList<>(meta.strings);

        int tagIndex = strings.indexOf(tagName);
        if (tagIndex < 0) {
            tagIndex = strings.size();
            strings.add(tagName);
        }
        int nameAttr = strings.indexOf("name");
        int androidNs = strings.indexOf(ANDROID_NS);
        if (nameAttr < 0 || androidNs < 0) {
            throw new IllegalArgumentException("ABRP manifest name/android XML strings are missing");
        }

        int[] valueIndexes = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int index = strings.indexOf(values[i]);
            if (index < 0) {
                index = strings.size();
                strings.add(values[i]);
            }
            valueIndexes[i] = index;
        }

        byte[] rebuiltPool = Spotify89BinaryUtil.buildPool(strings, meta);
        byte[] out = rebuildWithStringPool(input, rootHeader, meta.size, rebuiltPool);

        int offset = firstXmlNodeOffset(out, rootHeader, rebuiltPool.length);
        int insertAt = -1;
        int lineNumber = 0;
        int[] existing = new int[values.length];
        while (offset + 8 <= out.length) {
            int type = Spotify89BinaryUtil.u16(out, offset);
            int size = Spotify89BinaryUtil.i32(out, offset + 4);
            if (size < 8 || offset + size > out.length) {
                throw new IllegalArgumentException("Malformed ABRP manifest while adding " + tagName);
            }
            if (type == 0x0102) {
                int currentTagIndex = Spotify89BinaryUtil.i32(out, offset + 20);
                String currentTag = currentTagIndex >= 0 && currentTagIndex < strings.size()
                        ? strings.get(currentTagIndex) : null;
                if ("application".equals(currentTag) && insertAt < 0) {
                    insertAt = offset;
                    lineNumber = Spotify89BinaryUtil.i32(out, offset + 8);
                    break;
                }
                if (tagName.equals(currentTag)) {
                    String name = readStringAttribute(out, offset, strings, "name");
                    if (name != null) {
                        for (int i = 0; i < values.length; i++) {
                            if (values[i].equals(name)) existing[i]++;
                        }
                    }
                }
            }
            offset += size;
        }
        if (insertAt < 0) throw new IllegalArgumentException("ABRP manifest <application> was not found");

        ArrayList<byte[]> additions = new ArrayList<>();
        int addedBytes = 0;
        for (int i = 0; i < values.length; i++) {
            if (existing[i] > 1) {
                throw new IllegalArgumentException("Unexpected duplicate manifest entry for " + values[i]);
            }
            if (existing[i] == 1) continue;
            byte[] element = makeStringElement(tagIndex, androidNs, nameAttr, valueIndexes[i], lineNumber);
            additions.add(element);
            addedBytes += element.length;
        }
        if (addedBytes == 0) return out;

        byte[] patched = new byte[out.length + addedBytes];
        System.arraycopy(out, 0, patched, 0, insertAt);
        int cursor = insertAt;
        for (byte[] element : additions) {
            System.arraycopy(element, 0, patched, cursor, element.length);
            cursor += element.length;
        }
        System.arraycopy(out, insertAt, patched, cursor, out.length - insertAt);
        Spotify89BinaryUtil.put32(patched, 4, patched.length);
        return patched;
    }

    /** Add a package query to the existing <queries> element. */
    private static byte[] addPackageVisibility(byte[] input, String packageName) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool meta = Spotify89BinaryUtil.parsePool(input, rootHeader);
        List<String> strings = new ArrayList<>(meta.strings);

        int packageValueIndex = strings.indexOf(packageName);
        if (packageValueIndex < 0) {
            packageValueIndex = strings.size();
            strings.add(packageName);
        }
        int packageTag = strings.indexOf("package");
        int nameAttr = strings.indexOf("name");
        int androidNs = strings.indexOf(ANDROID_NS);
        if (packageTag < 0 || nameAttr < 0 || androidNs < 0) {
            throw new IllegalArgumentException("ABRP manifest package/name/android XML strings are missing");
        }

        byte[] rebuiltPool = Spotify89BinaryUtil.buildPool(strings, meta);
        byte[] out = rebuildWithStringPool(input, rootHeader, meta.size, rebuiltPool);
        int offset = firstXmlNodeOffset(out, rootHeader, rebuiltPool.length);
        int depth = 0;
        int queriesDepth = -1;
        int insertAt = -1;
        int lineNumber = 0;
        int existing = 0;
        while (offset + 8 <= out.length) {
            int type = Spotify89BinaryUtil.u16(out, offset);
            int size = Spotify89BinaryUtil.i32(out, offset + 4);
            if (size < 8 || offset + size > out.length) {
                throw new IllegalArgumentException("Malformed ABRP manifest while adding package visibility");
            }
            if (type == 0x0102) {
                int tagIndex = Spotify89BinaryUtil.i32(out, offset + 20);
                String tag = tagIndex >= 0 && tagIndex < strings.size() ? strings.get(tagIndex) : null;
                if ("queries".equals(tag) && queriesDepth < 0) {
                    queriesDepth = depth;
                } else if (queriesDepth >= 0 && "package".equals(tag)) {
                    String name = readStringAttribute(out, offset, strings, "name");
                    if (packageName.equals(name)) existing++;
                }
                depth++;
            } else if (type == 0x0103) {
                depth--;
                int tagIndex = Spotify89BinaryUtil.i32(out, offset + 20);
                String tag = tagIndex >= 0 && tagIndex < strings.size() ? strings.get(tagIndex) : null;
                if (queriesDepth >= 0 && depth == queriesDepth && "queries".equals(tag)) {
                    insertAt = offset;
                    lineNumber = Spotify89BinaryUtil.i32(out, offset + 8);
                    break;
                }
            }
            offset += size;
        }
        if (queriesDepth < 0 || insertAt < 0) {
            throw new IllegalArgumentException("ABRP manifest <queries> element was not found");
        }
        if (existing == 1) return out;
        if (existing != 0) {
            throw new IllegalArgumentException("Unexpected duplicate package queries for " + packageName + ": " + existing);
        }

        byte[] element = makeStringElement(packageTag, androidNs, nameAttr,
                packageValueIndex, lineNumber);
        byte[] patched = new byte[out.length + element.length];
        System.arraycopy(out, 0, patched, 0, insertAt);
        System.arraycopy(element, 0, patched, insertAt, element.length);
        System.arraycopy(out, insertAt, patched, insertAt + element.length, out.length - insertAt);
        Spotify89BinaryUtil.put32(patched, 4, patched.length);
        return patched;
    }

    private static byte[] rebuildWithStringPool(byte[] input, int rootHeader, int oldPoolSize,
                                                 byte[] rebuiltPool) {
        byte[] out = new byte[input.length - oldPoolSize + rebuiltPool.length];
        System.arraycopy(input, 0, out, 0, rootHeader);
        System.arraycopy(rebuiltPool, 0, out, rootHeader, rebuiltPool.length);
        System.arraycopy(input, rootHeader + oldPoolSize, out, rootHeader + rebuiltPool.length,
                input.length - (rootHeader + oldPoolSize));
        Spotify89BinaryUtil.put32(out, 4, out.length);
        return out;
    }

    private static int firstXmlNodeOffset(byte[] data, int rootHeader, int poolSize) {
        int offset = rootHeader + poolSize;
        if (offset + 8 <= data.length && Spotify89BinaryUtil.u16(data, offset) == 0x0180) {
            offset += Spotify89BinaryUtil.i32(data, offset + 4);
        }
        return offset;
    }

    /** Build a complete empty XML element containing one android:name string attribute. */
    private static byte[] makeStringElement(int tagIndex, int androidNs, int nameAttr,
                                            int valueIndex, int lineNumber) {
        byte[] start = new byte[56];
        Spotify89BinaryUtil.put16(start, 0, 0x0102);
        Spotify89BinaryUtil.put16(start, 2, 16);
        Spotify89BinaryUtil.put32(start, 4, start.length);
        Spotify89BinaryUtil.put32(start, 8, lineNumber);
        Spotify89BinaryUtil.put32(start, 12, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(start, 16, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(start, 20, tagIndex);
        Spotify89BinaryUtil.put16(start, 24, 20);
        Spotify89BinaryUtil.put16(start, 26, 20);
        Spotify89BinaryUtil.put16(start, 28, 1);
        Spotify89BinaryUtil.put16(start, 30, 0);
        Spotify89BinaryUtil.put16(start, 32, 0);
        Spotify89BinaryUtil.put16(start, 34, 0);

        int attribute = 36;
        Spotify89BinaryUtil.put32(start, attribute, androidNs);
        Spotify89BinaryUtil.put32(start, attribute + 4, nameAttr);
        Spotify89BinaryUtil.put32(start, attribute + 8, valueIndex);
        Spotify89BinaryUtil.put16(start, attribute + 12, 8);
        start[attribute + 14] = 0;
        start[attribute + 15] = 0x03;
        Spotify89BinaryUtil.put32(start, attribute + 16, valueIndex);

        byte[] end = new byte[24];
        Spotify89BinaryUtil.put16(end, 0, 0x0103);
        Spotify89BinaryUtil.put16(end, 2, 16);
        Spotify89BinaryUtil.put32(end, 4, end.length);
        Spotify89BinaryUtil.put32(end, 8, lineNumber);
        Spotify89BinaryUtil.put32(end, 12, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(end, 16, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(end, 20, tagIndex);

        byte[] element = new byte[start.length + end.length];
        System.arraycopy(start, 0, element, 0, start.length);
        System.arraycopy(end, 0, element, start.length, end.length);
        return element;
    }

    private static String readStringAttribute(byte[] data, int startTagOffset,
                                              List<String> strings, String attributeName) {
        int attrStart = Spotify89BinaryUtil.u16(data, startTagOffset + 24);
        int attrSize = Spotify89BinaryUtil.u16(data, startTagOffset + 26);
        int attrCount = Spotify89BinaryUtil.u16(data, startTagOffset + 28);
        int attributes = startTagOffset + 16 + attrStart;
        if (attrSize < 20 || attributes + (long) attrCount * attrSize
                > startTagOffset + Spotify89BinaryUtil.i32(data, startTagOffset + 4)) {
            throw new IllegalArgumentException("Malformed ABRP manifest attributes");
        }
        for (int i = 0; i < attrCount; i++) {
            int a = attributes + i * attrSize;
            int nameIndex = Spotify89BinaryUtil.i32(data, a + 4);
            int valueIndex = Spotify89BinaryUtil.i32(data, a + 16);
            if (nameIndex >= 0 && nameIndex < strings.size()
                    && attributeName.equals(strings.get(nameIndex))
                    && (data[a + 15] & 0xff) == 0x03
                    && valueIndex >= 0 && valueIndex < strings.size()) {
                return strings.get(valueIndex);
            }
        }
        return null;
    }

    private static void verifyDirectManifestExtras(byte[] input, boolean directBydBattery) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool pool = Spotify89BinaryUtil.parsePool(input, rootHeader);
        List<String> strings = pool.strings;
        int[] permissionMatches = new int[BYD_READ_PERMISSIONS.length];
        int providerMatches = 0;
        int offset = firstXmlNodeOffset(input, rootHeader, pool.size);
        int depth = 0;
        int queriesDepth = -1;
        while (offset + 8 <= input.length) {
            int type = Spotify89BinaryUtil.u16(input, offset);
            int size = Spotify89BinaryUtil.i32(input, offset + 4);
            if (size < 8 || offset + size > input.length) {
                throw new IllegalArgumentException("Malformed ABRP manifest while verifying direct telemetry declarations");
            }
            if (type == 0x0102) {
                int tagIndex = Spotify89BinaryUtil.i32(input, offset + 20);
                String tag = tagIndex >= 0 && tagIndex < strings.size() ? strings.get(tagIndex) : null;
                if ("uses-permission".equals(tag)) {
                    String name = readStringAttribute(input, offset, strings, "name");
                    for (int i = 0; i < BYD_READ_PERMISSIONS.length; i++) {
                        if (BYD_READ_PERMISSIONS[i].equals(name)) permissionMatches[i]++;
                    }
                }
                if ("queries".equals(tag) && queriesDepth < 0) {
                    queriesDepth = depth;
                } else if (queriesDepth >= 0 && "package".equals(tag)) {
                    String name = readStringAttribute(input, offset, strings, "name");
                    if (BYD_PROVIDER_PACKAGE.equals(name)) providerMatches++;
                }
                depth++;
            } else if (type == 0x0103) {
                depth--;
            }
            offset += size;
        }
        int expected = directBydBattery ? 1 : 0;
        for (int i = 0; i < permissionMatches.length; i++) {
            if (permissionMatches[i] != expected) {
                throw new IllegalArgumentException("Expected " + expected + " manifest declaration(s) for "
                        + BYD_READ_PERMISSIONS[i] + ", found " + permissionMatches[i]);
            }
        }
        if (providerMatches != expected) {
            throw new IllegalArgumentException("Expected " + expected + " package query for "
                    + BYD_PROVIDER_PACKAGE + ", found " + providerMatches);
        }
    }

    private static byte[] inspectManifest(byte[] input, boolean patch, int patchedTargetSdk) {
        byte[] out = input.clone();
        int rootHeader = Spotify89BinaryUtil.u16(out, 2);
        Spotify89BinaryUtil.Pool pool = Spotify89BinaryUtil.parsePool(out, rootHeader);
        List<String> strings = pool.strings;
        int offset = rootHeader + pool.size;
        if (offset + 8 <= out.length && Spotify89BinaryUtil.u16(out, offset) == 0x0180) {
            offset += Spotify89BinaryUtil.i32(out, offset + 4);
        }

        int sdkMatches = 0;
        int expoMatches = 0;
        while (offset + 8 <= out.length) {
            int type = Spotify89BinaryUtil.u16(out, offset);
            int size = Spotify89BinaryUtil.i32(out, offset + 4);
            if (size < 8 || offset + size > out.length) {
                throw new IllegalArgumentException("Malformed ABRP AndroidManifest.xml");
            }
            if (type == 0x0102) {
                int tagIndex = Spotify89BinaryUtil.i32(out, offset + 20);
                if (tagIndex >= 0 && tagIndex < strings.size()) {
                    String tag = strings.get(tagIndex);
                    int attrStart = Spotify89BinaryUtil.u16(out, offset + 24);
                    int attrSize = Spotify89BinaryUtil.u16(out, offset + 26);
                    int attrCount = Spotify89BinaryUtil.u16(out, offset + 28);
                    int attributes = offset + 16 + attrStart;
                    if (attrSize < 20 || attributes + (long) attrCount * attrSize > offset + size) {
                        throw new IllegalArgumentException("Malformed ABRP manifest attributes");
                    }
                    String metaName = null;
                    int metaValue = -1;
                    for (int i = 0; i < attrCount; i++) {
                        int attribute = attributes + i * attrSize;
                        int nameIndex = Spotify89BinaryUtil.i32(out, attribute + 4);
                        if (nameIndex < 0 || nameIndex >= strings.size()) continue;
                        String attrName = strings.get(nameIndex);
                        if ("uses-sdk".equals(tag) && "targetSdkVersion".equals(attrName)) {
                            int dataType = out[attribute + 15] & 0xff;
                            int value = Spotify89BinaryUtil.i32(out, attribute + 16);
                            int expected = patch ? 36 : patchedTargetSdk;
                            if ((dataType != 0x10 && dataType != 0x11) || value != expected) {
                                throw new IllegalArgumentException("Expected ABRP targetSdkVersion "
                                        + expected + ", found " + value);
                            }
                            if (patch) Spotify89BinaryUtil.put32(out, attribute + 16, patchedTargetSdk);
                            sdkMatches++;
                        } else if ("meta-data".equals(tag) && "name".equals(attrName)) {
                            int valueIndex = Spotify89BinaryUtil.i32(out, attribute + 16);
                            if ((out[attribute + 15] & 0xff) == 0x03
                                    && valueIndex >= 0 && valueIndex < strings.size()) {
                                metaName = strings.get(valueIndex);
                            }
                        } else if ("meta-data".equals(tag) && "value".equals(attrName)) {
                            metaValue = attribute;
                        }
                    }
                    if ("expo.modules.updates.ENABLED".equals(metaName)) {
                        if (metaValue < 0 || (out[metaValue + 15] & 0xff) != 0x12) {
                            throw new IllegalArgumentException("Expected boolean Expo Updates enabled flag");
                        }
                        int expected = patch ? -1 : 0;
                        int value = Spotify89BinaryUtil.i32(out, metaValue + 16);
                        if (value != expected) {
                            throw new IllegalArgumentException("Expected Expo Updates enabled flag "
                                    + expected + ", found " + value);
                        }
                        if (patch) Spotify89BinaryUtil.put32(out, metaValue + 16, 0);
                        expoMatches++;
                    }
                }
            }
            offset += size;
        }
        if (sdkMatches != 1 || expoMatches != 1) {
            throw new IllegalArgumentException("Expected one ABRP target SDK and one Expo Updates flag, found "
                    + sdkMatches + " and " + expoMatches);
        }
        return out;
    }

    static void verifyPatchedHermes(byte[] bundle, boolean autoDayNightDefault, boolean nativeBydTts) throws Exception {
        requireByte(bundle, DISPLAY_MODE_DEFAULT_STRING_ID_OFFSET,
                autoDayNightDefault ? DISPLAY_MODE_AUTOMATIC_ID_LO : DISPLAY_MODE_SYSTEM_ID_LO,
                "patched appearance default string ID low byte");
        requireByte(bundle, DISPLAY_MODE_DEFAULT_STRING_ID_OFFSET + 1,
                autoDayNightDefault ? DISPLAY_MODE_AUTOMATIC_ID_HI : DISPLAY_MODE_SYSTEM_ID_HI,
                "patched appearance default string ID high byte");
        requireBytes(bundle, DEVICE_SETTINGS_TTS_TAIL_OFFSET,
                nativeBydTts ? DEVICE_SETTINGS_TTS_TAIL_BYD : DEVICE_SETTINGS_TTS_TAIL_STOCK);
        for (int offset : PANEL_WIDTH_IMMEDIATE_OFFSETS) {
            requireInt(bundle, offset, PANEL_WIDTH_DP, "patched 360dp panel width");
        }
        requireByte(bundle, CAMERA_LEFT_SOURCE_OFFSET, 16, "centred left camera padding");
        requireByte(bundle, CAMERA_RIGHT_SOURCE_OFFSET, 14, "centred right camera padding");
        requireBytes(bundle, DRIVING_CAMERA_VERTICAL_BIAS_OFFSET - 2, 0x8d, 0x00);
        requireLong(bundle, DRIVING_CAMERA_VERTICAL_BIAS_OFFSET,
                Double.doubleToRawLongBits(0.6), "patched driving camera vertical bias");
        requireBytes(bundle, ANDROID_BOTTOM_MINIMUM_OFFSET,
                0x8b, 0x03, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x18);
        requireBytes(bundle, AUTOMOTIVE_BOTTOM_FALLBACK_OFFSET,
                0x8b, 0x0a, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x18);
        for (int offset : MAP_LANDSCAPE_BOTTOM_OFFSETS) {
            requireBytes(bundle, offset, 0x8b, 0x1c, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x2c);
        }
        requireBytes(bundle, MAP_LANDSCAPE_SPEED_BOTTOM_OFFSET,
                0x8b, 0x17, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x2c);
        requireBytes(bundle, DRIVING_CONTENT_STYLE_KEYS_OFFSET, 0x92, 0x0c, 0x00, 0x00);
        requireBytes(bundle, DRIVING_CONTENT_BOTTOM_PADDING_OFFSET,
                0x8b, 0x0a, BYD_BOTTOM_SAFE_AREA_DP, 0x93, 0x15);
        requireBytes(bundle, DRIVING_MINIMIZED_SNAP_SPACING_OFFSET,
                0x8b, 0x08, 0x10, 0x93, 0x13);
        requireBytes(bundle, DRIVING_MINIMIZED_SNAP_SUBTRACT_OFFSET,
                0x26, 0x04, 0x06, 0x08, 0x93, 0x13);
        requireBytes(bundle, DRIVING_MINIMIZED_SNAP_VALUE_OFFSET,
                0x5a, 0x05, 0x04, 0x00);
        verifyFooter(bundle);
    }

    private static void requireBytes(byte[] data, int offset, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            requireByte(data, offset + i, expected[i], "BYD bottom safe-area patch");
        }
    }

    private static void putBytes(byte[] data, int offset, int... values) {
        for (int i = 0; i < values.length; i++) data[offset + i] = (byte) values[i];
    }

    private static void verifyFooter(byte[] data) throws Exception {
        int footer = data.length - 20;
        byte[] expected = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOf(data, footer));
        for (int i = 0; i < expected.length; i++) {
            if (data[footer + i] != expected[i]) {
                throw new IllegalArgumentException("ABRP Hermes bytecode checksum mismatch");
            }
        }
    }

    private static void repairFooter(byte[] data) throws Exception {
        int footer = data.length - 20;
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOf(data, footer));
        System.arraycopy(digest, 0, data, footer, digest.length);
    }

    private static void requireInt(byte[] data, int offset, int expected, String label) {
        int found = readIntLe(data, offset);
        if (found != expected) {
            throw new IllegalArgumentException("ABRP patch profile mismatch at " + label
                    + ": expected " + expected + ", found " + found);
        }
    }

    private static void requireLong(byte[] data, int offset, long expected, String label) {
        if (readLongLe(data, offset) != expected) {
            throw new IllegalArgumentException("ABRP patch profile mismatch at " + label);
        }
    }

    private static void requireByte(byte[] data, int offset, int expected, String label) {
        int found = data[offset] & 0xff;
        if (found != expected) {
            throw new IllegalArgumentException("ABRP patch profile mismatch at " + label
                    + ": expected " + expected + ", found " + found);
        }
    }

    private static int readIntLe(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16) | (data[offset + 3] << 24);
    }

    private static long readLongLe(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (data[offset + i] & 0xffL);
        return value;
    }

    private static void putIntLe(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLongLe(byte[] data, int offset, long value) {
        for (int i = 0; i < 8; i++) data[offset + i] = (byte) (value >>> (8 * i));
    }
}

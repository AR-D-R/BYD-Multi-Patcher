package com.bydspotifymanager.app;

import java.nio.charset.StandardCharsets;

/** Small, exact-version DEX edits used by the ABRP BYD profile. */
final class AbrpDexUtil {
    private static final String MAIN_APPLICATION = "Lcom/iternio/abrpapp/MainApplication;";
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_FINAL = 0x0010;

    /*
     * ABRP 7.1.7 classes5.dex is protected by AbrpSourceValidator's exact base.apk
     * SHA-256 before these offsets are ever used.  Assertions below additionally
     * verify every original instruction we replace.
     *
     * Why patch native DEX instead of only the Hermes bundle?
     * - NitroGeolocation can otherwise select Google Fused at runtime.
     * - Mapbox Common can independently select GoogleDeviceLocationProvider.
     * - A native route lock cannot be undone by an ABRP JavaScript/config change.
     */
    private static final int ABRP_CLASSES5_LENGTH = 10_516_744;
    private static final int ABRP_CLASSES7_LENGTH = 8_811_812;
    private static final String STOCK_TTS_DESCRIPTOR = "Landroid/speech/tts/TextToSpeech;";
    // Same UTF-8/UTF-16 length as STOCK_TTS_DESCRIPTOR so the DEX string_data item can
    // be changed in place without rebuilding tables or changing instruction indexes.
    private static final String BYD_TTS_ZOE_DESCRIPTOR = "Landroid/speech/tts/TextToSpeeci;";

    // com.mapbox.common.location.LocationServiceImpl.getDeviceLocationProvider(...)
    // code_item instructions start at 0x001aa1b0.
    private static final int MAPBOX_PROVIDER_INSNS = 0x001aa1b0;
    private static final int MAPBOX_ANDROID_PROVIDER_FIELD = 0x05a2; // DeviceLocationProviderType.ANDROID
    private static final int GPS_STRING_INDEX = 0xbcff;             // "gps"

    // com.margelo.nitro.nitrogeolocation.AndroidProviderRouteKt.selectAndroidProviderRoute(...)
    private static final int NITRO_ROUTE_INSNS = 0x002ccd60;
    private static final int NITRO_PLATFORM_ROUTE_FIELD = 0x78d0;   // AndroidProviderRoute.PLATFORM

    // com.margelo.nitro.nitrogeolocation.NitroGeolocation.isGooglePlayServicesAvailable()
    private static final int NITRO_GMS_AVAILABLE_INSNS = 0x002dd3a8;

    // com.margelo.nitro.nitrogeolocation.background.NitroBackgroundLocationController
    // .startNativeLocationUpdates(): original branch at code-item instruction offset +36
    // compares configured provider with ANDROID_PLATFORM; if different it enters the
    // Google Fused PendingIntent path. NOPing the branch makes every background/
    // navigation configuration use startPlatformLocationUpdates() instead.
    private static final int NITRO_BACKGROUND_PROVIDER_BRANCH = 0x002ea4d8;

    private AbrpDexUtil() {}

    static byte[] makeMainApplicationSubclassable(byte[] input) throws Exception {
        if (input == null || input.length < 112
                || input[0] != 'd' || input[1] != 'e' || input[2] != 'x' || input[3] != '\n') {
            throw new IllegalArgumentException("ABRP classes3.dex is not a valid DEX file");
        }
        byte[] out = input.clone();
        int stringIdsSize = i32(out, 56);
        int stringIdsOff = i32(out, 60);
        int typeIdsSize = i32(out, 64);
        int typeIdsOff = i32(out, 68);
        int classDefsSize = i32(out, 96);
        int classDefsOff = i32(out, 100);
        bounds(out, stringIdsOff, stringIdsSize * 4L, "string_ids");
        bounds(out, typeIdsOff, typeIdsSize * 4L, "type_ids");
        bounds(out, classDefsOff, classDefsSize * 32L, "class_defs");

        int stringIndex = -1;
        for (int i = 0; i < stringIdsSize; i++) {
            int dataOff = i32(out, stringIdsOff + i * 4);
            if (MAIN_APPLICATION.equals(readDexString(out, dataOff))) {
                stringIndex = i;
                break;
            }
        }
        if (stringIndex < 0) throw new IllegalArgumentException("ABRP MainApplication descriptor not found in classes3.dex");

        int typeIndex = -1;
        for (int i = 0; i < typeIdsSize; i++) {
            if (i32(out, typeIdsOff + i * 4) == stringIndex) {
                typeIndex = i;
                break;
            }
        }
        if (typeIndex < 0) throw new IllegalArgumentException("ABRP MainApplication type_id not found");

        int matches = 0;
        for (int i = 0; i < classDefsSize; i++) {
            int def = classDefsOff + i * 32;
            if (i32(out, def) != typeIndex) continue;
            int access = i32(out, def + 4);
            if ((access & ACC_PUBLIC) == 0 || (access & ACC_FINAL) == 0) {
                throw new IllegalArgumentException("Unexpected ABRP MainApplication access flags 0x"
                        + Integer.toHexString(access));
            }
            put32(out, def + 4, access & ~ACC_FINAL);
            matches++;
        }
        if (matches != 1) {
            throw new IllegalArgumentException("Expected one ABRP MainApplication class_def, found " + matches);
        }
        out = DexUtil.repair(out);
        verifyMainApplicationSubclassable(out);
        return out;
    }

    /**
     * Permanently routes ABRP 7.1.7's foreground and background location stacks away from
     * Google Play Services on the BYD build.
     *
     * NitroGeolocation:
     *   - isGooglePlayServicesAvailable() => false
     *   - selectAndroidProviderRoute(...) => PLATFORM
     *   PLATFORM uses Android LocationManager and its gps/network/passive provider
     *   order; it does not use com.google.android.gms FusedLocationProviderClient.
     *
     * Mapbox Common:
     *   - force DeviceLocationProviderType.ANDROID
     *   - force provider name "gps"
     *   - skip GoogleDeviceLocationProvider availability probing entirely
     */
    static byte[] forceAndroidLocationManager(byte[] input) throws Exception {
        if (input == null || input.length != ABRP_CLASSES5_LENGTH
                || input[0] != 'd' || input[1] != 'e' || input[2] != 'x' || input[3] != '\n') {
            throw new IllegalArgumentException("Unexpected ABRP 7.1.7 classes5.dex");
        }
        byte[] out = input.clone();

        // Mapbox original prologue:
        // const-string v0,"LocationService"
        // sget-object v1, GoogleDeviceLocationProvider.Companion
        // invoke-virtual {v1}, isAvailable()
        // move-result v1
        // goto ...
        //
        // Keep the tag, but replace the Google probe with:
        // sget-object v5, DeviceLocationProviderType.ANDROID
        // const-string v7, "gps"
        // const/4 v1, 0
        // nop
        // goto ...
        requireWords(out, MAPBOX_PROVIDER_INSNS,
                0x001a, 0x62fe,
                0x0162, 0x05ca, 0x106e, 0x1af8, 0x0001, 0x010a, 0x0928);
        putWord(out, MAPBOX_PROVIDER_INSNS + 4, 0x0562); // sget-object v5, field@BBBB
        putWord(out, MAPBOX_PROVIDER_INSNS + 6, MAPBOX_ANDROID_PROVIDER_FIELD);
        putWord(out, MAPBOX_PROVIDER_INSNS + 8, 0x071a); // const-string v7, string@BBBB
        putWord(out, MAPBOX_PROVIDER_INSNS + 10, GPS_STRING_INDEX);
        putWord(out, MAPBOX_PROVIDER_INSNS + 12, 0x0112); // const/4 v1, #0
        putWord(out, MAPBOX_PROVIDER_INSNS + 14, 0x0000); // nop
        // +16 remains original goto +9 (0x0928)

        // Nitro route selection originally chooses PLATFORM or FUSED depending on
        // requested LocationProvider and Google availability. Return PLATFORM always.
        requireWords(out, NITRO_ROUTE_INSNS,
                0x0062, 0x7a4e, 0x0133, 0x0005, 0x0162, 0x78d0, 0x0111,
                0x0238, 0x0005, 0x0162, 0x78cf, 0x0111, 0x0162, 0x78d0, 0x0111);
        putWord(out, NITRO_ROUTE_INSNS, 0x0062); // sget-object v0, PLATFORM
        putWord(out, NITRO_ROUTE_INSNS + 2, NITRO_PLATFORM_ROUTE_FIELD);
        putWord(out, NITRO_ROUTE_INSNS + 4, 0x0011); // return-object v0
        fillWords(out, NITRO_ROUTE_INSNS + 6, 12, 0x0000);

        // Avoid even probing Google Play Services from NitroGeolocation.  This is
        // intentionally hard false on the BYD profile; currentProviderRoute is also
        // hard PLATFORM above, so no FusedLocationProviderClient is created/used.
        requireWords(out, NITRO_GMS_AVAILABLE_INSNS,
                0x0071, 0x0bf2, 0x0000, 0x000c, 0x2154, 0x7b2e, 0x011f, 0x004d,
                0x206e, 0x0bf3, 0x0010, 0x000a, 0x0039, 0x0004, 0x1012, 0x000f,
                0x0012, 0x000f);
        putWord(out, NITRO_GMS_AVAILABLE_INSNS, 0x0012);     // const/4 v0, #0
        putWord(out, NITRO_GMS_AVAILABLE_INSNS + 2, 0x000f); // return v0
        fillWords(out, NITRO_GMS_AVAILABLE_INSNS + 4, 16, 0x0000);

        // Background/navigation has a separate provider selector from the foreground
        // Nitro route above.  Original startNativeLocationUpdates() does:
        //   sget-object v2, AndroidBackgroundProvider.ANDROID_PLATFORM
        //   if-ne v1, v2, :fused_path
        //   ... startPlatformLocationUpdates() ...
        // NOP the two-code-unit if-ne so PLATFORM is used even if ABRP persisted or
        // requests AUTO/PLAYSERVICES. This also prevents its background location path
        // from creating FusedLocationProviderClient / Google PendingIntent updates.
        requireWords(out, NITRO_BACKGROUND_PROVIDER_BRANCH, 0x2133, 0x000d);
        putWord(out, NITRO_BACKGROUND_PROVIDER_BRANCH, 0x0000);
        putWord(out, NITRO_BACKGROUND_PROVIDER_BRANCH + 2, 0x0000);

        out = DexUtil.repair(out);
        verifyAndroidLocationManagerForced(out);
        return out;
    }


    /**
     * Redirect every ABRP RN-TTS reference to android.speech.tts.TextToSpeech in
     * classes7.dex to one of our API-compatible Cerence facade classes in classes8.dex.
     * The replacement descriptors are deliberately the same length and remain in
     * the original string-table sort window, so method/type indexes stay stable.
     */
    static byte[] redirectTextToSpeechToByd(byte[] input) throws Exception {
        if (input == null || input.length != ABRP_CLASSES7_LENGTH
                || input[0] != 'd' || input[1] != 'e' || input[2] != 'x' || input[3] != '\n') {
            throw new IllegalArgumentException("Unexpected ABRP 7.1.7 classes7.dex");
        }
        byte[] out = replaceDexStringSameLength(input, STOCK_TTS_DESCRIPTOR, BYD_TTS_ZOE_DESCRIPTOR, true);
        out = DexUtil.repair(out);
        verifyTextToSpeechRedirect(out);
        return out;
    }

    static void verifyTextToSpeechRedirect(byte[] dex) {
        if (dex == null || dex.length != ABRP_CLASSES7_LENGTH)
            throw new IllegalArgumentException("Patched ABRP classes7.dex has unexpected length");
        int oldCount = countDexString(dex, STOCK_TTS_DESCRIPTOR);
        int zoeCount = countDexString(dex, BYD_TTS_ZOE_DESCRIPTOR);
        if (oldCount != 0 || zoeCount != 1) {
            throw new IllegalArgumentException("ABRP TTS redirect verification failed; stock="
                    + oldCount + " Zoe=" + zoeCount);
        }
        verifyDexStringSortWindow(dex, BYD_TTS_ZOE_DESCRIPTOR);
    }

    static void verifyTextToSpeechStock(byte[] dex) {
        if (dex == null || dex.length != ABRP_CLASSES7_LENGTH)
            throw new IllegalArgumentException("ABRP classes7.dex has unexpected length");
        int oldCount = countDexString(dex, STOCK_TTS_DESCRIPTOR);
        int zoeCount = countDexString(dex, BYD_TTS_ZOE_DESCRIPTOR);
        if (oldCount != 1 || zoeCount != 0) {
            throw new IllegalArgumentException("ABRP stock TTS verification failed; stock="
                    + oldCount + " Zoe=" + zoeCount);
        }
    }

    private static byte[] replaceDexStringSameLength(byte[] input, String oldValue, String newValue, boolean exactlyOne) {
        if (oldValue.length() != newValue.length()
                || oldValue.getBytes(StandardCharsets.UTF_8).length != newValue.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("DEX in-place string replacement requires equal-length ASCII strings");
        }
        byte[] out = input.clone();
        int size = i32(out, 56);
        int off = i32(out, 60);
        bounds(out, off, size * 4L, "string_ids");
        int matches = 0;
        byte[] replacement = newValue.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < size; i++) {
            int dataOff = i32(out, off + i * 4);
            int[] u = readUleb128(out, dataOff);
            int textOff = u[1];
            String value = readDexString(out, dataOff);
            if (!oldValue.equals(value)) continue;
            System.arraycopy(replacement, 0, out, textOff, replacement.length);
            matches++;
        }
        if (exactlyOne && matches != 1)
            throw new IllegalArgumentException("Expected one DEX string " + oldValue + ", found " + matches);
        return out;
    }

    private static void verifyDexStringSortWindow(byte[] dex, String value) {
        int size = i32(dex, 56);
        int off = i32(dex, 60);
        int index = -1;
        for (int i = 0; i < size; i++) {
            int dataOff = i32(dex, off + i * 4);
            if (value.equals(readDexString(dex, dataOff))) { index = i; break; }
        }
        if (index <= 0 || index >= size - 1) {
            throw new IllegalArgumentException("ABRP TTS descriptor is not in a valid DEX sort window");
        }
        String before = readDexString(dex, i32(dex, off + (index - 1) * 4));
        String after = readDexString(dex, i32(dex, off + (index + 1) * 4));
        if (before.compareTo(value) >= 0 || value.compareTo(after) >= 0) {
            throw new IllegalArgumentException("ABRP TTS descriptor would break DEX string ordering: "
                    + before + " < " + value + " < " + after);
        }
    }

    private static int countDexString(byte[] dex, String value) {
        int size = i32(dex, 56);
        int off = i32(dex, 60);
        bounds(dex, off, size * 4L, "string_ids");
        int matches = 0;
        for (int i = 0; i < size; i++) {
            int dataOff = i32(dex, off + i * 4);
            if (value.equals(readDexString(dex, dataOff))) matches++;
        }
        return matches;
    }

    static void verifyAndroidLocationManagerForced(byte[] dex) {
        if (dex == null || dex.length != ABRP_CLASSES5_LENGTH) {
            throw new IllegalArgumentException("Patched ABRP classes5.dex has unexpected length");
        }

        requireWords(dex, MAPBOX_PROVIDER_INSNS,
                0x001a, 0x62fe,
                0x0562, MAPBOX_ANDROID_PROVIDER_FIELD,
                0x071a, GPS_STRING_INDEX,
                0x0112, 0x0000, 0x0928);

        requireWords(dex, NITRO_ROUTE_INSNS,
                0x0062, NITRO_PLATFORM_ROUTE_FIELD, 0x0011,
                0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
                0x0000, 0x0000, 0x0000, 0x0000, 0x0000);

        requireWords(dex, NITRO_GMS_AVAILABLE_INSNS,
                0x0012, 0x000f,
                0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
                0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
                0x0000, 0x0000);

        requireWords(dex, NITRO_BACKGROUND_PROVIDER_BRANCH, 0x0000, 0x0000);
    }

    static void verifyMainApplicationSubclassable(byte[] dex) {
        int stringIdsSize = i32(dex, 56);
        int stringIdsOff = i32(dex, 60);
        int typeIdsSize = i32(dex, 64);
        int typeIdsOff = i32(dex, 68);
        int classDefsSize = i32(dex, 96);
        int classDefsOff = i32(dex, 100);
        int stringIndex = -1;
        for (int i = 0; i < stringIdsSize; i++) {
            int dataOff = i32(dex, stringIdsOff + i * 4);
            if (MAIN_APPLICATION.equals(readDexString(dex, dataOff))) {
                stringIndex = i;
                break;
            }
        }
        if (stringIndex < 0) throw new IllegalArgumentException("Patched ABRP MainApplication descriptor missing");
        int typeIndex = -1;
        for (int i = 0; i < typeIdsSize; i++) {
            if (i32(dex, typeIdsOff + i * 4) == stringIndex) { typeIndex = i; break; }
        }
        int matches = 0;
        for (int i = 0; i < classDefsSize; i++) {
            int def = classDefsOff + i * 32;
            if (i32(dex, def) == typeIndex) {
                int access = i32(dex, def + 4);
                if ((access & ACC_PUBLIC) == 0 || (access & ACC_FINAL) != 0) {
                    throw new IllegalArgumentException("Patched ABRP MainApplication still has invalid access flags 0x"
                            + Integer.toHexString(access));
                }
                matches++;
            }
        }
        if (matches != 1) throw new IllegalArgumentException("Patched ABRP MainApplication verification found " + matches + " definitions");
    }

    private static void requireWords(byte[] b, int off, int... words) {
        bounds(b, off, words.length * 2L, "DEX instruction assertion");
        for (int i = 0; i < words.length; i++) {
            int actual = u16(b, off + i * 2);
            int expected = words[i] & 0xffff;
            if (actual != expected) {
                throw new IllegalArgumentException("Unexpected ABRP classes5.dex instruction at 0x"
                        + Integer.toHexString(off + i * 2) + ": expected 0x"
                        + Integer.toHexString(expected) + " found 0x" + Integer.toHexString(actual));
            }
        }
    }

    private static void fillWords(byte[] b, int off, int count, int word) {
        for (int i = 0; i < count; i++) putWord(b, off + i * 2, word);
    }

    private static void putWord(byte[] b, int off, int word) {
        bounds(b, off, 2, "DEX instruction write");
        b[off] = (byte) word;
        b[off + 1] = (byte) (word >>> 8);
    }

    private static int u16(byte[] b, int off) {
        bounds(b, off, 2, "DEX u16");
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static String readDexString(byte[] b, int off) {
        if (off < 0 || off >= b.length) return null;
        int[] u = readUleb128(b, off);
        int p = u[1];
        int end = p;
        while (end < b.length && b[end] != 0) end++;
        if (end >= b.length) return null;
        return new String(b, p, end - p, StandardCharsets.UTF_8);
    }

    private static int[] readUleb128(byte[] b, int off) {
        int result = 0;
        int shift = 0;
        int p = off;
        while (p < b.length && shift <= 28) {
            int v = b[p++] & 0xff;
            result |= (v & 0x7f) << shift;
            if ((v & 0x80) == 0) return new int[]{result, p};
            shift += 7;
        }
        throw new IllegalArgumentException("Malformed DEX uleb128");
    }

    private static void bounds(byte[] b, int off, long len, String name) {
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IllegalArgumentException("Malformed DEX " + name);
        }
    }

    private static int i32(byte[] b, int o) {
        if (o < 0 || o + 4 > b.length) throw new IllegalArgumentException("Malformed DEX offset");
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | (b[o + 3] << 24);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }
}

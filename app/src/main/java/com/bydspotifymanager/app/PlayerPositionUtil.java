package com.bydspotifymanager.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Spotify 9.1.82.2161 large-screen LHS hook installer.
 *
 * v0.1.20 keeps Spotify's complete ConstraintLayout/state-machine geometry stock.
 * The runtime hook mirrors the MainLayout render coordinate system, then
 * counter-mirrors each direct child so its local content remains LTR. Because
 * Spotify's own player/navigation rectangles are animated inside that mirrored
 * parent coordinate system, both startup and collapsed/expanded animations run
 * directly on the LHS instead of completing on RHS and being translated later.
 *
 * The only XML edit is replacing the shared direct-child FrameLayout tag in
 * large_main.xml with a FrameLayout-compatible hook class. Only the hook instance
 * carrying display_cutout_placeholder_start activates. Its DEX is added separately
 * as classes16.dex only for LHS builds.
 */
final class PlayerPositionUtil {
    static final String LARGE_MAIN_PATH = "res/layout/large_main.xml";
    static final String HOOK_ASSET = "lhs_hook/classes.dex";
    static final String HOOK_DEX_ENTRY = "classes16.dex";
    private static final String FRAME_LAYOUT = "FrameLayout";
    private static final String HOOK_CLASS = "com.bydspotify.lhshook.LhsLayoutHook";
    // Exact Spotify 9.1.82.2161 id/display_cutout_placeholder_start.
    // 9.1.78 used 0x7f0b04a6; carrying that ID forward made the runtime hook a no-op.
    private static final int ID_DISPLAY_CUTOUT_START_9182 = 0x7f0b0498;

    private PlayerPositionUtil() {}

    static byte[] moveLargePlayerLeft(byte[] stockXml) {
        if (stockXml == null || stockXml.length < 64) {
            throw new IllegalArgumentException("large_main.xml is missing or truncated");
        }
        if (!containsTypedReference(stockXml, ID_DISPLAY_CUTOUT_START_9182)) {
            throw new IllegalStateException("Spotify 9.1.82 display_cutout_placeholder_start (0x7f0b0498) was not found in large_main.xml");
        }
        byte[] out = BinaryStringPoolUtil.replaceFirstString(stockXml, FRAME_LAYOUT, HOOK_CLASS);
        if (out == stockXml || Arrays.equals(out, stockXml)) {
            throw new IllegalStateException("Spotify 9.1 large_main.xml FrameLayout hook target was not found");
        }
        byte[] hook = HOOK_CLASS.getBytes(StandardCharsets.UTF_8);
        if (indexOf(out, hook) < 0) {
            throw new IllegalStateException("LHS runtime hook class was not written into large_main.xml");
        }
        // Exact supported layout has one shared FrameLayout string used by the two
        // cutout placeholders plus the navigation/IME inset placeholders. Replacing that one
        // pool entry intentionally subclasses all three; only the START cutout ID
        // activates the hook at runtime.
        if (indexOf(out, FRAME_LAYOUT.getBytes(StandardCharsets.UTF_8)) >= 0) {
            throw new IllegalStateException("Unexpected additional FrameLayout string in supported large_main.xml");
        }
        return out;
    }

    /**
     * In binary XML a resource/reference attribute stores its 32-bit data value in
     * little-endian form. The exact supported layout is SHA/version-gated by the
     * 9.1.82 engine, so checking the expected placeholder ID here catches a stale
     * runtime-hook constant instead of silently producing an RHS build.
     */
    private static boolean containsTypedReference(byte[] data, int value) {
        byte b0 = (byte) value;
        byte b1 = (byte) (value >>> 8);
        byte b2 = (byte) (value >>> 16);
        byte b3 = (byte) (value >>> 24);
        for (int i = 0; i <= data.length - 4; i++) {
            if (data[i] == b0 && data[i + 1] == b1 && data[i + 2] == b2 && data[i + 3] == b3) return true;
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

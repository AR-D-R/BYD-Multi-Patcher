package com.bydspotifymanager.app;

import java.util.Arrays;

/**
 * Full Compose display scaling for the supported Spotify 9.1.82.2161 base APK.
 *
 * Spotify 9.1.82 moved much more of Home/Library to Compose. Scaling resources.arsc
 * still scales the classic/player surfaces, but Compose hard-coded Dp/TextUnit values
 * use the runtime Density created in Lp/i1m;.a(Context):Lp/xgq;. That is why an enlarged
 * build can show a larger player while Home shortcut tiles and text remain almost stock.
 *
 * For non-100% selections this patch scales that Compose Density's real DisplayMetrics.density
 * by the selected interface factor. It then normalises Compose fontScale to 1.0 so text is
 * enlarged once by the scaled density instead of factor x factor. A linear 1.0 font-scale
 * converter is selected through the factory's existing fallback path.
 *
 * The factory has one 16-bit alignment word between its 37-code-unit instruction body and
 * the next code_item. We consume that existing padding for one mul-float/2addr instruction;
 * no following DEX offsets move. 100% never invokes this helper and stays byte-for-byte stock.
 */
final class Spotify91ComposeScaleUtil {
    private Spotify91ComposeScaleUtil() {}

    // classes9.dex: Lp/i1m;->a(Landroid/content/Context;)Lp/xgq;
    private static final int FACTORY_CODE_OFFSET = 0x308a44;
    private static final int FACTORY_INSNS_SIZE_OFFSET = 0x308a50;
    private static final int FACTORY_INSNS_OFFSET = 0x308a54;
    private static final int FACTORY_FONT_SCALE_READ_OFFSET = 0x308a64;
    private static final int DENSITY_SCALE_INSERT_OFFSET = 0x308a80;
    private static final int STOCK_INSNS_END = 0x308a9e;
    private static final int EXPANDED_INSNS_END = 0x308aa0;

    private static final int STOCK_INSNS_UNITS = 37;
    private static final int EXPANDED_INSNS_UNITS = 38;

    private static final byte[] STOCK_CODE_HEADER = hex(
            "04000100040000000000000025000000");
    private static final byte[] STOCK_FACTORY_INSNS = hex(
            "6e105e0003000c006e10a90000000c005200070022011b61"
          + "6e105e0003000c036e10ac0003000c0352333c00711005dd"
          + "00000c02390207002202d0427020c0b802007040eaef3120"
          + "1101");
    private static final byte[] STOCK_ALIGNMENT_PAD = hex("0000");

    static byte[] applyClasses9Dex(byte[] stockDex, float requestedScale) throws Exception {
        validateScale(requestedScale);
        if (Math.abs(requestedScale - 1.0f) < 0.0001f) return stockDex;

        if (FACTORY_CODE_OFFSET < 0 || EXPANDED_INSNS_END > stockDex.length) {
            throw new IllegalStateException("Spotify 9.1.82 Compose density factory is outside classes9.dex");
        }
        requireBytes(stockDex, FACTORY_CODE_OFFSET, STOCK_CODE_HEADER,
                "Compose density code header");
        requireBytes(stockDex, FACTORY_INSNS_OFFSET, STOCK_FACTORY_INSNS,
                "Compose density factory body");
        requireBytes(stockDex, STOCK_INSNS_END, STOCK_ALIGNMENT_PAD,
                "Compose density alignment pad");

        byte[] out = stockDex.clone();
        byte[] exactScale = constFloatV0(requestedScale);

        // Build the expanded 38-code-unit method body directly from the validated stock body.
        // The original iget of Configuration.fontScale is 2 code units. Replace it with an exact
        // 3-code-unit const v0, #floatBits so every supported slider position is represented exactly.
        // We then add one mul-float/2addr unit before the converter call, but reclaim one unit by
        // replacing the stock converter call + move-result (4 units) with a 3-unit 1.0/null block.
        // Net growth remains one code unit and still fits the stock 16-bit alignment pad.
        byte[] body = new byte[EXPANDED_INSNS_UNITS * 2];
        int stockBeforeFactor = FACTORY_FONT_SCALE_READ_OFFSET - FACTORY_INSNS_OFFSET;
        int stockAfterFactor = stockBeforeFactor + 4; // old iget is 2 code units
        int stockConverter = DENSITY_SCALE_INSERT_OFFSET - FACTORY_INSNS_OFFSET;

        int dst = 0;
        System.arraycopy(STOCK_FACTORY_INSNS, 0, body, dst, stockBeforeFactor);
        dst += stockBeforeFactor;
        System.arraycopy(exactScale, 0, body, dst, exactScale.length);
        dst += exactScale.length;

        System.arraycopy(STOCK_FACTORY_INSNS, stockAfterFactor, body, dst,
                stockConverter - stockAfterFactor);
        dst += stockConverter - stockAfterFactor;

        body[dst++] = (byte) 0xc8; // mul-float/2addr v3, v0
        body[dst++] = 0x03;

        // fontScale=1.0 and converter register v2=null. The existing if-nez then falls through to
        // Spotify/Compose's own LinearFontScaleConverter(1.0) constructor.
        byte[] linearFontScale = hex("1500803f1202");
        System.arraycopy(linearFontScale, 0, body, dst, linearFontScale.length);
        dst += linearFontScale.length;

        int stockAfterConverter = stockConverter + 8; // invoke-static (3) + move-result (1)
        System.arraycopy(STOCK_FACTORY_INSNS, stockAfterConverter, body, dst,
                STOCK_FACTORY_INSNS.length - stockAfterConverter);
        dst += STOCK_FACTORY_INSNS.length - stockAfterConverter;

        if (dst != body.length) {
            throw new IllegalStateException("Spotify 9.1.82 Compose scale body size mismatch: " + dst);
        }
        System.arraycopy(body, 0, out, FACTORY_INSNS_OFFSET, body.length);
        putI32(out, FACTORY_INSNS_SIZE_OFFSET, EXPANDED_INSNS_UNITS);
        return DexUtil.repair(out);
    }

    private static void validateScale(float requestedScale) {
        if (requestedScale < 1.0f || requestedScale > 1.5f) {
            throw new IllegalArgumentException("Unsupported Spotify 9.1.82 Compose scale: " + requestedScale);
        }
    }

    private static byte[] constFloatV0(float value) {
        int bits = Float.floatToIntBits(value);
        return new byte[]{
                0x14, 0x00, // const v0, #+BBBBBBBB (31i)
                (byte) bits, (byte) (bits >>> 8),
                (byte) (bits >>> 16), (byte) (bits >>> 24)
        };
    }

    private static void requireBytes(byte[] data, int offset, byte[] expected, String label) {
        if (offset < 0 || offset + expected.length > data.length
                || !Arrays.equals(Arrays.copyOfRange(data, offset, offset + expected.length), expected)) {
            throw new IllegalStateException("Spotify 9.1.82 " + label + " signature mismatch at 0x"
                    + Integer.toHexString(offset));
        }
    }

    private static void putI32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static byte[] hex(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

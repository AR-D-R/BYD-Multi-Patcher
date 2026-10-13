package com.bydspotifymanager.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;

final class IconBrandingUtil {
    static final String ADAPTIVE_FOREGROUND_XML = "res/drawable/ic_launcher_renaissance_foreground.xml";
    static final String ADAPTIVE_FOREGROUND_PNG = "res/drawable/ic_launcher_renaissance_foreground.png";
    static final String[] DENSITY_ICONS = {
            "res/mipmap-mdpi-v4/ic_launcher_renaissance.webp",
            "res/mipmap-hdpi-v4/ic_launcher_renaissance.webp",
            "res/mipmap-xhdpi-v4/ic_launcher_renaissance.webp",
            "res/mipmap-xxhdpi-v4/ic_launcher_renaissance.webp",
            "res/mipmap-xxxhdpi-v4/ic_launcher_renaissance.webp"
    };
    private static final int STOCK_GREEN = 0xff1ed760;
    private static final String SPOTIFY_PATH_PREFIX = "M55.11,26.02";
    // Extra vector sub-path drawn in the bottom-right of Spotify's 108x108 adaptive foreground.
    private static final String PLUS_PATH = "M87,76L94,76L94,84L102,84L102,91L94,91L94,99L87,99L87,91L79,91L79,84L87,84Z";

    private IconBrandingUtil() {}

    static boolean isDensityIcon(String path) {
        for (String p : DENSITY_ICONS) if (p.equals(path)) return true;
        return false;
    }

    static boolean isAdaptiveForeground(String path) {
        return ADAPTIVE_FOREGROUND_XML.equals(path);
    }

    static byte[] brandDensityIcon(byte[] data, int hueDegrees, boolean plusBadge) throws IOException {
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (src == null) throw new IOException("Cannot decode Spotify launcher icon");
        Bitmap out = renderPreview(src, hueDegrees, plusBadge);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + 2048);
        if (!out.compress(Bitmap.CompressFormat.WEBP, 100, bos)) {
            if (out != src) out.recycle();
            src.recycle();
            throw new IOException("Cannot encode Spotify launcher icon");
        }
        if (out != src) out.recycle();
        src.recycle();
        return bos.toByteArray();
    }

    static byte[] brandAdaptiveForeground(byte[] data, int hueDegrees, boolean plusBadge) {
        byte[] out = data;
        int deg = normaliseHue(hueDegrees);
        if (deg != 0) {
            byte[] recolored = data.clone();
            int replacement = rotateHue(STOCK_GREEN, deg);
            int hits = 0;
            for (int i = 0; i + 4 <= recolored.length; i++) {
                if (i32(recolored, i) == STOCK_GREEN) {
                    putI32(recolored, i, replacement);
                    hits++;
                    i += 3;
                }
            }
            if (hits == 1) out = recolored;
        }
        if (plusBadge) {
            out = BinaryStringPoolUtil.appendToFirstStringStartingWith(out, SPOTIFY_PATH_PREFIX, PLUS_PATH);
        }
        return out;
    }

    static byte[] buildAdaptiveForegroundPng(byte[] xxxhdpiIcon, int hueDegrees, boolean plusBadge) throws IOException {
        Bitmap src = BitmapFactory.decodeByteArray(xxxhdpiIcon, 0, xxxhdpiIcon.length);
        if (src == null) throw new IOException("Cannot decode Spotify launcher icon for adaptive foreground");
        Bitmap branded = renderPreview(src, hueDegrees, plusBadge);
        Bitmap scaled = Bitmap.createScaledBitmap(branded, 240, 240, true);
        Bitmap canvas = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvas);
        c.drawBitmap(scaled, (432 - 240) / 2f, (432 - 240) / 2f, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean ok = canvas.compress(Bitmap.CompressFormat.PNG, 100, bos);
        if (branded != src) branded.recycle();
        src.recycle();
        scaled.recycle();
        canvas.recycle();
        if (!ok) throw new IOException("Cannot encode Spotify adaptive launcher icon");
        return bos.toByteArray();
    }

    static byte[] retargetAdaptiveForegroundResource(byte[] resources) {
        byte[] oldUtf8 = ADAPTIVE_FOREGROUND_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] newUtf8 = ADAPTIVE_FOREGROUND_PNG.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = IoUtil.replaceSameLength(resources, oldUtf8, newUtf8);
        byte[] oldUtf16 = ADAPTIVE_FOREGROUND_XML.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] newUtf16 = ADAPTIVE_FOREGROUND_PNG.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        return IoUtil.replaceSameLength(out, oldUtf16, newUtf16);
    }

    static Bitmap loadStockPreview(File stockApk) {
        if (stockApk == null || !stockApk.isFile()) return null;
        try (ZipFile z = new ZipFile(stockApk)) {
            for (int i = DENSITY_ICONS.length - 1; i >= 0; i--) {
                java.util.zip.ZipEntry e = z.getEntry(DENSITY_ICONS[i]);
                if (e == null) continue;
                byte[] bytes = IoUtil.readAll(z.getInputStream(e));
                Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (src != null) return src;
            }
        } catch (Exception ignored) {
        }
        return null;
    }


    /** Loads the best available stock launcher icon from base.apk inside an APKM bundle. */
    static Bitmap loadStockPreviewFromApkm(File apkm) {
        if (apkm == null || !apkm.isFile()) return null;
        try (ZipFile outer = new ZipFile(apkm)) {
            ZipEntry base = outer.getEntry("base.apk");
            if (base == null) return null;
            Bitmap best = null;
            int bestRank = -1;
            try (ZipInputStream zin = new ZipInputStream(outer.getInputStream(base))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    int rank = -1;
                    for (int i = 0; i < DENSITY_ICONS.length; i++) if (DENSITY_ICONS[i].equals(name)) { rank = i; break; }
                    if (rank <= bestRank) continue;
                    byte[] bytes = IoUtil.readAll(zin);
                    Bitmap candidate = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (candidate != null) {
                        if (best != null && !best.isRecycled()) best.recycle();
                        best = candidate; bestRank = rank;
                    }
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    static Bitmap renderPreview(Bitmap stock, int hueDegrees, boolean plusBadge) {
        if (stock == null) return null;
        Bitmap base = shiftHue(stock, hueDegrees);
        if (!plusBadge) return base;
        Bitmap mutable = base.copy(Bitmap.Config.ARGB_8888, true);
        if (base != stock) base.recycle();
        drawPlusBadge(mutable);
        return mutable;
    }

    private static void drawPlusBadge(Bitmap bitmap) {
        Canvas c = new Canvas(bitmap);
        float w = bitmap.getWidth(), h = bitmap.getHeight();
        float cx = w * 0.78f, cy = h * 0.78f;
        float r = Math.min(w, h) * 0.18f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xe6000000);
        c.drawCircle(cx, cy, r, bg);
        Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        plus.setColor(Color.WHITE);
        plus.setStrokeWidth(Math.max(2f, r * 0.34f));
        plus.setStrokeCap(Paint.Cap.ROUND);
        float arm = r * 0.55f;
        c.drawLine(cx - arm, cy, cx + arm, cy, plus);
        c.drawLine(cx, cy - arm, cx, cy + arm, plus);
    }

    private static Bitmap shiftHue(Bitmap src, int hueDegrees) {
        int deg = normaliseHue(hueDegrees);
        if (deg == 0) return src.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap mutable = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = mutable.getWidth(), h = mutable.getHeight();
        int[] pixels = new int[w * h];
        mutable.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = Color.alpha(c);
            if (a == 0) continue;
            Color.colorToHSV(c, hsv);
            if (hsv[1] > 0.05f) hsv[0] = (hsv[0] + deg) % 360f;
            pixels[i] = Color.HSVToColor(a, hsv);
        }
        mutable.setPixels(pixels, 0, w, 0, 0, w, h);
        return mutable;
    }

    private static int rotateHue(int color, int hueDegrees) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + normaliseHue(hueDegrees)) % 360f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    static int normaliseHue(int degrees) {
        int v = degrees % 360;
        return v < 0 ? v + 360 : v;
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | (b[o + 3] << 24);
    }

    private static void putI32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }
}

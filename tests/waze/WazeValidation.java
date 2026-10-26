package com.bydspotifymanager.app;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.zip.*;
public class WazeValidation {
    public static void main(String[] args) throws Exception {
        File bundle = new File(args[0]);
        var validation = WazePatchEngine.validateSource(bundle);
        if (!validation.ok || !validation.tested) throw new AssertionError(validation.detail);
        System.out.println("Exact supplied Waze bundle: " + validation.displayVersion() + ", tested=" + validation.tested);
        Path base = Path.of(args[1], "base.stock.apk");
        try (ZipFile z = new ZipFile(bundle); var in = z.getInputStream(z.getEntry("base.apk"))) {
            Files.copy(in, base, StandardCopyOption.REPLACE_EXISTING);
        }
        Field f = WazePatchEngine.class.getDeclaredField("STABLE_PROFILE");
        f.setAccessible(true);
        Object profile = f.get(null);
        Method m = WazePatchEngine.class.getDeclaredMethod("patchBaseApk", File.class, File.class, profile.getClass(), boolean.class, boolean.class, boolean.class, WazePatchEngine.Progress.class);
        m.setAccessible(true);
        File patched = Path.of(args[1], "base.patched.apk").toFile();
        File patchedNoEv = Path.of(args[1], "base.patched.noev.apk").toFile();
        File patchedMicKept = Path.of(args[1], "base.patched.mic-kept.apk").toFile();

        m.invoke(null, base.toFile(), patched, profile, false, true, true, (WazePatchEngine.Progress)System.out::println);
        assertReportBottomClearance(patched);
        System.out.println("Production patchBaseApk completed with EV + CCS2 and microphone removal enabled; force fallback disabled.");
        m.invoke(null, base.toFile(), patchedNoEv, profile, false, false, true, (WazePatchEngine.Progress)System.out::println);
        assertReportBottomClearance(patchedNoEv);
        System.out.println("Production patchBaseApk completed with EV disabled and microphone removal enabled; force fallback disabled.");
        m.invoke(null, base.toFile(), patchedMicKept, profile, false, true, false, (WazePatchEngine.Progress)System.out::println);
        assertReportBottomClearance(patchedMicKept);
        System.out.println("Production patchBaseApk completed with microphone removal disabled; force fallback disabled.");
    }

    private static void assertReportBottomClearance(File apk) throws Exception {
        try (ZipFile z = new ZipFile(apk); var in = z.getInputStream(z.getEntry("classes7.dex"))) {
            byte[] dex = in.readAllBytes();

            // SubmenuButtons: the old 16dp padding slot is repurposed as y=-56dp offset.
            int buttons = 0x4ae72e;
            int yOffset = buttons + 42;
            if (dex.length <= yOffset + 3
                    || (dex[yOffset] & 0xff) != 0x15
                    || (dex[yOffset + 1] & 0xff) != 0x0a
                    || (dex[yOffset + 2] & 0xff) != 0x60
                    || (dex[yOffset + 3] & 0xff) != 0xc2) {
                throw new AssertionError("Waze report action row was not moved upward by 56dp");
            }

            // ItemsGrid: sparse one-row menus must use the same weighted-height path as full menus.
            int sparseBranch = 0x4aebe2;
            if (dex.length <= sparseBranch + 3
                    || (dex[sparseBranch] & 0xff) != 0x29
                    || (dex[sparseBranch + 1] & 0xff) != 0x00
                    || (dex[sparseBranch + 2] & 0xff) != 0x10
                    || (dex[sparseBranch + 3] & 0xff) != 0x00) {
                throw new AssertionError("Waze sparse report grid was not bottom-anchored");
            }
        }
    }

}

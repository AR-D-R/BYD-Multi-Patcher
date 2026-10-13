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
        m.invoke(null, base.toFile(), Path.of(args[1], "base.patched.apk").toFile(), profile, false, true, true, (WazePatchEngine.Progress)System.out::println);
        System.out.println("Production patchBaseApk completed with EV + CCS2 and microphone removal enabled; force fallback disabled.");
        m.invoke(null, base.toFile(), Path.of(args[1], "base.patched.noev.apk").toFile(), profile, false, false, true, (WazePatchEngine.Progress)System.out::println);
        System.out.println("Production patchBaseApk completed with EV disabled and microphone removal enabled; force fallback disabled.");
        m.invoke(null, base.toFile(), Path.of(args[1], "base.patched.mic-kept.apk").toFile(), profile, false, true, false, (WazePatchEngine.Progress)System.out::println);
        System.out.println("Production patchBaseApk completed with microphone removal disabled; force fallback disabled.");
    }
}

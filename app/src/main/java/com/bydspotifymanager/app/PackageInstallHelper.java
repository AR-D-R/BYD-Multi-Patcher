package com.bydspotifymanager.app;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.*;
import java.util.*;

final class PackageInstallHelper {
    static void install(Context context, File apk, File dm, String packageName) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            write(session, apk, "base.apk");
            if (dm != null) write(session, dm, "base.dm");
            commit(context, session, id, packageName);
        } finally {
            session.close();
        }
    }

    /** Installs one base APK plus all split APKs in a single Android PackageInstaller session. */
    static void installSplitApks(Context context, List<File> apks, String packageName) throws Exception {
        installSplitApks(context, apks, null, packageName);
    }

    /** Installs a split bundle and optionally attaches DexMetadata to base.apk. */
    static void installSplitApks(Context context, List<File> apks, File baseDm, String packageName) throws Exception {
        if (apks == null || apks.isEmpty()) throw new IllegalArgumentException("No APKs supplied for split install");
        boolean hasBase = false;
        long total = 0;
        for (File f : apks) {
            if (f == null || !f.isFile()) throw new FileNotFoundException("Missing split APK: " + f);
            if ("base.apk".equals(f.getName())) hasBase = true;
            total += f.length();
        }
        if (baseDm != null) {
            if (!baseDm.isFile()) throw new FileNotFoundException("Missing base DexMetadata: " + baseDm);
            total += baseDm.length();
        }
        if (!hasBase) throw new IllegalArgumentException("Split install is missing base.apk");

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        params.setSize(total);
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            List<File> ordered = new ArrayList<>(apks);
            ordered.sort((a, b) -> {
                if ("base.apk".equals(a.getName())) return -1;
                if ("base.apk".equals(b.getName())) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File apk : ordered) write(session, apk, apk.getName());
            if (baseDm != null) write(session, baseDm, "base.dm");
            commit(context, session, id, packageName);
        } finally {
            session.close();
        }
    }

    private static void commit(Context context, PackageInstaller.Session session, int id, String packageName) throws Exception {
        Intent result = new Intent(context, InstallResultReceiver.class)
                .setAction("com.bydspotifymanager.app.INSTALL_RESULT")
                .putExtra("targetPackage", packageName);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, id, result, flags);
        session.commit(pending.getIntentSender());
    }

    private static void write(PackageInstaller.Session session, File file, String name) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             OutputStream out = session.openWrite(name, 0, file.length())) {
            IoUtil.copy(in, out);
            session.fsync(out);
        }
    }
}

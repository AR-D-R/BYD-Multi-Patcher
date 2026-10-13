package com.bydspotifymanager.app;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class InstallResultReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_FINISHED = "com.bydspotifymanager.app.INSTALL_FINISHED";

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }

        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String targetPackage = intent.getStringExtra("targetPackage");
        context.getSharedPreferences("manager_settings", Context.MODE_PRIVATE).edit()
                .putInt("last_install_status", status)
                .putString("last_install_message", msg == null ? "" : msg)
                .putString("last_install_package", targetPackage == null ? "" : targetPackage)
                .apply();
        if (status == PackageInstaller.STATUS_SUCCESS) {
            String app = BuildConfigData.ABRP_PACKAGE.equals(targetPackage) ? "ABRP"
                    : (BuildConfigData.WAZE_PACKAGE.equals(targetPackage) ? "Waze" : "SpotifyPlus");
            Toast.makeText(context, app + " installed successfully.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, "Install failed: " + status + (msg == null ? "" : " · " + msg), Toast.LENGTH_LONG).show();
        }

        Intent refresh = new Intent(ACTION_INSTALL_FINISHED)
                .setPackage(context.getPackageName())
                .putExtra("status", status)
                .putExtra("message", msg == null ? "" : msg)
                .putExtra("targetPackage", targetPackage == null ? "" : targetPackage);
        context.sendBroadcast(refresh);
    }
}

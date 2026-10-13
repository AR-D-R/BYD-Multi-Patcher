package com.iternio.abrpapp.aivi;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * Compatibility context for the four read-only permission checks in BYD's
 * Statistic and Instrument client SDKs. This affects checks made on this
 * Context object only; it cannot grant an Android or Binder-side permission.
 */
final class BydReadContext extends ContextWrapper {
    BydReadContext(Context base) {
        super(base);
    }

    private static boolean isBatteryReadPermission(String permission) {
        if (permission == null) return false;
        switch (permission) {
            case "android.permission.BYDAUTO_STATISTIC_GET":
            case "android.permission.BYDAUTO_STATISTIC_COMMON":
            case "android.permission.BYDAUTO_INSTRUMENT_GET":
            case "android.permission.BYDAUTO_INSTRUMENT_COMMON":
                return true;
            default:
                return false;
        }
    }

    @Override public int checkPermission(String permission, int pid, int uid) {
        return isBatteryReadPermission(permission) ? PackageManager.PERMISSION_GRANTED
                : super.checkPermission(permission, pid, uid);
    }

    @Override public int checkCallingOrSelfPermission(String permission) {
        return isBatteryReadPermission(permission) ? PackageManager.PERMISSION_GRANTED
                : super.checkCallingOrSelfPermission(permission);
    }

    @Override public int checkCallingPermission(String permission) {
        return isBatteryReadPermission(permission) ? PackageManager.PERMISSION_GRANTED
                : super.checkCallingPermission(permission);
    }

    @Override public int checkSelfPermission(String permission) {
        return isBatteryReadPermission(permission) ? PackageManager.PERMISSION_GRANTED
                : super.checkSelfPermission(permission);
    }

    @Override public void enforcePermission(String permission, int pid, int uid, String message) {
        if (!isBatteryReadPermission(permission)) super.enforcePermission(permission, pid, uid, message);
    }

    @Override public void enforceCallingPermission(String permission, String message) {
        if (!isBatteryReadPermission(permission)) super.enforceCallingPermission(permission, message);
    }

    @Override public void enforceCallingOrSelfPermission(String permission, String message) {
        if (!isBatteryReadPermission(permission)) super.enforceCallingOrSelfPermission(permission, message);
    }
}

package com.iternio.abrpapp.aivi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal, read-only probe of BYD's vehicle battery percentage on DiLink head units. */
final class BydBatteryReader {
    private static final String OEM_PACKAGE = "com.byd.data.collect";
    private static final Pattern BYD_PERMISSION =
            Pattern.compile("(?:android\\.permission\\.)?BYDAUTO_[A-Z_]+");

    static final class Reading {
        final Double percent;
        final String detail;

        Reading(Double percent, String detail) {
            this.percent = percent;
            this.detail = detail;
        }
    }

    private BydBatteryReader() {}

    static Reading read(Context context) {
        String[][] probes = {
                {"Statistic", "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
                        "getElecPercentageValue", "getSOCBatteryPercentage"},
                {"Instrument", "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
                        "getBatteryPercent"}
        };
        StringBuilder failures = new StringBuilder();
        for (String[] probe : probes) {
            Reading result = readProbe(context, probe);
            if (result.percent != null) return result;
            if (failures.length() > 0) failures.append('\n');
            failures.append(result.detail);
        }
        return new Reading(null, failures.toString());
    }


    private static Reading readProbe(Context context, String[] probe) {
        String label = probe[0];
        Class<?> type;
        try {
            type = loadOemClass(context, probe[1]);
        } catch (Throwable e) {
            return failure(label, "loading BYD interface", e);
        }

        Object device;
        try {
            // BYD's client SDK can check permissions on the Context supplied here
            // before performing a read. Keep this compatibility context limited to
            // the two read-only interfaces; the OS still enforces Binder permissions.
            Context readContext = new BydReadContext(context.getApplicationContext());
            device = type.getMethod("getInstance", Context.class)
                    .invoke(null, readContext);
        } catch (Throwable e) {
            return failure(label, "connecting to BYD", e);
        }
        if (device == null) return new Reading(null, label + ": BYD returned no device");

        String getterFailure = null;
        boolean securityFailure = false;
        boolean getterFound = false;
        for (int i = 2; i < probe.length; i++) {
            try {
                Method getter = type.getMethod(probe[i]);
                getterFound = true;
                Object raw = getter.invoke(device);
                if (!(raw instanceof Number)) {
                    if (!securityFailure) getterFailure = label + ": " + probe[i] + " returned no number";
                    continue;
                }
                double value = ((Number) raw).doubleValue();
                // Zero may be an uninitialised value; never inject a false empty-battery reading.
                if (Double.isFinite(value) && value > 0.0 && value <= 100.0) {
                    return new Reading(value, label + " battery read successfully");
                }
                if (!securityFailure) getterFailure = label + ": " + probe[i] + " returned an invalid level";
            } catch (NoSuchMethodException ignored) {
                // Firmware variants expose different read-only getter names.
            } catch (Throwable e) {
                Reading failed = failure(label, "reading " + probe[i], e);
                boolean denied = unwrap(e) instanceof SecurityException;
                if (getterFailure == null || denied) {
                    getterFailure = failed.detail;
                    securityFailure = denied;
                }
            }
        }
        if (getterFailure != null) return new Reading(null, getterFailure);
        return new Reading(null, label + (getterFound
                ? ": battery getter returned no value" : ": battery getter not found"));
    }

    private static Reading failure(String label, String stage, Throwable error) {
        Throwable cause = unwrap(error);
        String reason;
        if (cause instanceof SecurityException) {
            String message = cause.getMessage();
            if ("BYD data provider is not a system app".equals(message)) {
                reason = "BYD provider package is not marked as a system app";
            } else {
                Matcher permission = BYD_PERMISSION.matcher(message == null ? "" : message);
                reason = permission.find() ? "permission denied: " + permission.group()
                        : "permission/security check denied";
            }
        } else if (cause instanceof ClassNotFoundException) {
            reason = cause.getCause() instanceof PackageManager.NameNotFoundException
                    ? "BYD provider app not found" : "BYD class not found";
        } else {
            reason = cause.getClass().getSimpleName();
        }
        return new Reading(null, label + ": " + stage + " failed (" + reason + ")");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof InvocationTargetException || cause instanceof ExceptionInInitializerError)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Class<?> loadOemClass(Context context, String name) throws Exception {
        try {
            return Class.forName(name, true, context.getClassLoader());
        } catch (ClassNotFoundException missing) {
            // On some units the OEM SDK lives in a preinstalled system APK rather than
            // the boot class path. Only load code from the verified system package.
            ApplicationInfo info;
            try {
                info = context.getPackageManager().getApplicationInfo(OEM_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException e) {
                throw new ClassNotFoundException(name, e);
            }
            if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                throw new SecurityException("BYD data provider is not a system app");
            }
            Context oem = context.createPackageContext(OEM_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            return Class.forName(name, true, oem.getClassLoader());
        }
    }
}

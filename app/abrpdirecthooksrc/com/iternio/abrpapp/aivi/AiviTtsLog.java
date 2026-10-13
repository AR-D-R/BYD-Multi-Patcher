package com.iternio.abrpapp.aivi;

import android.content.Context;

/** Production no-op trace shim: the v2 ABRP backend emits no runtime logs. */
final class AiviTtsLog {
    private AiviTtsLog() {}
    static void setContext(Context value) {}
    static void i(String message) {}
    static void w(String message) {}
    static void w(String message, Throwable error) {}
    static void e(String message, Throwable error) {}
    static void flush() {}
}

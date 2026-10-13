package com.iternio.abrpapp;

import com.iternio.abrpapp.aivi.AiviDirectTelemetry;

/**
 * ABRP Application shim used only when the "Direct BYD battery data" patch option is enabled.
 * The real MainApplication is supplied by ABRP classes3.dex.
 */
public final class DirectTelemetryApplication extends MainApplication {
    @Override public void onCreate() {
        // Install before ABRP creates React Native's shared OkHttp client. This changes only
        // the /2/tlm SSE subscription; all other ABRP networking is built normally.
        try {
            AiviDirectTelemetry.install(this);
        } catch (Throwable ignored) {
            // Production build intentionally does not emit ABRP runtime diagnostics.
        }
        super.onCreate();
    }
}

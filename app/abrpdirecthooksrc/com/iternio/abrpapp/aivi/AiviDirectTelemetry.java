package com.iternio.abrpapp.aivi;

import android.content.Context;
import android.os.Build;

import com.facebook.react.modules.network.OkHttpClientFactory;
import com.facebook.react.modules.network.OkHttpClientProvider;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/**
 * Local ABRP telemetry bridge.
 *
 * ABRP 7.1.7 already opens an SSE subscription to /2/tlm?vehicleIds=... and then feeds
 * those points through its normal processMessageQueue -> updateVehicleTelemetry path.
 * We replace only that stream with locally generated SOC points, so no telemetry token,
 * companion APK or upload to Iternio is needed for battery percentage.
 */
public final class AiviDirectTelemetry {
    private static final long UPDATE_INTERVAL_MS = 2_000L;

    // Proof-of-concept value for BlueStacks / normal Android emulators. Change this to
    // 41.0 or 18.0 for the reducer/planner test without needing BYD hardware.
    public static final double EMULATOR_TEST_SOC = 73.0;

    private static final Pattern VEHICLE_IDS = Pattern.compile("(?:[?&])vehicleIds=([^&#]+)");
    private static volatile boolean installed;

    private AiviDirectTelemetry() {}

    public static synchronized void install(Context context) {
        if (installed) return;
        final Context app = context.getApplicationContext();

        OkHttpClientProvider.setOkHttpClientFactory(new OkHttpClientFactory() {
            @Override public OkHttpClient createNewNetworkModuleClient() {
                return OkHttpClientProvider.createClientBuilder(app)
                        .addInterceptor(new TelemetryInterceptor(app))
                        .build();
            }
        });
        installed = true;
    }

    private static final class TelemetryInterceptor implements Interceptor {
        private final Context context;

        TelemetryInterceptor(Context context) {
            this.context = context;
        }

        @Override public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            String url = request.url().toString();
            if (!isTelemetryStream(request, url)) return chain.proceed(request);

            List<Long> vehicleIds = parseVehicleIds(url);
            if (vehicleIds.isEmpty()) {
                return chain.proceed(request);
            }

            if (vehicleIds.size() != 1) {
                return chain.proceed(request);
            }

            PipedInputStream input = new PipedInputStream(16 * 1024);
            PipedOutputStream output = new PipedOutputStream(input);
            Thread writer = new Thread(new StreamWriter(context, output, vehicleIds),
                    "BYD-ABRP-Telemetry");
            writer.setDaemon(true);
            writer.start();

            BufferedSource source = Okio.buffer(Okio.source(input));
            ResponseBody body = ResponseBody.create(null, -1L, source);
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "keep-alive")
                    .body(body)
                    .build();
        }
    }

    private static final class StreamWriter implements Runnable {
        private final Context context;
        private final PipedOutputStream output;
        private final List<Long> vehicleIds;

        StreamWriter(Context context, PipedOutputStream output, List<Long> vehicleIds) {
            this.context = context;
            this.output = output;
            this.vehicleIds = vehicleIds;
        }

        @Override public void run() {
            try {
                // An SSE comment opens the stream immediately even if the BYD provider is not ready yet.
                writeUtf8(": byd direct telemetry\n\n");
                while (!Thread.currentThread().isInterrupted()) {
                    SocReading reading = readSoc(context);
                    if (reading.percent != null) {
                        String time = utcNow();
                        double fraction = reading.percent / 100.0;
                        for (Long vehicleId : vehicleIds) {
                            String json = "{\"vehicleId\":" + vehicleId
                                    + ",\"soc\":{\"frac\":" + formatFraction(fraction)
                                    + ",\"time\":\"" + time
                                    + "\",\"provider\":\"TLM_API\"}}";
                            writeUtf8("data: " + json + "\n\n");
                        }
                    } else {
                        // Keep the SSE alive without making stale telemetry look fresh.
                        writeUtf8(": byd waiting for local soc\n\n");
                    }
                    Thread.sleep(UPDATE_INTERVAL_MS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException closed) {
                // Normal when ABRP closes or replaces the telemetry subscription.
            } catch (Throwable error) {
                // Keep the production hook silent; ABRP can recreate the stream if needed.
            } finally {
                try { output.close(); } catch (IOException ignored) {}
            }
        }

        private void writeUtf8(String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static SocReading readSoc(Context context) {
        if (isEmulator()) {
            return new SocReading(EMULATOR_TEST_SOC, "emulator fixed-SOC proof value");
        }
        BydBatteryReader.Reading byd = BydBatteryReader.read(context);
        return new SocReading(byd.percent, byd.detail);
    }

    private static boolean isTelemetryStream(Request request, String url) {
        if (!"GET".equalsIgnoreCase(request.method())) return false;
        if (!url.contains("vehicleIds=")) return false;
        // Version-locked ABRP 7.1.7 uses the v2 Iternio telemetry stream. Match both the
        // canonical public URL and a path-only equivalent in case the app changes host aliases.
        return url.contains("/2/tlm?") || url.contains("/2/tlm&");
    }

    private static List<Long> parseVehicleIds(String url) {
        ArrayList<Long> result = new ArrayList<>();
        Matcher matcher = VEHICLE_IDS.matcher(url);
        if (!matcher.find()) return result;
        String raw = matcher.group(1)
                .replace("%2C", ",")
                .replace("%2c", ",");
        for (String value : raw.split(",")) {
            try {
                long id = Long.parseLong(value.trim());
                if (id > 0 && !result.contains(id)) result.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static boolean isEmulator() {
        String all = (safe(Build.MANUFACTURER) + " " + safe(Build.BRAND) + " "
                + safe(Build.MODEL) + " " + safe(Build.PRODUCT) + " "
                + safe(Build.HARDWARE) + " " + safe(Build.FINGERPRINT)).toLowerCase(Locale.US);
        if (all.contains("bluestacks") || all.contains("bst") || all.contains("emulator")
                || all.contains("goldfish") || all.contains("ranchu") || all.contains("vbox")
                || all.contains("sdk_gphone") || all.contains("generic_x86")) {
            return true;
        }
        // BlueStacks can spoof a physical phone model/fingerprint, but its Android VM still
        // exposes an x86 ABI. BYD production head units for this patch are ARM64.
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi != null && abi.toLowerCase(Locale.US).contains("x86")) return true;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String formatFraction(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static final class SocReading {
        final Double percent;
        final String detail;

        SocReading(Double percent, String detail) {
            this.percent = percent;
            this.detail = detail;
        }
    }
}

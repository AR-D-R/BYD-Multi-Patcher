package com.iternio.abrpapp.aivi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared BYD/Telenav Cerence Ark implementation behind the tiny
 * android.speech.tts facade class used by patched ABRP 7.1.7.  ABRP continues to use its stock RN-TTS
 * module; the DEX type descriptor is redirected to this class at patch time.
 *
 * This class loads the Cerence Ark implementation and data already installed
 * by BYD/Telenav Navigation, then plays ABRP's normal guidance strings through
 * the BYD navigation stream.  No Google services or Android TTS engine are
 * required.
 */
public class CerenceTtsBase {
    private static final String NAV_PACKAGE = "com.telenav.app.arp";
    private static final String OTA_DATA = "/navi/ota_common_res/tts/TelenavTTS/";
    private static final String ARK_ASSISTANT = "cerence.ark.assistant.api.ArkAssistant";
    private static final String ARK_FRAMEWORK = "cerence.ark.assistant.framework.impl.ArkFramework";
    private static final String CONFIG_BUILDER = "cerence.ark.assistant.framework.data.Config$Builder";
    private static final String FRAMEWORK_LISTENER = "cerence.ark.assistant.framework.FrameworkMessageListener";
    private static final String RESULT_CALLBACK = "cerence.ark.assistant.common.callback.IResultCallback";
    private static final String PROMPTER_CALLBACK = "cerence.ark.assistant.framework.prompter.PrompterCallback";
    private static final String UTILS = "cerence.ark.assistant.library.Utils";
    private static final String LOGGER = "cerence.ark.assistant.common.Logger";
    private static final String LOGGER_CONFIG = "cerence.ark.assistant.common.Logger$Config";

    private static final String ZOE_ID = "enu_zoe-ml_embedded-pro_2-2-2_female";
    // ABRP fresh-state aliases. These exact strings already exist in ABRP 7.1.7's
    // Hermes string table, so the patch can preselect the BYD engine without expanding HBC.
    // "en" remains the fresh-install default alias when an English voice is actually
    // installed, but every Cerence voice reported by the car is exposed to ABRP.
    private static final String ABRP_ENGINE_ALIAS = "native";
    private static final String ABRP_VOICE_ALIAS = "en";

    private static final Object LOCK = new Object();
    private static final ArrayList<TextToSpeech.OnInitListener> INIT_LISTENERS = new ArrayList<>();
    private static volatile boolean initializing;
    private static volatile boolean ready;
    private static volatile Throwable initError;
    private static volatile Context appContext;
    private static volatile Context hostContext;
    private static volatile ClassLoader navLoader;
    private static volatile Object framework;
    private static volatile Object prompter;
    private static volatile Class<?> prompterCallbackClass;
    private static volatile String selectedVoiceId;
    private static volatile String selectedVoiceAlias = ABRP_VOICE_ALIAS;
    private static volatile String requestedVoiceAlias;
    private static volatile Locale requestedLocale;
    private static volatile List<CerenceVoiceInfo> availableCerenceVoices = Collections.emptyList();

    private static final class CerenceVoiceInfo {
        final String voiceId;
        final String voiceName;
        final String language;
        final String gender;
        final Locale locale;
        final String alias;

        CerenceVoiceInfo(String voiceId, String voiceName, String language, String gender,
                         Locale locale, String alias) {
            this.voiceId = voiceId;
            this.voiceName = voiceName;
            this.language = language;
            this.gender = gender;
            this.locale = locale;
            this.alias = alias;
        }

        Voice asAndroidVoice() {
            return new Voice(alias, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL,
                    false, Collections.<String>emptySet());
        }

        @Override public String toString() {
            return alias + " -> " + voiceId + " [" + language + ", " + voiceName + ", " + gender + "]";
        }
    }

    private volatile UtteranceProgressListener progressListener;
    private volatile String currentUtteranceId;
    private volatile String cancelledUtteranceId;

    public CerenceTtsBase(Context context, TextToSpeech.OnInitListener listener) {
        AiviTtsLog.setContext(context);
        AiviTtsLog.i("TextToSpeech facade constructor; engine=<default> context="
                + (context == null ? "null" : context.getClass().getName()));
        start(context, listener);
    }

    public CerenceTtsBase(Context context, TextToSpeech.OnInitListener listener,
                          String enginePackage) {
        AiviTtsLog.setContext(context);
        AiviTtsLog.i("TextToSpeech facade constructor; requestedEngine=" + String.valueOf(enginePackage)
                + " context=" + (context == null ? "null" : context.getClass().getName()));
        start(context, listener);
    }

    private void start(Context context, TextToSpeech.OnInitListener listener) {
        hostContext = context;
        Context candidate = context == null ? null : context.getApplicationContext();
        appContext = candidate != null ? candidate : context;
        AiviTtsLog.setContext(appContext != null ? appContext : context);
        synchronized (LOCK) {
            AiviTtsLog.i("start(); ready=" + ready + " initializing=" + initializing
                    + " previousError=" + (initError == null ? "none" : initError.getClass().getSimpleName()));
            if (ready) {
                notifyInit(listener, TextToSpeech.SUCCESS);
                return;
            }
            if (listener != null) INIT_LISTENERS.add(listener);
            if (initializing) {
                AiviTtsLog.i("Cerence initialization already in progress; listener queued");
                return;
            }
            // v1.1.82 treated the first initialization failure as permanent. That is unsafe on
            // Android 10 because the first attempt can happen before ABRP has an Activity or
            // storage permission. Always allow a later constructor/speak call to retry.
            initError = null;
            initializing = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    long started = System.currentTimeMillis();
                    try {
                        AiviTtsLog.i("Cerence initialization thread started");
                        initCerence();
                        ready = true;
                        AiviTtsLog.i("BYD native Cerence TTS ready; voice=" + selectedVoiceId
                                + " elapsedMs=" + (System.currentTimeMillis() - started));
                        finishInit(TextToSpeech.SUCCESS, null);
                    } catch (Throwable t) {
                        Throwable cause = unwrap(t);
                        AiviTtsLog.e("BYD native Cerence TTS initialization failed after "
                                + (System.currentTimeMillis() - started) + "ms", cause);
                        finishInit(TextToSpeech.ERROR, cause);
                    }
                }
            }, "BYD-ABRP-Cerence-Init").start();
        }
    }

    private static void finishInit(int status, Throwable error) {
        ArrayList<TextToSpeech.OnInitListener> listeners;
        synchronized (LOCK) {
            initializing = false;
            initError = status == TextToSpeech.SUCCESS ? null
                    : (error == null ? new Exception("Cerence init failed") : error);
            listeners = new ArrayList<>(INIT_LISTENERS);
            INIT_LISTENERS.clear();
        }
        AiviTtsLog.i("finishInit status=" + status + " listeners=" + listeners.size()
                + " retryable=" + (status != TextToSpeech.SUCCESS));
        for (TextToSpeech.OnInitListener l : listeners) notifyInit(l, status);
    }

    private static void notifyInit(final TextToSpeech.OnInitListener listener, final int status) {
        if (listener == null) return;
        AiviTtsLog.i("Posting Android TTS onInit(" + status + ") to RN-TTS");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    listener.onInit(status);
                    AiviTtsLog.i("RN-TTS onInit callback delivered status=" + status);
                } catch (Throwable t) {
                    AiviTtsLog.w("ABRP TTS init listener failed", t);
                }
            }
        });
    }

    private static void initCerence() throws Exception {
        final Context context = appContext;
        if (context == null) throw new IllegalStateException("ABRP application context is null");
        AiviTtsLog.i("initCerence: context=" + context.getClass().getName()
                + " storagePermission=" + hasStoragePermission(context));
        ensureStoragePermission(context, hostContext);
        AiviTtsLog.flush();
        AiviTtsLog.i("initCerence: storage permission ready=" + hasStoragePermission(context));

        final Context navContext = context.createPackageContext(
                NAV_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        final ClassLoader cl = navContext.getClassLoader();
        if (cl == null) throw new IllegalStateException("Telenav Navigation classloader is null");
        navLoader = cl;
        AiviTtsLog.i("initCerence: Telenav classloader=" + cl.getClass().getName());

        final Class<?> arkAssistant = Class.forName(ARK_ASSISTANT, true, cl);
        final Class<?> frameworkClass = Class.forName(ARK_FRAMEWORK, true, cl);
        final Class<?> builderClass = Class.forName(CONFIG_BUILDER, true, cl);
        final Class<?> frameworkListenerClass = Class.forName(FRAMEWORK_LISTENER, true, cl);
        final Class<?> resultCallbackClass = Class.forName(RESULT_CALLBACK, true, cl);
        prompterCallbackClass = Class.forName(PROMPTER_CALLBACK, true, cl);
        final Class<?> utilsClass = Class.forName(UTILS, true, cl);
        final Class<?> loggerClass = Class.forName(LOGGER, true, cl);
        final Class<?> loggerConfigClass = Class.forName(LOGGER_CONFIG, true, cl);

        File data = new File(OTA_DATA);
        AiviTtsLog.i("initCerence: dataPath=" + OTA_DATA + " exists=" + data.exists()
                + " dir=" + data.isDirectory() + " readable=" + data.canRead());
        if (!data.isDirectory() || !data.canRead()) {
            throw new IllegalStateException("Cerence data is not readable: " + OTA_DATA
                    + " (storage permission=" + hasStoragePermission(context) + ")");
        }

        final List<?> asr = staticList(arkAssistant, "getAvailableAsrLanguages",
                new Class<?>[]{String.class}, OTA_DATA);
        final List<?> voices = staticList(arkAssistant, "getAvailableTtsVoices",
                new Class<?>[]{String.class, String.class}, OTA_DATA, null);
        AiviTtsLog.i("initCerence: ASR=" + String.valueOf(asr)
                + " voiceCount=" + (voices == null ? -1 : voices.size()));
        if (asr == null || asr.isEmpty() || voices == null || voices.isEmpty()) {
            throw new IllegalStateException("Cerence Ark returned no languages/voices");
        }
        availableCerenceVoices = buildVoiceCatalog(voices);
        if (availableCerenceVoices.isEmpty()) {
            throw new IllegalStateException("Cerence Ark voices could not be converted for ABRP");
        }
        for (CerenceVoiceInfo info : availableCerenceVoices) {
            AiviTtsLog.i("initCerence: available voice " + info.toString());
        }
        String asrLanguage = "eng-USA";
        if (!asr.contains(asrLanguage)) asrLanguage = String.valueOf(asr.get(0));
        final String selectedAsr = asrLanguage;

        final File writable = new File(context.getCacheDir(), "aivi_cerence_ark");
        if (!writable.exists() && !writable.mkdirs()) {
            throw new IllegalStateException("Cannot create Cerence writable cache");
        }
        final String writablePath = writable.getAbsolutePath();

        final Object config = callOnMain(new Callable<Object>() {
            @Override public Object call() throws Exception {
                return withLoader(cl, new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        Object b = builderClass.getDeclaredConstructor().newInstance();
                        b = chain(b, "setAsrLanguage", String.class, selectedAsr);
                        b = chain(b, "dataPath", String.class, OTA_DATA);
                        b = chain(b, "writablePath", String.class, writablePath);
                        b = chain(b, "isSaveInputAudio", boolean.class, false);
                        b = chain(b, "isSaveOutputAudio", boolean.class, false);
                        b = chain(b, "setDeviceId", String.class, "BYDDeviceId");
                        b = chain(b, "userId", String.class, "BYDUserId");
                        return b.getClass().getMethod("build").invoke(b);
                    }
                });
            }
        });

        final Object frameworkListener = simpleProxy(cl, frameworkListenerClass);
        final Object initCallback = simpleProxy(cl, resultCallbackClass);
        final Context cerenceContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;

        AiviTtsLog.i("initCerence: building/initializing ArkFramework; writable=" + writablePath);
        framework = callOnMain(new Callable<Object>() {
            @Override public Object call() throws Exception {
                return withLoader(cl, new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        try {
                            Object lb = loggerConfigClass.getMethod("builder", Context.class).invoke(null, cerenceContext);
                            lb = chain(lb, "setDir", String.class, writablePath);
                            lb = chain(lb, "setLogConsoleEnable", boolean.class, false);
                            lb = chain(lb, "setLogFileEnable", boolean.class, false);
                            Object lc = lb.getClass().getMethod("build").invoke(lb);
                            loggerClass.getMethod("initialize", loggerConfigClass).invoke(null, lc);
                        } catch (Throwable t) {
                            // Optional Cerence logger setup is non-fatal.
                        }

                        utilsClass.getMethod("init", Context.class).invoke(null, cerenceContext);
                        Object seeded = utilsClass.getMethod("getContext").invoke(null);
                        if (seeded == null) {
                            try {
                                Field f = utilsClass.getDeclaredField("sContext");
                                f.setAccessible(true);
                                f.set(null, cerenceContext);
                                seeded = utilsClass.getMethod("getContext").invoke(null);
                            } catch (Throwable ignored) {}
                        }
                        if (seeded == null) throw new IllegalStateException("Cerence Utils context is null");

                        Object fw = frameworkClass.getDeclaredConstructor().newInstance();
                        findMethod(frameworkClass, "initialize", 3).invoke(fw, config, frameworkListener, initCallback);
                        return fw;
                    }
                });
            }
        });

        // ArkFramework.initialize() is asynchronous. Poll the same public surface that
        // Telenav exposes once its prompter is ready instead of using a fixed 7s delay.
        List<?> names = null;
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            final Object fw = framework;
            try {
                names = callOnMain(new Callable<List<?>>() {
                    @Override public List<?> call() throws Exception {
                        return withLoader(cl, new Callable<List<?>>() {
                            @Override public List<?> call() throws Exception {
                                Object v = frameworkClass.getMethod("getAvailablePrompterNames").invoke(fw);
                                return v instanceof List ? (List<?>) v : null;
                            }
                        });
                    }
                });
                if (names != null && !names.isEmpty()) break;
            } catch (Throwable ignored) {}
            sleep(250L);
        }
        if (names == null || names.isEmpty()) throw new IllegalStateException("Cerence Ark prompter did not become ready");
        AiviTtsLog.i("initCerence: prompter names=" + String.valueOf(names));

        CerenceVoiceInfo chosen = chooseInitialVoice();
        if (chosen == null) throw new IllegalStateException("Selected BYD Cerence voice was not found");
        selectedVoiceId = chosen.voiceId;
        selectedVoiceAlias = chosen.alias;
        AiviTtsLog.i("initCerence: selectedVoice=" + selectedVoiceId + " alias=" + selectedVoiceAlias);
        final String voiceId = selectedVoiceId;
        final String prompterName = String.valueOf(names.get(0));
        final int stream = Build.VERSION.SDK_INT > 30 ? 15 : 14;
        final AudioAttributes attrs = new AudioAttributes.Builder().setLegacyStreamType(stream).build();
        final Object fw = framework;

        prompter = callOnMain(new Callable<Object>() {
            @Override public Object call() throws Exception {
                return withLoader(cl, new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        frameworkClass.getMethod("setTtsVoiceId", String.class).invoke(fw, voiceId);
                        return frameworkClass.getMethod("createPrompter", String.class, AudioAttributes.class)
                                .invoke(fw, prompterName, attrs);
                    }
                });
            }
        });
        if (prompter == null) throw new IllegalStateException("Cerence createPrompter returned null");
        AiviTtsLog.i("initCerence: prompter created successfully stream=" + stream);
    }

    private static List<CerenceVoiceInfo> buildVoiceCatalog(List<?> rawVoices) {
        if (rawVoices == null || rawVoices.isEmpty()) return Collections.emptyList();

        boolean hasZoe = false;
        for (Object raw : rawVoices) {
            Object id = safeInvoke(raw, "getVoiceId");
            if (id != null && ZOE_ID.equals(String.valueOf(id))) {
                hasZoe = true;
                break;
            }
        }

        ArrayList<CerenceVoiceInfo> result = new ArrayList<>();
        HashSet<String> usedAliases = new HashSet<>();
        boolean englishAliasUsed = false;
        int fallbackIndex = 1;

        for (Object raw : rawVoices) {
            String id = cleanValue(safeInvoke(raw, "getVoiceId"));
            if (id.length() == 0) continue;
            String language = cleanValue(safeInvoke(raw, "getLanguage"));
            String voiceName = cleanValue(safeInvoke(raw, "getVoiceName"));
            String gender = cleanValue(safeInvoke(raw, "getGender"));
            Locale locale = localeFromCerenceLanguage(language);

            String alias;
            boolean english = isEnglishLocale(locale, language);
            if (ZOE_ID.equals(id) || (english && !hasZoe && !englishAliasUsed)) {
                alias = ABRP_VOICE_ALIAS;
                englishAliasUsed = true;
            } else {
                alias = localeAlias(locale, language);
                if (alias.length() == 0 || "und".equalsIgnoreCase(alias)) {
                    alias = "cerence-" + fallbackIndex++;
                }
            }
            alias = uniqueAlias(alias, usedAliases);
            usedAliases.add(alias.toLowerCase(Locale.US));
            result.add(new CerenceVoiceInfo(id, voiceName, language, gender, locale, alias));
        }
        return Collections.unmodifiableList(result);
    }

    private static String uniqueAlias(String base, Set<String> usedAliases) {
        String value = base == null ? "" : base.trim();
        if (value.length() == 0) value = "cerence";
        if (!usedAliases.contains(value.toLowerCase(Locale.US))) return value;
        int suffix = 2;
        while (usedAliases.contains((value + "-" + suffix).toLowerCase(Locale.US))) suffix++;
        return value + "-" + suffix;
    }

    private static String cleanValue(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private static Locale localeFromCerenceLanguage(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.length() == 0) return Locale.ROOT;
        String[] parts = raw.replace('_', '-').split("-");
        String language = parts.length > 0 ? isoLanguageToIso2(parts[0]) : "";
        String country = parts.length > 1 ? isoCountryToIso2(parts[1]) : "";
        if (language.length() == 0) return Locale.ROOT;
        return country.length() == 0 ? new Locale(language) : new Locale(language, country);
    }

    private static String isoLanguageToIso2(String code) {
        if (code == null) return "";
        String c = code.trim().toLowerCase(Locale.US);
        if (c.length() == 0) return "";
        if (c.length() == 2) return c;
        // Nuance/Cerence commonly uses ENU in voice/SSML identifiers even though
        // the Ark language metadata is normally ISO-639-2 ENG.
        if ("enu".equals(c)) return "en";
        if (c.length() == 3) {
            for (String iso2 : Locale.getISOLanguages()) {
                try {
                    if (c.equals(new Locale(iso2).getISO3Language().toLowerCase(Locale.US))) return iso2;
                } catch (Throwable ignored) {}
            }
        }
        return c;
    }

    private static String isoCountryToIso2(String code) {
        if (code == null) return "";
        String c = code.trim().toUpperCase(Locale.US);
        if (c.length() == 0) return "";
        if (c.length() == 2) return c;
        if (c.length() == 3) {
            for (String iso2 : Locale.getISOCountries()) {
                try {
                    Locale candidate = new Locale("", iso2);
                    if (c.equals(candidate.getISO3Country().toUpperCase(Locale.US))) return iso2;
                } catch (Throwable ignored) {}
            }
        }
        return c;
    }

    private static String localeAlias(Locale locale, String rawLanguage) {
        if (locale != null && locale != Locale.ROOT) {
            String language = locale.getLanguage();
            String country = locale.getCountry();
            if (language != null && language.length() > 0) {
                return country == null || country.length() == 0
                        ? language.toLowerCase(Locale.US)
                        : language.toLowerCase(Locale.US) + "-" + country.toUpperCase(Locale.US);
            }
        }
        String raw = rawLanguage == null ? "" : rawLanguage.trim();
        return raw.replace('_', '-');
    }

    private static boolean isEnglishLocale(Locale locale, String rawLanguage) {
        if (locale != null && "en".equalsIgnoreCase(locale.getLanguage())) return true;
        String raw = rawLanguage == null ? "" : rawLanguage.toLowerCase(Locale.US);
        return raw.startsWith("eng") || raw.startsWith("enu") || raw.startsWith("en-") || "en".equals(raw);
    }

    private static CerenceVoiceInfo findVoiceById(String id) {
        if (id == null || id.length() == 0) return null;
        for (CerenceVoiceInfo info : availableCerenceVoices) {
            if (id.equals(info.voiceId)) return info;
        }
        return null;
    }

    private static CerenceVoiceInfo findVoiceByAlias(String alias) {
        if (alias == null || alias.trim().length() == 0) return null;
        String wanted = alias.trim();
        for (CerenceVoiceInfo info : availableCerenceVoices) {
            if (wanted.equalsIgnoreCase(info.alias) || wanted.equals(info.voiceId)) return info;
        }
        return null;
    }

    private static CerenceVoiceInfo findVoiceByLocale(Locale locale) {
        if (locale == null) return null;
        String wantedLanguage = locale.getLanguage();
        String wantedCountry = locale.getCountry();
        if (wantedLanguage == null || wantedLanguage.length() == 0) return null;

        CerenceVoiceInfo languageOnly = null;
        for (CerenceVoiceInfo info : availableCerenceVoices) {
            if (!wantedLanguage.equalsIgnoreCase(info.locale.getLanguage())) continue;
            if (languageOnly == null) languageOnly = info;
            if (wantedCountry != null && wantedCountry.length() > 0
                    && wantedCountry.equalsIgnoreCase(info.locale.getCountry())) return info;
        }
        return languageOnly;
    }

    private static boolean applyVoiceSelection(final CerenceVoiceInfo info, String reason) {
        if (info == null) return false;

        final Object fw = framework;
        final ClassLoader cl = navLoader;
        if (fw == null || cl == null) {
            selectedVoiceId = info.voiceId;
            selectedVoiceAlias = info.alias;
            requestedVoiceAlias = info.alias;
            requestedLocale = info.locale;
            AiviTtsLog.i("Cerence voice queued until framework is ready: " + info.toString()
                    + " reason=" + reason);
            return true;
        }
        try {
            Object result = callOnMain(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    return withLoader(cl, new Callable<Object>() {
                        @Override public Object call() throws Exception {
                            return fw.getClass().getMethod("setTtsVoiceId", String.class).invoke(fw, info.voiceId);
                        }
                    });
                }
            });
            if (result instanceof Boolean && !((Boolean) result)) {
                AiviTtsLog.w("Cerence rejected voice " + info.voiceId + " reason=" + reason);
                return false;
            }
            selectedVoiceId = info.voiceId;
            selectedVoiceAlias = info.alias;
            requestedVoiceAlias = info.alias;
            requestedLocale = info.locale;
            AiviTtsLog.i("Cerence voice selected: " + info.toString() + " reason=" + reason);
            return true;
        } catch (Throwable t) {
            AiviTtsLog.w("Could not select Cerence voice " + info.voiceId + " reason=" + reason, unwrap(t));
            return false;
        }
    }

    private static CerenceVoiceInfo chooseInitialVoice() {
        CerenceVoiceInfo requested = findVoiceByAlias(requestedVoiceAlias);
        if (requested != null) return requested;

        requested = findVoiceByLocale(requestedLocale);
        if (requested != null) return requested;

        requested = findVoiceById(selectedVoiceId);
        if (requested != null) return requested;

        requested = findVoiceById(ZOE_ID);
        if (requested != null) return requested;

        requested = findVoiceByAlias(ABRP_VOICE_ALIAS);
        if (requested != null) return requested;

        List<CerenceVoiceInfo> voices = availableCerenceVoices;
        return voices.isEmpty() ? null : voices.get(0);
    }

    public int speak(final CharSequence text, final int queueMode, Bundle params, final String utteranceId) {
        final String spoken = text == null ? "" : text.toString();
        AiviTtsLog.i("speak requested id=" + String.valueOf(utteranceId)
                + " queueMode=" + queueMode + " ready=" + ready + " initializing=" + initializing
                + " text=\"" + clip(spoken, 240) + "\"");
        if (spoken.length() == 0) {
            AiviTtsLog.w("speak rejected: empty text id=" + String.valueOf(utteranceId));
            fireError(utteranceId);
            return TextToSpeech.ERROR;
        }

        if (!ready || prompter == null || navLoader == null || prompterCallbackClass == null) {
            // ABRP's voice-preview UI can call speak while its TextToSpeech object is still
            // initializing. v1.1.82 returned ERROR immediately, leaving ABRP waiting forever
            // for a finish/error event. Queue the utterance and return Android-TTS SUCCESS;
            // the normal progress callback will complete the preview once Cerence is ready.
            AiviTtsLog.i("speak deferred until Cerence becomes ready id=" + String.valueOf(utteranceId));
            if (!initializing) start(hostContext != null ? hostContext : appContext, null);
            new Thread(new Runnable() {
                @Override public void run() {
                    long deadline = System.currentTimeMillis() + 35000L;
                    while (System.currentTimeMillis() < deadline) {
                        if (ready && prompter != null && navLoader != null && prompterCallbackClass != null) {
                            int result = speakReady(spoken, queueMode, utteranceId);
                            AiviTtsLog.i("deferred speak executed id=" + String.valueOf(utteranceId)
                                    + " result=" + result);
                            return;
                        }
                        if (!initializing && initError != null) {
                            AiviTtsLog.w("deferred speak saw retryable init failure id="
                                    + String.valueOf(utteranceId) + " error=" + initError);
                            start(hostContext != null ? hostContext : appContext, null);
                        }
                        sleep(250L);
                    }
                    AiviTtsLog.w("deferred speak timed out waiting for Cerence id=" + String.valueOf(utteranceId));
                    fireError(utteranceId);
                }
            }, "BYD-ABRP-Cerence-SpeakWait").start();
            return TextToSpeech.SUCCESS;
        }
        return speakReady(spoken, queueMode, utteranceId);
    }

    private int speakReady(final String spoken, final int queueMode, final String utteranceId) {
        if (queueMode == TextToSpeech.QUEUE_FLUSH && currentUtteranceId != null) {
            cancelledUtteranceId = currentUtteranceId;
        }
        currentUtteranceId = utteranceId;
        try {
            final ClassLoader cl = navLoader;
            final Object pr = prompter;
            final Object callback = progressProxy(cl, prompterCallbackClass, this, utteranceId);
            callOnMain(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    return withLoader(cl, new Callable<Object>() {
                        @Override public Object call() throws Exception {
                            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                                try {
                                    pr.getClass().getMethod("stop").invoke(pr);
                                    AiviTtsLog.i("Cerence stop-before-flush completed id=" + String.valueOf(utteranceId));
                                } catch (Throwable stopError) {
                                    AiviTtsLog.w("Cerence stop-before-flush warning", unwrap(stopError));
                                }
                            }
                            AiviTtsLog.i("Invoking Cerence Prompter.play id=" + String.valueOf(utteranceId)
                                    + " immediate=" + (queueMode == TextToSpeech.QUEUE_FLUSH));
                            findMethod(pr.getClass(), "play", 3).invoke(pr, spoken,
                                    queueMode == TextToSpeech.QUEUE_FLUSH, callback);
                            return null;
                        }
                    });
                }
            });
            return TextToSpeech.SUCCESS;
        } catch (Throwable t) {
            AiviTtsLog.e("Cerence speak failed id=" + String.valueOf(utteranceId), unwrap(t));
            fireError(utteranceId);
            return TextToSpeech.ERROR;
        }
    }

    public int stop() {
        AiviTtsLog.i("stop requested currentId=" + String.valueOf(currentUtteranceId));
        final Object pr = prompter;
        final ClassLoader cl = navLoader;
        if (pr == null || cl == null) return TextToSpeech.SUCCESS;
        try {
            if (currentUtteranceId != null) cancelledUtteranceId = currentUtteranceId;
            callOnMain(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    return withLoader(cl, new Callable<Object>() {
                        @Override public Object call() throws Exception {
                            pr.getClass().getMethod("stop").invoke(pr);
                            return null;
                        }
                    });
                }
            });
            return TextToSpeech.SUCCESS;
        } catch (Throwable t) {
            AiviTtsLog.w("Cerence stop failed", unwrap(t));
            return TextToSpeech.ERROR;
        }
    }

    public void shutdown() {
        AiviTtsLog.i("shutdown requested");
        stop();
    }

    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        this.progressListener = listener;
        AiviTtsLog.i("setOnUtteranceProgressListener listener=" + (listener == null ? "null" : listener.getClass().getName()));
        return TextToSpeech.SUCCESS;
    }

    public int setAudioAttributes(AudioAttributes attributes) {
        AiviTtsLog.i("setAudioAttributes called " + String.valueOf(attributes));
        return TextToSpeech.SUCCESS;
    }
    public int setLanguage(Locale locale) {
        AiviTtsLog.i("setLanguage called locale=" + String.valueOf(locale));
        if (locale == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        requestedLocale = locale;

        if (availableCerenceVoices.isEmpty()) {
            AiviTtsLog.i("setLanguage queued until Cerence voice discovery completes: " + locale);
            return TextToSpeech.LANG_AVAILABLE;
        }

        CerenceVoiceInfo info = findVoiceByLocale(locale);
        if (info == null) {
            AiviTtsLog.w("setLanguage unsupported by installed Cerence voices: " + locale);
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if (!applyVoiceSelection(info, "setLanguage(" + locale + ")")) return TextToSpeech.LANG_NOT_SUPPORTED;

        String requestedCountry = locale.getCountry();
        if (requestedCountry != null && requestedCountry.length() > 0
                && requestedCountry.equalsIgnoreCase(info.locale.getCountry())) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        }
        return TextToSpeech.LANG_AVAILABLE;
    }
    public int setPitch(float pitch) {
        AiviTtsLog.i("setPitch called pitch=" + pitch + " (not applied by Cerence facade)");
        return TextToSpeech.SUCCESS;
    }
    public int setSpeechRate(float rate) {
        AiviTtsLog.i("setSpeechRate called rate=" + rate + " (not applied by Cerence facade)");
        return TextToSpeech.SUCCESS;
    }
    public int setVoice(Voice voice) {
        AiviTtsLog.i("setVoice called voice=" + String.valueOf(voice));
        if (voice == null) return TextToSpeech.ERROR;
        requestedVoiceAlias = voice.getName();
        requestedLocale = voice.getLocale();

        if (availableCerenceVoices.isEmpty()) {
            AiviTtsLog.i("setVoice queued until Cerence voice discovery completes: " + voice.getName());
            return TextToSpeech.SUCCESS;
        }

        CerenceVoiceInfo info = findVoiceByAlias(voice.getName());
        if (info == null) info = findVoiceByLocale(voice.getLocale());
        if (info == null) {
            AiviTtsLog.w("ABRP requested a voice that is not installed: " + String.valueOf(voice));
            return TextToSpeech.ERROR;
        }
        return applyVoiceSelection(info, "setVoice(" + voice.getName() + ")")
                ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    public Voice getVoice() {
        CerenceVoiceInfo info = findVoiceById(selectedVoiceId);
        if (info == null) info = findVoiceByAlias(selectedVoiceAlias);
        if (info == null) info = chooseInitialVoice();
        if (info != null) {
            AiviTtsLog.i("getVoice -> " + info.toString());
            return info.asAndroidVoice();
        }

        Voice fallback = new Voice(ABRP_VOICE_ALIAS, Locale.ENGLISH,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.<String>emptySet());
        AiviTtsLog.i("getVoice -> temporary fallback " + ABRP_VOICE_ALIAS + " while Cerence initializes");
        return fallback;
    }

    public Set<Voice> getVoices() {
        List<CerenceVoiceInfo> catalog = availableCerenceVoices;
        if (catalog.isEmpty()) {
            AiviTtsLog.i("getVoices -> temporary fallback while Cerence initializes");
            return Collections.singleton(getVoice());
        }

        LinkedHashSet<Voice> result = new LinkedHashSet<>();
        for (CerenceVoiceInfo info : catalog) result.add(info.asAndroidVoice());
        AiviTtsLog.i("getVoices -> " + result.size() + " installed Cerence voices");
        return result;
    }

    public String getDefaultEngine() {
        String value = ABRP_ENGINE_ALIAS;
        AiviTtsLog.i("getDefaultEngine -> " + value);
        return value;
    }

    public List<TextToSpeech.EngineInfo> getEngines() {
        // react-native-tts exposes getEngines() to JavaScript. Return one synthetic
        // engine entry so ABRP's settings/UI also sees this backend as available,
        // even though it is not registered as a system TextToSpeechService.
        TextToSpeech.EngineInfo e = new TextToSpeech.EngineInfo();
        e.name = getDefaultEngine();
        e.label = "BYD Cerence";
        e.icon = 0;
        AiviTtsLog.i("getEngines -> BYD Cerence name=" + e.name);
        return Collections.singletonList(e);
    }

    private void fireStart(String id) {
        AiviTtsLog.i("UtteranceProgress onStart id=" + String.valueOf(id));
        UtteranceProgressListener l = progressListener;
        if (l != null && id != null) try { l.onStart(id); } catch (Throwable t) { AiviTtsLog.w("onStart delivery failed", t); }
    }
    private void fireDone(String id) {
        AiviTtsLog.i("UtteranceProgress onDone id=" + String.valueOf(id));
        UtteranceProgressListener l = progressListener;
        if (l != null && id != null) try { l.onDone(id); } catch (Throwable t) { AiviTtsLog.w("onDone delivery failed", t); }
    }
    private void fireError(String id) {
        AiviTtsLog.w("UtteranceProgress onError id=" + String.valueOf(id));
        UtteranceProgressListener l = progressListener;
        if (l != null && id != null) try { l.onError(id); } catch (Throwable t) { AiviTtsLog.w("onError delivery failed", t); }
    }
    private void fireStop(String id) {
        AiviTtsLog.i("UtteranceProgress onStop id=" + String.valueOf(id));
        UtteranceProgressListener l = progressListener;
        if (l != null && id != null && Build.VERSION.SDK_INT >= 23) {
            try { l.onStop(id, true); } catch (Throwable t) { AiviTtsLog.w("onStop delivery failed", t); }
        }
    }

    private static Object progressProxy(ClassLoader cl, Class<?> iface, final CerenceTtsBase owner, final String utteranceId) {
        InvocationHandler h = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("onPlayStateChanged".equals(name) && args != null && args.length > 0) {
                    String state = String.valueOf(args[0]);
                    AiviTtsLog.i("Cerence Prompter callback id=" + String.valueOf(utteranceId) + " state=" + state);
                    if (state.contains("STARTED")) owner.fireStart(utteranceId);
                    else if (state.contains("STOPPED")) {
                        if (utteranceId != null && utteranceId.equals(owner.cancelledUtteranceId)) {
                            owner.cancelledUtteranceId = null;
                            owner.fireStop(utteranceId);
                        } else {
                            owner.fireDone(utteranceId);
                        }
                    }
                } else if (name.toLowerCase(Locale.US).contains("error")) {
                    AiviTtsLog.w("Cerence Prompter callback error method=" + name + " id=" + String.valueOf(utteranceId));
                    owner.fireError(utteranceId);
                }
                return defaultValue(method.getReturnType());
            }
        };
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, h);
    }

    private static Object simpleProxy(ClassLoader cl, Class<?> iface) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static Object defaultValue(Class<?> r) {
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == byte.class) return (byte) 0;
        if (r == short.class) return (short) 0;
        if (r == char.class) return '\0';
        return null;
    }

    private static void ensureStoragePermission(Context context, Context requestContext) throws Exception {
        if (Build.VERSION.SDK_INT < 23 || hasStoragePermission(context)) {
            AiviTtsLog.i("Storage permission already granted");
            return;
        }
        AiviTtsLog.w("Storage permission missing; trying to request from ABRP Activity");

        // The RN TextToSpeech module can be constructed before React has attached its
        // current Activity. Keep looking briefly for an Activity, but request only once
        // so a slow ABRP startup cannot stack multiple Android permission dialogs.
        final Context requester = requestContext != null ? requestContext : context;
        boolean requestIssued = false;
        long deadline = System.currentTimeMillis() + 30000L;
        while (System.currentTimeMillis() < deadline) {
            if (hasStoragePermission(context)) {
                AiviTtsLog.i("Storage permission became granted");
                AiviTtsLog.flush();
                return;
            }
            if (!requestIssued) {
                requestIssued = requestStoragePermission(requester);
                AiviTtsLog.i("Storage permission requestIssued=" + requestIssued);
            }
            sleep(250L);
        }
        throw new SecurityException("Storage permission is required for BYD Cerence voice data"
                + (requestIssued ? " (permission was requested)" : " (ABRP Activity was not available)"));
    }

    private static boolean hasStoragePermission(Context c) {
        return Build.VERSION.SDK_INT < 23 ||
                c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean requestStoragePermission(final Context context) {
        if (context == null || Build.VERSION.SDK_INT < 23) return false;
        final AtomicReference<Boolean> issued = new AtomicReference<>(false);
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    Activity a = null;
                    if (context instanceof Activity) a = (Activity) context;
                    if (a == null) {
                        try {
                            Method m = context.getClass().getMethod("getCurrentActivity");
                            Object v = m.invoke(context);
                            if (v instanceof Activity) a = (Activity) v;
                        } catch (Throwable ignored) {}
                    }
                    if (a != null) {
                        AiviTtsLog.i("Requesting READ/WRITE_EXTERNAL_STORAGE from Activity=" + a.getClass().getName());
                        a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7418);
                        issued.set(true);
                    } else {
                        AiviTtsLog.w("No current ABRP Activity available for storage permission request");
                    }
                } catch (Throwable t) {
                    AiviTtsLog.w("Could not request storage permission", t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try { latch.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Boolean.TRUE.equals(issued.get());
    }

    private interface Callable<T> { T call() throws Exception; }

    private static <T> T callOnMain(final Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { value.set(c.call()); }
                catch (Throwable t) { error.set(t); }
                finally { latch.countDown(); }
            }
        });
        if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for Cerence main-thread call");
        if (error.get() != null) throw asException(unwrap(error.get()));
        return value.get();
    }

    private static <T> T withLoader(ClassLoader loader, Callable<T> body) throws Exception {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        try {
            t.setContextClassLoader(loader);
            return body.call();
        } finally {
            t.setContextClassLoader(old);
        }
    }

    private static List<?> staticList(Class<?> c, String name, Class<?>[] types, Object... args) throws Exception {
        Object v = c.getMethod(name, types).invoke(null, args);
        return v instanceof List ? (List<?>) v : null;
    }

    private static Object chain(Object target, String name, Class<?> type, Object arg) throws Exception {
        Object r = target.getClass().getMethod(name, type).invoke(target, arg);
        return r == null ? target : r;
    }

    private static Method findMethod(Class<?> c, String name, int argCount) throws NoSuchMethodException {
        for (Method m : c.getMethods()) {
            if (name.equals(m.getName()) && m.getParameterTypes().length == argCount) return m;
        }
        throw new NoSuchMethodException(c.getName() + "." + name + "/" + argCount);
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private static Object safeInvoke(Object target, String method) {
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (Throwable ignored) { return null; }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable x = t;
        while (x instanceof InvocationTargetException
                && ((InvocationTargetException) x).getTargetException() != null) {
            x = ((InvocationTargetException) x).getTargetException();
        }
        return x;
    }

    private static Exception asException(Throwable t) {
        return t instanceof Exception ? (Exception) t : new Exception(t);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

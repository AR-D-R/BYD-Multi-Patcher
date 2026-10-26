package com.bydspotifymanager.app;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.View;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** BYD Multi Patcher profiles for Spotify, Waze and A Better Routeplanner. */
public class MainActivity extends AppCompatActivity {
    private static final int SPOTIFY_ACCENT = Color.rgb(29, 185, 84);
    private static final int SPOTIFY_ACCENT_DARK = Color.rgb(22, 137, 67);
    private static final int WAZE_ACCENT = Color.rgb(51, 204, 255);
    private static final int WAZE_ACCENT_DARK = Color.rgb(25, 145, 184);
    private static final int ABRP_ACCENT = Color.rgb(197, 34, 34);
    private static final int ABRP_ACCENT_DARK = Color.rgb(143, 24, 24);
    private static final int DEBUG_RED = Color.rgb(239, 83, 80);
    private static final int DEBUG_RED_DARK = Color.rgb(183, 28, 28);
    private static final int CONTROL_UNCHECKED = Color.rgb(179, 179, 179);
    private static final int CONTROL_DISABLED = Color.rgb(105, 105, 105);
    private static final int SCALE_MIN_PERCENT = 100;
    private static final int SCALE_MAX_PERCENT = 150;
    private static final int SCALE_STEP_PERCENT = 5;
    private static final int SCALE_DEFAULT_PERCENT = 115;

    // Waze profiles exposed by this patcher build. The old 5.24 beta binary profile remains
    // inside WazePatchEngine for compatibility, but is intentionally not advertised in the UI.
    private static final String WAZE_FEATURES_DESCRIPTION =
            "BYD wide-screen changes:\n"
            + "• Centres the car and speedometer.\n"
            + "• Makes the navigation panel narrower to show more map.\n"
            + "• Moves top controls below thick system bars.\n"
            + "• Improves route preview and overview placement.\n"
            + "• Keeps the search drawer smaller when collapsed.\n"
            + "• Keeps drawers and report confirmation controls above the BYD navigation bar.";

    private static final String WAZE24_USER_DESCRIPTION = WAZE_FEATURES_DESCRIPTION;

    private static final String WAZE59_USER_DESCRIPTION =
            "Older Waze version — may run quicker on slower hardware.\n\n"
            + WAZE_FEATURES_DESCRIPTION;

    private TextView txtOfficial, txtPrimary, txtSecondary, txtSourceStatus, txtProgress;
    private TextView txtOptimiseHint, txtSelectedSlotTitle, txtManagerVersion, txtIconHue, txtScaleValue, txtBydClusterTitle, txtBydClusterHint;
    private TextView txtProgressStage, txtProgressPercent, txtSourceVersion, txtSlotsVersion, txtBrandingHint, txtWazeInstalled, txtAbrpInstalled;
    private TextView txtWazeProfileTitle, txtWazeSettingsTitle, txtWazeWideDetails, txtWazeAudioDetails, txtWazeEvDetails, txtWazeMicDetails, txtWazeDebugDetails;
    private ProgressBar progressBar;

    private RadioButton radioEngine91, radioEngine89, radioEngineWaze, radioEngineWaze59, radioEngineAbrp;
    private RadioButton radioPrimary, radioSecondary;
    private RadioButton radioPlayerLhs, radioPlayerRhs;
    private RadioButton radio89FontStock, radio89FontModerate, radio89FontLarge;
    private RadioButton radio89PanelLeft, radio89PanelRight;

    private EditText editAppName;
    private SwitchMaterial switchOptimise, switchBydLayout, switchAutoResume, switchBydClusterBridge, switchIconBadge, switch89PreventPortrait, switchWazeWide, switchWazeDisableAudio, switchWazeEv, switchWazeRemoveMic, switchWazeDebugLogging, switchAbrpDirectBattery, switchAbrpAutoDayNight, switchAbrpNativeTts, switchAbrpDebugLogging;
    private Button btnPatchInstall, btnPatchSaveApkm, btnPrimaryInfo, btnSecondaryInfo, btnWazeInfo, btnAbrpInfo, btnBrowse, btnApkMirror, btnDiagnostics, btnRefresh;
    private SeekBar seekIconHue, seekInterfaceScale;
    private ImageView imgIconPreview;
    private View slotPrimaryCard, slotSecondaryCard, panel91, panel89, panelWaze, panelAbrp;
    private View spotifySlotsPanel, wazeStatusPanel, abrpStatusPanel, spotifySettingsPanel;

    private android.graphics.Bitmap stockIconPreview, renderedIconPreview;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean installReceiverRegistered;
    private boolean loadingSlotUi;
    private boolean loadingWazeUi;
    private boolean engine89;
    private boolean engineWaze;      // Waze 5.24
    private boolean engineWaze59;
    private boolean engineAbrp;
    private boolean sourceValid91;
    private boolean sourceValid89;
    private boolean sourceValidWaze;
    private boolean sourceValidWaze59;
    private boolean sourceValidAbrp;
    private volatile WazePatchEngine.Validation waze24SourceValidation, waze59SourceValidation;
    private File sourceApk;

    private final BroadcastReceiver installRefreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshPackages();
            int status = intent.getIntExtra("status", -999);
            String message = intent.getStringExtra("message");
            String targetPackage = intent.getStringExtra("targetPackage");
            String installedName = displayNameForPackage(targetPackage);
            if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                setBusy(false, installedName + " installed/updated successfully.");
                showProgress(100, "COMPLETE", installedName + " installed/updated successfully.");
            } else {
                String detail = (message == null || message.isEmpty())
                        ? "Android reported installation failure." : "Install result: " + message;
                setBusy(false, detail);
                showProgress(100, "INSTALL FAILED", detail);
            }
            if (prefs != null) {
                prefs.edit().remove("last_install_status").remove("last_install_message")
                        .remove("last_install_package").apply();
            }
        }
    };

    private final ActivityResultLauncher<Intent> pickApk = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) importSource(uri);
            });

    private final ActivityResultLauncher<Intent> pickBuiltIn = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == FileBrowserActivity.RESULT_USE_EXTERNAL) {
                    openExternalPicker();
                    return;
                }
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                String path = result.getData().getStringExtra(FileBrowserActivity.EXTRA_RESULT_PATH);
                if (path != null && !path.isEmpty()) importSource(Uri.fromFile(new File(path)));
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("manager_settings", MODE_PRIVATE);
        bind();
        boolean clusterEnabled = prefs.contains("spotify_byd_cluster")
                ? prefs.getBoolean("spotify_byd_cluster", true)
                : prefs.getBoolean("spotify89_byd_cluster_test", true);
        switchBydClusterBridge.setChecked(clusterEnabled);
        switchWazeDisableAudio.setChecked(prefs.getBoolean("waze_disable_audio", true));
        switchWazeEv.setChecked(prefs.getBoolean("waze_enable_ev", true));
        switchWazeRemoveMic.setChecked(prefs.getBoolean("waze_remove_microphone", true));
        if (switchWazeDebugLogging != null) switchWazeDebugLogging.setChecked(false);
        switchAbrpDirectBattery.setChecked(prefs.getBoolean("abrp_direct_battery", true));
        switchAbrpAutoDayNight.setChecked(prefs.getBoolean("abrp_auto_day_night_default", true));
        switchAbrpNativeTts.setChecked(prefs.getBoolean("abrp_native_tts", true));
        if (switchAbrpDebugLogging != null) switchAbrpDebugLogging.setChecked(false);
        setupActions();
        String selectedProfile = prefs.getString("selected_engine", "91");
        if ("waze".equals(selectedProfile)) selectedProfile = "waze24"; // migrate v2.1 preference
        engine89 = "89".equals(selectedProfile);
        engineWaze = "waze24".equals(selectedProfile);
        engineWaze59 = "waze59".equals(selectedProfile);
        engineAbrp = "abrp".equals(selectedProfile);
        radioEngine89.setChecked(engine89);
        radioEngineWaze.setChecked(engineWaze);
        radioEngineWaze59.setChecked(engineWaze59);
        radioEngineAbrp.setChecked(engineAbrp);
        radioEngine91.setChecked(!engine89 && !isWazeProfile() && !engineAbrp);
        radioPrimary.setChecked(true);
        radioSecondary.setChecked(false);
        applyEngineUi(false);
    }

    private void bind() {
        txtOfficial = findViewById(R.id.txtOfficial);
        txtPrimary = findViewById(R.id.txtPrimary);
        txtSecondary = findViewById(R.id.txtSecondary);
        txtSourceStatus = findViewById(R.id.txtSourceStatus);
        txtSourceVersion = findViewById(R.id.txtSourceVersion);
        txtSlotsVersion = findViewById(R.id.txtSlotsVersion);
        txtBrandingHint = findViewById(R.id.txtBrandingHint);
        txtWazeInstalled = findViewById(R.id.txtWazeInstalled);
        txtWazeProfileTitle = findViewById(R.id.txtWazeProfileTitle);
        txtWazeSettingsTitle = findViewById(R.id.txtWazeSettingsTitle);
        txtWazeWideDetails = findViewById(R.id.txtWazeWideDetails);
        txtWazeAudioDetails = findViewById(R.id.txtWazeAudioDetails);
        txtWazeEvDetails = findViewById(R.id.txtWazeEvDetails);
        txtWazeMicDetails = findViewById(R.id.txtWazeMicDetails);
        txtWazeDebugDetails = findViewById(R.id.txtWazeDebugDetails);
        txtAbrpInstalled = findViewById(R.id.txtAbrpInstalled);
        txtProgress = findViewById(R.id.txtProgress);
        txtProgressStage = findViewById(R.id.txtProgressStage);
        txtProgressPercent = findViewById(R.id.txtProgressPercent);
        progressBar = findViewById(R.id.progressBar);
        txtOptimiseHint = findViewById(R.id.txtOptimiseHint);
        txtBydClusterTitle = findViewById(R.id.txtBydClusterTitle);
        txtBydClusterHint = findViewById(R.id.txtBydClusterHint);
        txtSelectedSlotTitle = findViewById(R.id.txtSelectedSlotTitle);
        txtManagerVersion = findViewById(R.id.txtManagerVersion);
        txtIconHue = findViewById(R.id.txtIconHue);
        txtScaleValue = findViewById(R.id.txtScaleValue);
        if (txtManagerVersion != null) txtManagerVersion.setText("v" + BuildConfigData.MANAGER_VERSION);

        radioEngine91 = findViewById(R.id.radioEngine91);
        radioEngine89 = findViewById(R.id.radioEngine89);
        radioEngineWaze = findViewById(R.id.radioEngineWaze);
        radioEngineWaze59 = findViewById(R.id.radioEngineWaze59);
        radioEngineAbrp = findViewById(R.id.radioEngineAbrp);
        radioPrimary = findViewById(R.id.radioPrimary);
        radioSecondary = findViewById(R.id.radioSecondary);
        radioPlayerLhs = findViewById(R.id.radioPlayerLhs);
        radioPlayerRhs = findViewById(R.id.radioPlayerRhs);
        radio89FontStock = findViewById(R.id.radio89FontStock);
        radio89FontModerate = findViewById(R.id.radio89FontModerate);
        radio89FontLarge = findViewById(R.id.radio89FontLarge);
        radio89PanelLeft = findViewById(R.id.radio89PanelLeft);
        radio89PanelRight = findViewById(R.id.radio89PanelRight);

        editAppName = findViewById(R.id.editAppName);
        switchOptimise = findViewById(R.id.switchOptimise);
        switchBydLayout = findViewById(R.id.switchBydLayout);
        switchAutoResume = findViewById(R.id.switchAutoResume);
        switchBydClusterBridge = findViewById(R.id.switchBydClusterBridge);
        switchIconBadge = findViewById(R.id.switchIconBadge);
        switch89PreventPortrait = findViewById(R.id.switch89PreventPortrait);
        switchWazeWide = findViewById(R.id.switchWazeWide);
        switchWazeDisableAudio = findViewById(R.id.switchWazeDisableAudio);
        switchWazeEv = findViewById(R.id.switchWazeEv);
        switchWazeRemoveMic = findViewById(R.id.switchWazeRemoveMic);
        switchWazeDebugLogging = findViewById(R.id.switchWazeDebugLogging);
        switchAbrpDirectBattery = findViewById(R.id.switchAbrpDirectBattery);
        switchAbrpAutoDayNight = findViewById(R.id.switchAbrpAutoDayNight);
        switchAbrpNativeTts = findViewById(R.id.switchAbrpNativeTts);
        switchAbrpDebugLogging = findViewById(R.id.switchAbrpDebugLogging);
        seekIconHue = findViewById(R.id.seekIconHue);
        seekInterfaceScale = findViewById(R.id.seekInterfaceScale);
        imgIconPreview = findViewById(R.id.imgIconPreview);

        btnPatchInstall = findViewById(R.id.btnPatchInstall);
        btnPatchSaveApkm = findViewById(R.id.btnPatchSaveApkm);
        btnPrimaryInfo = findViewById(R.id.btnPrimaryInfo);
        btnSecondaryInfo = findViewById(R.id.btnSecondaryInfo);
        btnWazeInfo = findViewById(R.id.btnWazeInfo);
        btnAbrpInfo = findViewById(R.id.btnAbrpInfo);
        btnBrowse = findViewById(R.id.btnBrowse);
        btnApkMirror = findViewById(R.id.btnApkMirror);
        btnDiagnostics = findViewById(R.id.btnDiagnostics);
        btnRefresh = findViewById(R.id.btnRefresh);
        slotPrimaryCard = findViewById(R.id.slotPrimaryCard);
        slotSecondaryCard = findViewById(R.id.slotSecondaryCard);
        panel91 = findViewById(R.id.panel91);
        panel89 = findViewById(R.id.panel89);
        panelWaze = findViewById(R.id.panelWaze);
        panelAbrp = findViewById(R.id.panelAbrp);
        spotifySlotsPanel = findViewById(R.id.spotifySlotsPanel);
        wazeStatusPanel = findViewById(R.id.wazeStatusPanel);
        abrpStatusPanel = findViewById(R.id.abrpStatusPanel);
        spotifySettingsPanel = findViewById(R.id.spotifySettingsPanel);
        applyWazeStaticText();
    }

    private void setupActions() {
        btnBrowse.setOnClickListener(v -> BrowseChoiceDialog.show(this, currentAccent(),
                "Looking for " + currentSourceDescription(),
                prefs.getString("browse_method", ""), this::openBuiltInBrowser, this::openExternalPicker));
        btnApkMirror.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentApkMirrorUrl()))));
        btnRefresh.setOnClickListener(v -> {
            refreshPackages();
            validateStoredSource();
        });
        btnDiagnostics.setOnClickListener(v -> copyDiagnostics());
        btnPrimaryInfo.setOnClickListener(v -> openAppInfo(currentPrimaryPackage()));
        btnSecondaryInfo.setOnClickListener(v -> openAppInfo(currentSecondaryPackage()));
        btnWazeInfo.setOnClickListener(v -> openAppInfo(BuildConfigData.WAZE_PACKAGE));
        btnAbrpInfo.setOnClickListener(v -> openAppInfo(BuildConfigData.ABRP_PACKAGE));
        switchAbrpDirectBattery.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("abrp_direct_battery", isChecked).apply());
        switchAbrpAutoDayNight.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("abrp_auto_day_night_default", isChecked).apply());
        switchAbrpNativeTts.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("abrp_native_tts", isChecked).apply());
        switchBydClusterBridge.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("spotify_byd_cluster", isChecked).remove("spotify89_byd_cluster_test").apply();
            if (!loadingSlotUi) applyClusterBridgeUi();
        });

        radioEngine91.setOnClickListener(v -> switchProfile("91"));
        radioEngine89.setOnClickListener(v -> switchProfile("89"));
        radioEngineWaze.setOnClickListener(v -> switchProfile("waze24"));
        radioEngineWaze59.setOnClickListener(v -> switchProfile("waze59"));
        radioEngineAbrp.setOnClickListener(v -> switchProfile("abrp"));
        radioPrimary.setOnClickListener(v -> selectSlot(true));
        radioSecondary.setOnClickListener(v -> selectSlot(false));
        slotPrimaryCard.setOnClickListener(v -> selectSlot(true));
        slotSecondaryCard.setOnClickListener(v -> selectSlot(false));

        seekIconHue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                txtIconHue.setText(progress + "°");
                updateIconPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        seekInterfaceScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateScaleLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        switchIconBadge.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loadingSlotUi) updateIconPreview();
        });
        switchWazeDisableAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loadingWazeUi) prefs.edit().putBoolean("waze_disable_audio", isChecked).apply();
        });
        switchWazeEv.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loadingWazeUi) prefs.edit().putBoolean("waze_enable_ev", isChecked).apply();
        });
        switchWazeRemoveMic.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loadingWazeUi) prefs.edit().putBoolean("waze_remove_microphone", isChecked).apply();
        });
        btnPatchInstall.setOnClickListener(v -> patchAndInstall());
        btnPatchSaveApkm.setOnClickListener(v -> patchAndSaveApkm());
    }

    private void switchProfile(String profile) {
        boolean new89 = "89".equals(profile);
        boolean newWaze24 = "waze24".equals(profile);
        boolean newWaze59 = "waze59".equals(profile);
        boolean newAbrp = "abrp".equals(profile);
        if (new89 == engine89 && newWaze24 == engineWaze && newWaze59 == engineWaze59 && newAbrp == engineAbrp) return;
        if (!isWazeProfile() && !engineAbrp) saveCurrentSlotUi();
        engine89 = new89;
        engineWaze = newWaze24;
        engineWaze59 = newWaze59;
        engineAbrp = newAbrp;
        prefs.edit().putString("selected_engine", currentProfileCode()).apply();
        radioEngine89.setChecked(engine89);
        radioEngineWaze.setChecked(engineWaze);
        radioEngineWaze59.setChecked(engineWaze59);
        radioEngineAbrp.setChecked(engineAbrp);
        radioEngine91.setChecked(!engine89 && !isWazeProfile() && !engineAbrp);
        applyEngineUi(true);
    }

    private boolean isWazeProfile() { return engineWaze || engineWaze59; }

    private void applyEngineUi(boolean resetProgress) {
        boolean spotify = !isWazeProfile() && !engineAbrp;
        applyProfileAccent();
        applySourceButtonUi();
        spotifySlotsPanel.setVisibility(spotify ? View.VISIBLE : View.GONE);
        wazeStatusPanel.setVisibility(isWazeProfile() ? View.VISIBLE : View.GONE);
        abrpStatusPanel.setVisibility(engineAbrp ? View.VISIBLE : View.GONE);
        spotifySettingsPanel.setVisibility(spotify ? View.VISIBLE : View.GONE);
        if (btnPatchSaveApkm != null) btnPatchSaveApkm.setVisibility(View.VISIBLE);
        panelWaze.setVisibility(isWazeProfile() ? View.VISIBLE : View.GONE);
        panelAbrp.setVisibility(engineAbrp ? View.VISIBLE : View.GONE);
        panel91.setVisibility(spotify && !engine89 ? View.VISIBLE : View.GONE);
        panel89.setVisibility(spotify && engine89 ? View.VISIBLE : View.GONE);

        if (engineAbrp) {
            // Troubleshooting mode is intentionally never persisted.
            if (switchAbrpDebugLogging != null) {
                switchAbrpDebugLogging.setChecked(false);
                switchAbrpDebugLogging.setEnabled(true);
            }
            txtSelectedSlotTitle.setText("ABRP");
            txtSourceVersion.setText("Original ABRP " + BuildConfigData.ABRP_SUPPORTED_VERSION + " file (.apkm)");
            btnBrowse.setText("CHOOSE FILE");
            sourceApk = sourceFileForProfile("abrp");
            clearIconPreviews();
            refreshPackages();
            if (resetProgress) showProgress(0, "READY", "Choose the original ABRP 7.1.7 file, then install the patched version.");
            if (sourceApk.isFile()) validateStoredSource();
            else {
                setCurrentSourceValid(false);
                txtSourceStatus.setText("Choose the ABRP 7.1.7 file to start.");
            }
            updatePatchButton();
            return;
        }

        if (isWazeProfile()) {
            String version = currentTargetVersion();
            txtSelectedSlotTitle.setText(engineWaze59 ? "Waze 5.9 · Older / lighter" : "Waze 5.24");
            txtSourceVersion.setText(engineWaze59
                    ? "Waze 5.9.5.4 · older version — may run quicker on slower hardware · 2-architecture APKM"
                    : "Waze 5.24.0.1 · Android 10+ · arm64 · 480-640dpi APKM");
            btnBrowse.setText("BROWSE APKM");
            if (switchWazeWide != null) { switchWazeWide.setChecked(true); switchWazeWide.setEnabled(false); }
            applyWazeStaticText();
            loadingWazeUi = true;
            switchWazeDisableAudio.setChecked(prefs.getBoolean("waze_disable_audio", true));
            switchWazeDisableAudio.setEnabled(true);
            switchWazeRemoveMic.setChecked(prefs.getBoolean("waze_remove_microphone", true));
            switchWazeRemoveMic.setEnabled(true);
            switchWazeEv.setChecked(prefs.getBoolean("waze_enable_ev", true));
            switchWazeEv.setEnabled(true);
            // Deliberately never persist troubleshooting mode: every fresh selection starts safe/OFF.
            switchWazeDebugLogging.setChecked(false);
            switchWazeDebugLogging.setEnabled(true);
            switchWazeDebugLogging.setVisibility(View.VISIBLE);
            if (txtWazeDebugDetails != null) txtWazeDebugDetails.setVisibility(View.VISIBLE);
            loadingWazeUi = false;
            sourceApk = sourceFileForProfile(currentProfileCode());
            clearIconPreviews();
            refreshPackages();
            if (resetProgress) showProgress(0, "READY", "Choose Waze " + version + " and apply the BYD patch.");
            if (sourceApk.isFile()) validateStoredSource();
            else {
                setWazeValidation(currentProfileCode(), null);
                setCurrentSourceValid(false);
                txtSourceStatus.setText("No Waze " + version + " APKM selected.");
            }
            updatePatchButton();
            return;
        }

        btnBrowse.setText("BROWSE APKM");
        txtSlotsVersion.setText(engine89 ? "8.9" : "9.1");
        txtSourceVersion.setText(engine89
                ? "Spotify " + BuildConfigData.V89_SUPPORTED_VERSION + " · Android 5.0+ · arm64-v8a + x86 · 160-640dpi APKM"
                : "Spotify " + BuildConfigData.SUPPORTED_VERSION + " · Android 8.0+ · universal · 120-640dpi APKM");
        txtBrandingHint.setText("0° = original Spotify colours.");
        editAppName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(engine89 ? 24 : 32)});
        sourceApk = sourceFileForProfile(engine89 ? "89" : "91");
        clearIconPreviews();
        selectSlot(!radioSecondary.isChecked());
        applyClusterBridgeUi();
        refreshPackages();
        if (resetProgress) showProgress(0, "READY", "Switched to Spotify " + (engine89 ? "8.9" : "9.1") + ".");
        if (sourceApk.isFile()) validateStoredSource();
        else {
            setCurrentSourceValid(false);
            txtSourceStatus.setText(engine89 ? "No Spotify 8.9 APKM selected" : "No Spotify 9.1 APKM selected");
            updateIconPreview();
        }
    }

    private void applySourceButtonUi() {
        if (btnApkMirror == null || btnDiagnostics == null || btnRefresh == null) return;
        btnApkMirror.setVisibility(View.VISIBLE);
        btnApkMirror.setText(isWazeProfile() ? "APK MIRROR" : "APK MIRROR");
        btnDiagnostics.setText("DIAGNOSTICS");
        btnRefresh.setText("REFRESH");
        btnDiagnostics.setVisibility(View.VISIBLE);
        btnRefresh.setVisibility(View.VISIBLE);
    }

    private void applyProfileAccent() {
        int accent = engineAbrp ? ABRP_ACCENT : (isWazeProfile() ? WAZE_ACCENT : SPOTIFY_ACCENT);
        int accentDark = engineAbrp ? ABRP_ACCENT_DARK : (isWazeProfile() ? WAZE_ACCENT_DARK : SPOTIFY_ACCENT_DARK);

        ColorStateList controlTint = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked},
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{accent, accent, CONTROL_UNCHECKED});

        ColorStateList buttonTint = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{CONTROL_DISABLED, accent});

        RadioButton[] radios = {
                radioEngine91, radioEngine89, radioEngineWaze, radioEngineWaze59, radioEngineAbrp,
                radioPrimary, radioSecondary,
                radioPlayerLhs, radioPlayerRhs,
                radio89FontStock, radio89FontModerate, radio89FontLarge,
                radio89PanelLeft, radio89PanelRight
        };
        for (RadioButton radio : radios) {
            if (radio != null) radio.setButtonTintList(controlTint);
        }

        SwitchMaterial[] switches = {
                switchOptimise, switchBydLayout, switchAutoResume, switchIconBadge, switchBydClusterBridge,
                switch89PreventPortrait, switchWazeWide, switchWazeDisableAudio, switchWazeEv, switchWazeRemoveMic,
                switchAbrpDirectBattery, switchAbrpAutoDayNight, switchAbrpNativeTts
        };
        for (SwitchMaterial sw : switches) {
            if (sw != null) {
                sw.setThumbTintList(controlTint);
                sw.setTrackTintList(new ColorStateList(
                        new int[][]{
                                new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked},
                                new int[]{android.R.attr.state_checked},
                                new int[]{}
                        },
                        new int[]{withAlpha(accent, 105), withAlpha(accent, 105), Color.rgb(70, 70, 70)}));
            }
        }

        SwitchMaterial[] debugSwitches = {switchWazeDebugLogging, switchAbrpDebugLogging};
        for (SwitchMaterial debugSwitch : debugSwitches) {
            if (debugSwitch == null) continue;
            ColorStateList debugTint = new ColorStateList(
                    new int[][]{
                            new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked},
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{DEBUG_RED_DARK, DEBUG_RED, CONTROL_UNCHECKED});
            debugSwitch.setThumbTintList(debugTint);
            debugSwitch.setTrackTintList(new ColorStateList(
                    new int[][]{
                            new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked},
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{withAlpha(DEBUG_RED_DARK, 105), withAlpha(DEBUG_RED, 120), Color.rgb(70, 70, 70)}));
            debugSwitch.setTextColor(DEBUG_RED);
        }

        if (seekIconHue != null) {
            seekIconHue.setProgressTintList(ColorStateList.valueOf(accent));
            seekIconHue.setThumbTintList(ColorStateList.valueOf(accent));
        }
        if (seekInterfaceScale != null) {
            seekInterfaceScale.setProgressTintList(ColorStateList.valueOf(accent));
            seekInterfaceScale.setThumbTintList(ColorStateList.valueOf(accent));
        }
        if (txtProgressStage != null) txtProgressStage.setTextColor(accent);
        if (txtSlotsVersion != null) txtSlotsVersion.setTextColor(accent);
        if (progressBar != null) {
            progressBar.setProgressTintList(ColorStateList.valueOf(accent));
            progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
            View parent = (View) progressBar.getParent();
            if (parent != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setColor(engineAbrp ? Color.rgb(45, 21, 21)
                        : (isWazeProfile() ? Color.rgb(15, 34, 42) : Color.rgb(20, 36, 26)));
                bg.setCornerRadius(dpToPx(10));
                bg.setStroke(Math.max(1, Math.round(dpToPx(1))), accentDark);
                parent.setBackground(bg);
            }
        }

        int[] accentButtonIds = {
                R.id.btnPatchInstall, R.id.btnPatchSaveApkm, R.id.btnPrimaryInfo, R.id.btnSecondaryInfo, R.id.btnWazeInfo, R.id.btnAbrpInfo,
                R.id.btnApkMirror, R.id.btnBrowse, R.id.btnDiagnostics, R.id.btnRefresh
        };
        for (int id : accentButtonIds) {
            Button button = findViewById(id);
            if (button != null) {
                button.setBackgroundTintList(buttonTint);
                button.setTextColor(engineAbrp ? Color.WHITE : Color.BLACK);
            }
        }
    }

    private int currentAccent() {
        return engineAbrp ? ABRP_ACCENT : (isWazeProfile() ? WAZE_ACCENT : SPOTIFY_ACCENT);
    }

    private int currentAccentTextColor() {
        return engineAbrp ? Color.WHITE : Color.BLACK;
    }

    private String currentProductName() {
        if (engineAbrp) return "ABRP";
        if (engineWaze59) return "Waze 5.9";
        if (engineWaze) return "Waze 5.24";
        return engine89 ? "Spotify 8.9" : "Spotify 9.1";
    }

    private String currentSourceDescription() {
        String product = engineAbrp ? "ABRP" : (isWazeProfile() ? "Waze" : "Spotify");
        return product + " " + currentTargetVersion() + " · APKM/APK source";
    }

    private String[] currentSourceKeywords() {
        if (engineAbrp) return new String[]{"abrp", "iternio", "routeplanner"};
        if (isWazeProfile()) return new String[]{"waze"};
        return new String[]{"spotify"};
    }

    private void openBuiltInBrowser() {
        prefs.edit().putString("browse_method", BrowseChoiceDialog.METHOD_BUILT_IN).apply();
        Intent i = new Intent(this, FileBrowserActivity.class)
                .putExtra(FileBrowserActivity.EXTRA_TITLE, "SELECT " + currentProductName().toUpperCase(Locale.ROOT) + " FILE")
                .putExtra(FileBrowserActivity.EXTRA_SUBTITLE, "Looking for " + currentSourceDescription())
                .putExtra(FileBrowserActivity.EXTRA_ACCENT, currentAccent())
                .putExtra(FileBrowserActivity.EXTRA_ON_ACCENT, currentAccentTextColor())
                .putExtra(FileBrowserActivity.EXTRA_MATCH_VERSIONS, new String[]{currentTargetVersion()})
                .putExtra(FileBrowserActivity.EXTRA_MATCH_KEYWORDS, currentSourceKeywords());
        pickBuiltIn.launch(i);
    }

    private void openExternalPicker() {
        prefs.edit().putString("browse_method", BrowseChoiceDialog.METHOD_EXTERNAL).apply();
        // PR #7: ACTION_OPEN_DOCUMENT commonly resolves only to Albums on DiLink 3.
        // Third-party file managers generally expose ACTION_GET_CONTENT instead.
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            pickApk.launch(Intent.createChooser(i, "Choose the original " + currentProductName() + " file"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No compatible file manager found. Use the built-in browser instead.", Toast.LENGTH_LONG).show();
        }
    }

    private int withAlpha(int rgb, int alpha) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private boolean isClusterBridgeSelected() {
        return !isWazeProfile() && !engineAbrp && switchBydClusterBridge != null && switchBydClusterBridge.isChecked();
    }

    private void applyClusterBridgeUi() {
        if (switchBydClusterBridge == null) return;
        boolean spotify = !isWazeProfile() && !engineAbrp;
        boolean available = spotify;
        if (txtBydClusterTitle != null) txtBydClusterTitle.setVisibility(available ? View.VISIBLE : View.GONE);
        switchBydClusterBridge.setVisibility(available ? View.VISIBLE : View.GONE);
        if (txtBydClusterHint != null) txtBydClusterHint.setVisibility(available ? View.VISIBLE : View.GONE);
        boolean cluster = available && switchBydClusterBridge.isChecked();
        radioSecondary.setEnabled(true);
        if (slotSecondaryCard != null) {
            slotSecondaryCard.setEnabled(true);
            slotSecondaryCard.setAlpha(1f);
        }

        refreshPackages();
        updatePatchButton();
    }

    private void selectSlot(boolean primary) {
        radioPrimary.setChecked(primary);
        radioSecondary.setChecked(!primary);
        updateSlotUi();
    }

    private void updateSlotUi() {
        loadingSlotUi = true;
        if (isWazeProfile() || engineAbrp) {
            txtSelectedSlotTitle.setText(engineAbrp ? "ABRP" : (engineWaze59 ? "Waze 5.9" : "Waze 5.24"));
            loadingSlotUi = false;
            updatePatchButton();
            return;
        }
        boolean primary = radioPrimary.isChecked();
        if (engine89) {
            String prefix = primary ? "v89_primary" : "v89_secondary";
            String saved89Name = prefs.getString(prefix + "_name", primary ? "SpotifyPlus" : "SpotifyPlus2");
            if (primary && "SpotifyPlus8".equals(saved89Name)) saved89Name = "SpotifyPlus";
            if (!primary && "SpotifyPlus8-2".equals(saved89Name)) saved89Name = "SpotifyPlus2";
            editAppName.setText(saved89Name);
            String font = prefs.getString(prefix + "_font", "moderate");
            radio89FontStock.setChecked("stock".equals(font));
            radio89FontModerate.setChecked("moderate".equals(font));
            radio89FontLarge.setChecked("large".equals(font));
            String side = prefs.getString(prefix + "_panel", "right");
            radio89PanelLeft.setChecked("left".equals(side));
            radio89PanelRight.setChecked(!"left".equals(side));
            switch89PreventPortrait.setChecked(prefs.getBoolean(prefix + "_prevent_portrait", true));
            int hue = prefs.getInt(prefix + "_icon_hue", 0);
            boolean badge = prefs.getBoolean(prefix + "_icon_badge", false);
            seekIconHue.setProgress(IconBrandingUtil.normaliseHue(hue));
            txtIconHue.setText(IconBrandingUtil.normaliseHue(hue) + "°");
            switchIconBadge.setChecked(badge);
            txtSelectedSlotTitle.setText("Spotify 8.9");
        } else {
            String prefix = primary ? "primary" : "secondary";
            editAppName.setText(prefs.getString(prefix + "_name", primary ? "SpotifyPlus" : "SpotifyPlus2"));
            String scale = prefs.getString(prefix + "_scale", Integer.toString(SCALE_DEFAULT_PERCENT));
            int scalePercent = SCALE_DEFAULT_PERCENT;
            try {
                scalePercent = Integer.parseInt(scale);
            } catch (NumberFormatException ignored) {}
            setScalePercent(scalePercent);
            String playerSide = prefs.getString(prefix + "_player_side", "rhs");
            radioPlayerLhs.setChecked("lhs".equals(playerSide));
            radioPlayerRhs.setChecked(!"lhs".equals(playerSide));
            switchBydLayout.setChecked(prefs.getBoolean(prefix + "_byd_layout", true));
            switchOptimise.setChecked(prefs.getBoolean(prefix + "_optimise", true));
            switchAutoResume.setChecked(prefs.getBoolean(prefix + "_auto_resume", true));
            int hue = prefs.getInt(prefix + "_icon_hue", 0);
            boolean badge = prefs.getBoolean(prefix + "_icon_badge", false);
            seekIconHue.setProgress(IconBrandingUtil.normaliseHue(hue));
            txtIconHue.setText(IconBrandingUtil.normaliseHue(hue) + "°");
            switchIconBadge.setChecked(badge);
            txtOptimiseHint.setText("Installs matched DexMetadata for faster startup.");
            txtSelectedSlotTitle.setText("Spotify 9.1");
        }

        loadingSlotUi = false;
        updateIconPreview();
        updatePatchButton();
    }

    private void saveCurrentSlotUi() {
        if (isWazeProfile() || engineAbrp || prefs == null || radioPrimary == null) return;
        boolean secondary = radioSecondary.isChecked();
        String appName = editAppName.getText().toString().trim();
        if (engine89) {
            String prefix = secondary ? "v89_secondary" : "v89_primary";
            prefs.edit()
                    .putString(prefix + "_name", appName)
                    .putString(prefix + "_font", selected89FontCode())
                    .putString(prefix + "_panel", radio89PanelLeft.isChecked() ? "left" : "right")
                    .putBoolean(prefix + "_prevent_portrait", switch89PreventPortrait.isChecked())
                    .putInt(prefix + "_icon_hue", seekIconHue.getProgress())
                    .putBoolean(prefix + "_icon_badge", switchIconBadge.isChecked())
                    .apply();
        } else {
            String prefix = secondary ? "secondary" : "primary";
            prefs.edit()
                    .putString(prefix + "_name", appName)
                    .putString(prefix + "_scale", selectedScaleCode())
                    .putBoolean(prefix + "_byd_layout", switchBydLayout.isChecked())
                    .putBoolean(prefix + "_optimise", switchOptimise.isChecked())
                    .putBoolean(prefix + "_auto_resume", switchAutoResume.isChecked())
                    .putString(prefix + "_player_side", radioPlayerLhs.isChecked() ? "lhs" : "rhs")
                    .putInt(prefix + "_icon_hue", seekIconHue.getProgress())
                    .putBoolean(prefix + "_icon_badge", switchIconBadge.isChecked())
                    .apply();
        }
    }

    private void clearIconPreviews() {
        if (renderedIconPreview != null && !renderedIconPreview.isRecycled()) renderedIconPreview.recycle();
        if (stockIconPreview != null && !stockIconPreview.isRecycled()) stockIconPreview.recycle();
        renderedIconPreview = null;
        stockIconPreview = null;
    }

    private void ensureStockIconPreview() {
        if (isWazeProfile() || engineAbrp) return;
        if (stockIconPreview == null && sourceApk != null && sourceApk.isFile()) {
            stockIconPreview = IconBrandingUtil.loadStockPreviewFromApkm(sourceApk);
        }
    }

    private void updateIconPreview() {
        if (isWazeProfile() || engineAbrp || imgIconPreview == null) return;
        ensureStockIconPreview();
        if (stockIconPreview != null) {
            android.graphics.Bitmap preview = engine89
                    ? Spotify89BrandingUtil.render(stockIconPreview, seekIconHue.getProgress(), switchIconBadge.isChecked())
                    : IconBrandingUtil.renderPreview(stockIconPreview, seekIconHue.getProgress(), switchIconBadge.isChecked());
            android.graphics.Bitmap oldPreview = renderedIconPreview;
            renderedIconPreview = preview;
            imgIconPreview.setImageBitmap(preview);
            if (oldPreview != null && oldPreview != stockIconPreview && oldPreview != preview && !oldPreview.isRecycled()) oldPreview.recycle();
            imgIconPreview.setAlpha(1f);
        } else {
            imgIconPreview.setImageResource(R.drawable.ic_manager);
            imgIconPreview.setAlpha(0.45f);
        }
    }

    private int selectedScalePercent() {
        if (seekInterfaceScale == null) return SCALE_DEFAULT_PERCENT;
        int percent = SCALE_MIN_PERCENT + (seekInterfaceScale.getProgress() * SCALE_STEP_PERCENT);
        return Math.max(SCALE_MIN_PERCENT, Math.min(SCALE_MAX_PERCENT, percent));
    }

    private void setScalePercent(int percent) {
        int clamped = Math.max(SCALE_MIN_PERCENT, Math.min(SCALE_MAX_PERCENT, percent));
        int snapped = SCALE_MIN_PERCENT
                + Math.round((clamped - SCALE_MIN_PERCENT) / (float) SCALE_STEP_PERCENT) * SCALE_STEP_PERCENT;
        snapped = Math.max(SCALE_MIN_PERCENT, Math.min(SCALE_MAX_PERCENT, snapped));
        if (seekInterfaceScale != null) {
            seekInterfaceScale.setProgress((snapped - SCALE_MIN_PERCENT) / SCALE_STEP_PERCENT);
        }
        updateScaleLabel();
    }

    private void updateScaleLabel() {
        if (txtScaleValue != null) txtScaleValue.setText(selectedScalePercent() + "%");
    }

    private float selectedScaleFactor() {
        return selectedScalePercent() / 100.0f;
    }

    private String selectedScaleCode() {
        return Integer.toString(selectedScalePercent());
    }

    private String selected89FontCode() {
        if (radio89FontLarge.isChecked()) return "large";
        if (radio89FontStock.isChecked()) return "stock";
        return "moderate";
    }

    private Spotify89ResourceUtil.FontPreset selected89FontPreset() {
        if (radio89FontLarge.isChecked()) return Spotify89ResourceUtil.FontPreset.LARGE;
        if (radio89FontStock.isChecked()) return Spotify89ResourceUtil.FontPreset.STOCK;
        return Spotify89ResourceUtil.FontPreset.MODERATE;
    }

    private File sourceFile(boolean for89) { return sourceFileForProfile(for89 ? "89" : "91"); }

    private File sourceFileForProfile(String profile) {
        File dir = new File(getFilesDir(), "source");
        if ("waze24".equals(profile) || "waze".equals(profile)) return new File(dir, "waze_source.apkm");
        if ("waze59".equals(profile)) return new File(dir, "waze_5.9.5.4.apkm");
        if ("abrp".equals(profile)) return new File(dir, "abrp_7.1.7.apkm");
        return new File(dir, "89".equals(profile) ? "spotify_8.9.76.538.apkm" : "spotify_9.1.82.2161.apkm");
    }

    private String currentProfileCode() {
        if (engineAbrp) return "abrp";
        if (engineWaze59) return "waze59";
        if (engineWaze) return "waze24";
        return engine89 ? "89" : "91";
    }

    private String currentPrimaryPackage() {
        return engineAbrp ? BuildConfigData.ABRP_PACKAGE
                : (isWazeProfile() ? BuildConfigData.WAZE_PACKAGE
                : (engine89 ? BuildConfigData.V89_PRIMARY_PACKAGE : BuildConfigData.PRIMARY_PACKAGE));
    }

    private String currentSecondaryPackage() {
        return engineAbrp ? BuildConfigData.ABRP_PACKAGE
                : (isWazeProfile() ? BuildConfigData.WAZE_PACKAGE
                : (engine89 ? BuildConfigData.V89_SECONDARY_PACKAGE : BuildConfigData.SECONDARY_PACKAGE));
    }

    private String currentTargetVersion() {
        if (engineAbrp) return BuildConfigData.ABRP_SUPPORTED_VERSION;
        if (engineWaze59) return BuildConfigData.WAZE59_SUPPORTED_VERSION;
        if (engineWaze) return BuildConfigData.WAZE_SUPPORTED_VERSION;
        return engine89 ? BuildConfigData.V89_SUPPORTED_VERSION : BuildConfigData.SUPPORTED_VERSION;
    }

    private long currentTargetVersionCode() {
        if (engineAbrp) return BuildConfigData.ABRP_SUPPORTED_VERSION_CODE;
        if (engineWaze59) return BuildConfigData.WAZE59_SUPPORTED_VERSION_CODE;
        if (engineWaze) return BuildConfigData.WAZE_SUPPORTED_VERSION_CODE;
        return engine89 ? BuildConfigData.V89_SUPPORTED_VERSION_CODE : BuildConfigData.SUPPORTED_VERSION_CODE;
    }

    private String currentApkMirrorUrl() {
        if (engineAbrp) return BuildConfigData.ABRP_APKMIRROR_URL;
        if (engineWaze59) return BuildConfigData.WAZE59_APKMIRROR_URL;
        if (engineWaze) return BuildConfigData.WAZE_APKMIRROR_URL;
        return engine89 ? BuildConfigData.V89_APKMIRROR_URL : BuildConfigData.APKMIRROR_URL;
    }

    private boolean currentSourceValid() {
        if (engineAbrp) return sourceValidAbrp;
        if (engineWaze59) return sourceValidWaze59;
        if (engineWaze) return sourceValidWaze;
        return engine89 ? sourceValid89 : sourceValid91;
    }

    private void setCurrentSourceValid(boolean valid) {
        if (engineAbrp) sourceValidAbrp = valid;
        else if (engineWaze59) sourceValidWaze59 = valid;
        else if (engineWaze) sourceValidWaze = valid;
        else if (engine89) sourceValid89 = valid;
        else sourceValid91 = valid;
    }

    private void setSourceValid(String profile, boolean valid) {
        if ("abrp".equals(profile)) sourceValidAbrp = valid;
        else if ("waze59".equals(profile)) sourceValidWaze59 = valid;
        else if ("waze24".equals(profile) || "waze".equals(profile)) sourceValidWaze = valid;
        else if ("89".equals(profile)) sourceValid89 = valid;
        else sourceValid91 = valid;
    }

    private boolean isWazeProfileCode(String profile) {
        return "waze24".equals(profile) || "waze59".equals(profile) || "waze".equals(profile);
    }

    private WazePatchEngine.Validation currentWazeValidation() {
        return engineWaze59 ? waze59SourceValidation : waze24SourceValidation;
    }

    private void setWazeValidation(String profile, WazePatchEngine.Validation validation) {
        if ("waze59".equals(profile)) waze59SourceValidation = validation;
        else if ("waze24".equals(profile) || "waze".equals(profile)) waze24SourceValidation = validation;
    }

    private boolean isSupportedWazeVersion(WazePatchEngine.Validation validation, String profile) {
        if (validation == null || !validation.ok) return false;
        if ("waze59".equals(profile)) {
            return BuildConfigData.WAZE59_SUPPORTED_VERSION.equals(validation.versionName)
                    && validation.versionCode == BuildConfigData.WAZE59_SUPPORTED_VERSION_CODE
                    && validation.exactPatchProfile;
        }
        return BuildConfigData.WAZE_SUPPORTED_VERSION.equals(validation.versionName)
                && validation.versionCode == BuildConfigData.WAZE_SUPPORTED_VERSION_CODE
                && validation.exactPatchProfile;
    }

    private boolean isSupportedWazeVersion(WazePatchEngine.Validation validation) {
        return isSupportedWazeVersion(validation, currentProfileCode());
    }

    private String supportedWazeDetail(WazePatchEngine.Validation validation) {
        String label = BuildConfigData.WAZE59_SUPPORTED_VERSION.equals(validation.versionName)
                ? "older / lighter" : "current";
        return "SUPPORTED · Waze " + validation.displayVersion() + " · " + label
                + " · " + validation.apkCount + " APK splits";
    }

    private void applyWazeStaticText() {
        String wazeTitle = engineWaze59 ? "WAZE 5.9 · OLDER / LIGHTER" : "WAZE 5.24";
        if (txtWazeProfileTitle != null) txtWazeProfileTitle.setText(wazeTitle);
        if (txtWazeSettingsTitle != null) txtWazeSettingsTitle.setText(wazeTitle);
        if (txtWazeWideDetails != null) txtWazeWideDetails.setText(engineWaze59 ? WAZE59_USER_DESCRIPTION : WAZE24_USER_DESCRIPTION);
        if (txtWazeAudioDetails != null) txtWazeAudioDetails.setText(R.string.waze_disable_audio_details);
        if (txtWazeMicDetails != null) txtWazeMicDetails.setText(R.string.waze_remove_microphone_details);
        if (txtWazeEvDetails != null) txtWazeEvDetails.setText(R.string.waze_ev_details);
        if (txtWazeDebugDetails != null) txtWazeDebugDetails.setText(R.string.waze_debug_logging_details);
    }

    private void importSource(Uri uri) {
        final String profile = currentProfileCode();
        final File target = sourceFileForProfile(profile);
        final boolean waze = isWazeProfileCode(profile);
        final boolean abrp = "abrp".equals(profile);
        final String product = abrp ? "ABRP 7.1.7 file"
                : (waze ? "Waze APKM" : ("89".equals(profile) ? "Spotify 8.9 APKM" : "Spotify 9.1 APKM"));
        setBusy(true, "Copying selected " + product + "…");
        showIndeterminate("VERIFYING SOURCE", "Copying and checking " + product + "…");
        worker.execute(() -> {
            try {
                File dir = target.getParentFile();
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create source directory");
                File tmp = new File(dir, "source_" + profile + ".tmp");
                InputStream selectedIn = "file".equalsIgnoreCase(uri.getScheme())
                        ? new FileInputStream(new File(uri.getPath()))
                        : getContentResolver().openInputStream(uri);
                try (InputStream in = selectedIn; OutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IOException("Cannot read selected file");
                    IoUtil.copy(in, out);
                }

                // A saved .apkm/.apks produced by this patcher can be selected with the same Browse button.
                // Reinstall it directly instead of trying to validate it as a stock source APKM.
                if (PatchedBundleArchive.isPatchedBundle(tmp)) {
                    PatchedBundleArchive.Info info = PatchedBundleArchive.readInfo(tmp);
                    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        if (!tmp.delete()) tmp.deleteOnExit();
                        runOnUiThread(() -> {
                            setBusy(false, "Allow installs from BYD Multi Patcher, then select the saved patched bundle again.");
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        });
                        return;
                    }
                    PackageInfo existing = installedPackageInfo(info.packageName);
                    if (existing != null && !isSignedByManager(info.packageName)) {
                        if (!tmp.delete()) tmp.deleteOnExit();
                        runOnUiThread(() -> {
                            setBusy(false, "Existing app has a different signature.");
                            showSavedBundleSignatureDialog(info);
                        });
                        return;
                    }
                    File savedInstallDir = new File(getCacheDir(), "saved_bundle_install");
                    PatchedBundleArchive.Extracted extracted = PatchedBundleArchive.extract(tmp, savedInstallDir);
                    if (!tmp.delete()) tmp.deleteOnExit();
                    showProgressFromWorker(95, "INSTALLING SAVED PATCH", "Installing " + info.displayName + " from the saved patched bundle…");
                    PackageInstallHelper.installSplitApks(this, extracted.apks, extracted.dexMetadata, info.packageName);
                    runOnUiThread(() -> showProgress(98, "INSTALLING", "Confirm the Android installation prompt if shown, then wait for the saved patched build to install."));
                    return;
                }
                String detail;
                WazePatchEngine.Validation selectedWazeValidation = null;
                if (abrp) {
                    AbrpSourceValidator.Validation validation = AbrpSourceValidator.validateSource(tmp);
                    if (!validation.ok) { tmp.delete(); throw new IllegalArgumentException(validation.detail); }
                    detail = "✓ Correct ABRP 7.1.7 file selected";
                } else if (waze) {
                    WazePatchEngine.Validation validation = WazePatchEngine.validateSource(tmp);
                    if (!validation.ok) { tmp.delete(); throw new IllegalArgumentException(validation.detail); }
                    if (!isSupportedWazeVersion(validation, profile)) {
                        tmp.delete();
                        throw new IllegalArgumentException("Wrong Waze source for this profile. Selected profile requires Waze "
                                + ("waze59".equals(profile) ? BuildConfigData.WAZE59_SUPPORTED_VERSION : BuildConfigData.WAZE_SUPPORTED_VERSION)
                                + ", but the file is " + validation.displayVersion() + ".");
                    }
                    selectedWazeValidation = validation;
                    detail = "✓ " + supportedWazeDetail(validation);
                } else if ("89".equals(profile)) {
                    ApkPatchEngine89.Validation validation = ApkPatchEngine89.validateSource(tmp);
                    if (!validation.ok) { tmp.delete(); throw new IllegalArgumentException(validation.detail); }
                    detail = "✓ " + validation.detail;
                } else {
                    ApkPatchEngine91.Validation validation = ApkPatchEngine91.validateSource(tmp);
                    if (!validation.ok) { tmp.delete(); throw new IllegalArgumentException(validation.detail); }
                    detail = "✓ " + validation.detail;
                }
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace stored source file");
                if (!tmp.renameTo(target)) throw new IOException("Cannot store selected source file");
                if (waze) setWazeValidation(profile, selectedWazeValidation);
                setSourceValid(profile, true);
                final WazePatchEngine.Validation finalWazeValidation = selectedWazeValidation;
                runOnUiThread(() -> {
                    if (currentProfileCode().equals(profile)) {
                        sourceApk = target;
                        txtSourceStatus.setText(detail);
                        if (waze && finalWazeValidation != null) updateWazeSourceHeader(finalWazeValidation);
                        clearIconPreviews();
                        updateIconPreview();
                        updatePatchButton();
                        setBusy(false, "Source ready.");
                        if (abrp) {
                            showProgress(0, "FILE READY", "ABRP file checked. Tap Install to add the BYD display fixes.");
                        } else if (waze && finalWazeValidation != null) {
                            showProgress(0, "READY", "Supported Waze " + finalWazeValidation.displayVersion() + " verified. Ready to patch.");
                        } else {
                            showProgress(0, "READY", "Spotify source verified. Ready to patch.");
                        }
                    }
                });
            } catch (Exception e) {
                if (waze) setWazeValidation(profile, null);
                setSourceValid(profile, false);
                runOnUiThread(() -> {
                    if (currentProfileCode().equals(profile)) {
                        txtSourceStatus.setText("✕ " + e.getMessage());
                        clearIconPreviews();
                        updateIconPreview();
                        setBusy(false, "Source verification failed.");
                        showProgress(0, "SOURCE ERROR", "Source verification failed.");
                    }
                });
            }
        });
    }

    private void validateStoredSource() {
        final String profile = currentProfileCode();
        final File target = sourceFileForProfile(profile);
        if (!target.isFile()) {
            if (isWazeProfileCode(profile)) setWazeValidation(profile, null);
            setSourceValid(profile, false);
            txtSourceStatus.setText(engineAbrp ? "Choose the ABRP 7.1.7 file to start."
                    : (isWazeProfile() ? "No Waze " + currentTargetVersion() + " APKM selected."
                    : (engine89 ? "No Spotify 8.9 APKM selected" : "No Spotify 9.1 APKM selected")));
            return;
        }
        worker.execute(() -> {
            boolean valid = false;
            String detail;
            try {
                if ("abrp".equals(profile)) {
                    AbrpSourceValidator.Validation v = AbrpSourceValidator.validateSource(target);
                    valid = v.ok;
                    detail = v.ok ? "✓ Correct ABRP 7.1.7 file selected" : "✕ " + v.detail;
                } else if (isWazeProfileCode(profile)) {
                    WazePatchEngine.Validation v = WazePatchEngine.validateSource(target);
                    valid = v.ok && isSupportedWazeVersion(v, profile);
                    setWazeValidation(profile, valid ? v : null);
                    if (!v.ok) {
                        detail = "✕ " + v.detail;
                    } else if (!valid) {
                        String required = "waze59".equals(profile) ? BuildConfigData.WAZE59_SUPPORTED_VERSION : BuildConfigData.WAZE_SUPPORTED_VERSION;
                        detail = "✕ Wrong Waze version for this profile. Required: " + required + "; selected: " + v.displayVersion();
                    } else {
                        detail = "✓ Stored " + supportedWazeDetail(v);
                    }
                } else if ("89".equals(profile)) {
                    ApkPatchEngine89.Validation v = ApkPatchEngine89.validateSource(target);
                    valid = v.ok;
                    detail = v.ok ? "✓ Stored " + v.detail : "✕ " + v.detail;
                } else {
                    ApkPatchEngine91.Validation v = ApkPatchEngine91.validateSource(target);
                    valid = v.ok;
                    detail = v.ok ? "✓ Stored " + v.detail : "✕ " + v.detail;
                }
            } catch (Exception e) {
                if (isWazeProfileCode(profile)) setWazeValidation(profile, null);
                detail = "Source check failed: " + e.getMessage();
            }
            final boolean result = valid;
            final String message = detail;
            final WazePatchEngine.Validation storedWazeValidation = isWazeProfileCode(profile) ? ("waze59".equals(profile) ? waze59SourceValidation : waze24SourceValidation) : null;
            setSourceValid(profile, result);
            runOnUiThread(() -> {
                if (currentProfileCode().equals(profile)) {
                    sourceApk = target;
                    txtSourceStatus.setText(message);
                    if (isWazeProfileCode(profile) && storedWazeValidation != null) updateWazeSourceHeader(storedWazeValidation);
                    clearIconPreviews();
                    updateIconPreview();
                    updatePatchButton();
                }
            });
        });
    }


    private void updateWazeSourceHeader(WazePatchEngine.Validation validation) {
        if (validation == null || txtSourceVersion == null) return;
        if (isSupportedWazeVersion(validation)) {
            if (engineWaze59) {
                txtSourceVersion.setText("Waze " + validation.displayVersion()
                        + " · LEGACY PROFILE · slower hardware · exact 2-architecture APKM");
            } else {
                txtSourceVersion.setText("Waze " + validation.displayVersion()
                        + " · CURRENT PROFILE · Android 10+ · arm64 · 480-640dpi APKM");
            }
        } else {
            txtSourceVersion.setText("Wrong Waze source · this profile requires " + currentTargetVersion());
        }
    }

    private void patchAndInstall() {
        if (!currentSourceValid()) {
            String needed = engineAbrp
                    ? "Choose the original ABRP 7.1.7 file first."
                    : isWazeProfile()
                    ? "Select a valid Waze " + currentTargetVersion() + " APKM for this profile first."
                    : "Select and verify the supported Spotify " + (engine89 ? "8.9" : "9.1") + " APKM first.";
            Toast.makeText(this, needed, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Allow installs from BYD Multi Patcher, then press Patch & Install again.", Toast.LENGTH_LONG).show();
            return;
        }

        if (engineAbrp) {
            PackageInfo installed = installedPackageInfo(BuildConfigData.ABRP_PACKAGE);
            if (installed != null && (!isSignedByManager(BuildConfigData.ABRP_PACKAGE)
                    || installed.getLongVersionCode() > BuildConfigData.ABRP_SUPPORTED_VERSION_CODE)) {
                showAbrpReinstallDialog(installed);
                return;
            }
            patchAndInstallAbrp();
            return;
        }

        if (isWazeProfile()) {
            WazePatchEngine.Validation validation = currentWazeValidation();
            if (validation == null || !validation.ok) {
                Toast.makeText(this, "Waze source is still being checked. Press Refresh if needed.", Toast.LENGTH_LONG).show();
                return;
            }
            if (!isSupportedWazeVersion(validation)) {
                Toast.makeText(this, "This profile requires Waze " + currentTargetVersion()
                        + "; selected source is " + validation.displayVersion() + ".", Toast.LENGTH_LONG).show();
                return;
            }
            continueWazePatch(validation);
            return;
        }

        String selectedPkg = radioSecondary.isChecked() ? currentSecondaryPackage() : currentPrimaryPackage();
        PackageInfo installedInfo = installedPackageInfo(selectedPkg);
        if (installedInfo != null && installedInfo.getLongVersionCode() > currentTargetVersionCode()) {
            showProfileDowngradeDialog(selectedPkg, installedInfo.versionName);
            return;
        }
        continueSpotifyPatchAfterChecks();
    }

    private void patchAndSaveApkm() {
        if (!currentSourceValid()) {
            String needed = engineAbrp
                    ? "Choose the original ABRP 7.1.7 APKM first."
                    : isWazeProfile()
                    ? "Select a valid Waze " + currentTargetVersion() + " APKM first."
                    : "Select and verify the supported Spotify " + (engine89 ? "8.9" : "9.1") + " APKM first.";
            Toast.makeText(this, needed, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT == 28 && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 9142);
            Toast.makeText(this, "Allow storage access, then press Patch & Save APKM again.", Toast.LENGTH_LONG).show();
            return;
        }

        if (engineAbrp) {
            patchAbrp(true);
            return;
        }
        if (isWazeProfile()) {
            WazePatchEngine.Validation validation = currentWazeValidation();
            if (validation == null || !validation.ok || !isSupportedWazeVersion(validation)) {
                Toast.makeText(this, "Select the Waze " + currentTargetVersion() + " APKM for this profile first.", Toast.LENGTH_LONG).show();
                return;
            }
            patchWaze(validation, true);
            return;
        }

        saveCurrentSlotUi();
        if (engine89) patchSpotify89(true); else patchSpotify91(true);
    }

    private void continueSpotifyPatchAfterChecks() {
        saveCurrentSlotUi();
        if (engine89) patchAndInstall89(); else patchAndInstall91();
    }

    private void patchAndInstallAbrp() { patchAbrp(false); }

    private void patchAbrp(boolean saveOnly) {
        final File source = sourceFileForProfile("abrp");
        final boolean directBydBattery = switchAbrpDirectBattery != null && switchAbrpDirectBattery.isChecked();
        final boolean autoDayNightDefault = switchAbrpAutoDayNight != null && switchAbrpAutoDayNight.isChecked();
        final boolean nativeBydTts = switchAbrpNativeTts != null && switchAbrpNativeTts.isChecked();
        final boolean enableDebugLogging = switchAbrpDebugLogging != null && switchAbrpDebugLogging.isChecked();
        String abrpPatchSummary = (saveOnly ? "Preparing ABRP APKM export" : "Preparing ABRP display & location improvements")
                + (directBydBattery ? ", direct BYD battery data" : "")
                + (autoDayNightDefault ? ", Automatic Day/Night default" : "")
                + (nativeBydTts ? ", BYD native multilingual Cerence TTS" : "")
                + (enableDebugLogging ? ", privacy-safe 5-minute debug log" : "") + "…";
        setBusy(true, abrpPatchSummary);
        showProgress(5, "PREPARING", "Checking which ABRP files this device needs…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "build_abrp");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create ABRP build directory");
                File extractedDir = new File(work, "apks");
                List<File> signedApks = AbrpInstallEngine.prepareInstallBundle(this, source, extractedDir,
                        directBydBattery, autoDayNightDefault, nativeBydTts, enableDebugLogging, this::postPatchDetail);
                String readyDetail = "Display/location fixes are ready"
                        + (directBydBattery ? ", with direct BYD battery injection" : "")
                        + (autoDayNightDefault ? ", and fresh-install Automatic Day/Night appearance" : "")
                        + (nativeBydTts ? ", and BYD native Cerence TTS with installed voice selection" : "")
                        + (enableDebugLogging ? ", and privacy-safe startup diagnostics" : "") + ".";
                showProgressFromWorker(90, "ABRP PATCH READY", readyDetail);
                if (saveOnly) {
                    showProgressFromWorker(95, "SAVING APKM", "Saving patched ABRP APKM to Downloads/BYD-Multi-Patcher…");
                    String savedPath = PatchedBundleArchive.saveApkmToDownloads(this, source, signedApks, null,
                            "ABRP", BuildConfigData.ABRP_SUPPORTED_VERSION, BuildConfigData.ABRP_PACKAGE,
                            enableDebugLogging ? "DEBUG-byd" : "byd", "ABRP",
                            "directBattery=" + directBydBattery + ";autoDayNight=" + autoDayNightDefault
                                    + ";nativeTts=" + nativeBydTts + ";debug5min=" + enableDebugLogging);
                    runOnUiThread(() -> {
                        setBusy(false, "Patched APKM saved: " + savedPath);
                        showProgress(100, "APKM SAVED", savedPath);
                        Toast.makeText(this, "Patched ABRP APKM saved to Downloads/BYD-Multi-Patcher.", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                showProgressFromWorker(95, "STARTING INSTALL", "Asking Android to install ABRP…");
                PackageInstallHelper.installSplitApks(this, signedApks, BuildConfigData.ABRP_PACKAGE);
                runOnUiThread(() -> showProgress(98, "INSTALLING",
                        "Confirm the Android installation prompt if shown, then wait for ABRP installation to complete."));
            } catch (Exception e) { reportPatchFailure(e); }
        });
    }

    private void continueWazePatch(WazePatchEngine.Validation validation) {
        PackageInfo installed = installedPackageInfo(BuildConfigData.WAZE_PACKAGE);
        if (installed != null && (!isSignedByManager(BuildConfigData.WAZE_PACKAGE)
                || installed.getLongVersionCode() > validation.versionCode)) {
            showWazeReinstallDialog(installed, validation);
            return;
        }
        patchAndInstallWaze(validation);
    }

    private void patchAndInstallWaze(WazePatchEngine.Validation validation) { patchWaze(validation, false); }

    private void patchWaze(WazePatchEngine.Validation validation, boolean saveOnly) {
        final String wazeProfile = currentProfileCode();
        final File source = sourceFileForProfile(wazeProfile);
        final boolean disableAudioPlayerControls = switchWazeDisableAudio.isChecked();
        final boolean enableEvFeatures = switchWazeEv.isChecked();
        final boolean removeMicrophoneButton = switchWazeRemoveMic.isChecked();
        final boolean enableDebugLogging = isWazeProfile() && switchWazeDebugLogging.isChecked();
        setBusy(true, saveOnly ? "Starting Waze patch for APKM export…" : "Starting Waze wide-screen patch…");
        showProgress(5, "PREPARING", "Opening supported Waze " + validation.displayVersion() + "…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "build_waze");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create Waze build directory");
                File signedDir = new File(work, "signed");
                runOnUiThread(() -> showIndeterminate("PATCHING WAZE", "Applying wide-screen display optimisations…"));
                List<File> signedApks = WazePatchEngine.buildSignedBundle(this, source, signedDir, false,
                        disableAudioPlayerControls, enableEvFeatures, removeMicrophoneButton, enableDebugLogging, message -> {
                    String cleaned = message;
                    if (cleaned != null && cleaned.startsWith("Compatibility mode · Waze ")) {
                        cleaned = "Supported Waze " + validation.displayVersion() + " · applying wide-screen patch…";
                    }
                    postPatchDetail(cleaned);
                });
                showProgressFromWorker(91, "SIGNING COMPLETE", "Patched base and all Waze splits use the same local certificate.");
                if (saveOnly) {
                    showProgressFromWorker(95, "SAVING APKM", "Saving patched Waze APKM to Downloads/BYD-Multi-Patcher…");
                    String savedPath = PatchedBundleArchive.saveApkmToDownloads(this, source, signedApks, null,
                            "Waze", validation.versionName, BuildConfigData.WAZE_PACKAGE,
                            enableDebugLogging ? "DEBUG-byd-wide" : "byd-wide", "Waze",
                            "wide=true;disableAudio=" + disableAudioPlayerControls
                                    + ";removeMic=" + removeMicrophoneButton + ";ev=" + enableEvFeatures
                                    + ";debug5min=" + enableDebugLogging);
                    runOnUiThread(() -> {
                        setBusy(false, "Patched APKM saved: " + savedPath);
                        showProgress(100, "APKM SAVED", savedPath);
                        Toast.makeText(this, "Patched Waze APKM saved to Downloads/BYD-Multi-Patcher.", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                showProgressFromWorker(95, "STAGING INSTALL", "Creating one Android split-APK installation session…");
                PackageInstallHelper.installSplitApks(this, signedApks, BuildConfigData.WAZE_PACKAGE);
                runOnUiThread(() -> showProgress(98, "INSTALLING", "Confirm the Android installation prompt if shown, then wait for Waze installation to complete."));
            } catch (Exception e) { reportPatchFailure(e); }
        });
    }

    private void patchAndInstall91() { patchSpotify91(false); }

    private void patchSpotify91(boolean saveOnly) {
        boolean secondary = radioSecondary.isChecked();
        boolean optimise = switchOptimise.isChecked();
        boolean bydLayout = switchBydLayout.isChecked();
        boolean autoResume = switchAutoResume.isChecked();
        boolean clusterBridge = isClusterBridgeSelected();
        boolean playerLeft = radioPlayerLhs.isChecked();
        int iconHue = seekIconHue.getProgress();
        boolean iconBadge = switchIconBadge.isChecked();
        String appName = editAppName.getText().toString().trim();
        if (appName.isEmpty()) appName = secondary ? "SpotifyPlus2" : "SpotifyPlus";
        String pkg = secondary ? BuildConfigData.SECONDARY_PACKAGE : BuildConfigData.PRIMARY_PACKAGE;
        float scaleFactor = selectedScaleFactor();
        final String finalAppName = appName;
        final File source = sourceFile(false);

        setBusy(true, saveOnly ? "Starting Spotify 9.1 patch for APKM export…" : "Starting Spotify 9.1 patch…");
        showProgress(5, "PREPARING", "Preparing Spotify 9.1.82 APKM bundle…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "build91");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create 9.1 build directory");
                File signedDir = new File(work, "signed");
                runOnUiThread(() -> showIndeterminate("PATCHING SPOTIFY 9.1", "Applying Spotify 9.1 settings to the APKM bundle…"));
                List<File> signedApks = ApkPatchEngine91.buildSignedBundle(this, source, signedDir, secondary, finalAppName,
                        scaleFactor, bydLayout, playerLeft, autoResume, clusterBridge, iconHue, iconBadge, this::postPatchDetail);
                showProgressFromWorker(88, "SIGNING COMPLETE", "Spotify 9.1 base and compatible splits signed with one local certificate.");
                File dm = null;
                if (optimise) {
                    File signedBase = null;
                    for (File f : signedApks) if ("base.apk".equals(f.getName())) { signedBase = f; break; }
                    if (signedBase == null) throw new IOException("Signed Spotify base.apk missing");
                    showProgressFromWorker(91, "OPTIMISING", "Creating DexMetadata from Spotify 9.1.82's own embedded profile…");
                    dm = new File(work, "base.dm");
                    ProfileMetadataUtil.createMatchingDmFromEmbeddedProfile(signedBase, dm);
                    showProgressFromWorker(93, "OPTIMISING", "Version-matched DexMetadata attached to base.apk.");
                } else {
                    showProgressFromWorker(93, "OPTIMISING", "External startup optimisation skipped; embedded Spotify profile retained.");
                }
                if (saveOnly) {
                    showProgressFromWorker(95, "SAVING APKM", "Saving patched APKM to Downloads/BYD-Multi-Patcher…");
                    String mode = clusterBridge ? (secondary ? "musib-cluster" : "musia-cluster") : (secondary ? "musib" : "musia");
                    String savedPath = PatchedBundleArchive.saveApkmToDownloads(this, source, signedApks, dm, "Spotify", BuildConfigData.SUPPORTED_VERSION,
                            pkg, mode, finalAppName, "scale=" + Math.round(scaleFactor * 100f) + ";player=" + (playerLeft ? "lhs" : "rhs")
                                    + ";autoResume=" + autoResume + ";clusterBridge=" + clusterBridge);
                    runOnUiThread(() -> {
                        setBusy(false, "Patched APKM saved: " + savedPath);
                        showProgress(100, "APKM SAVED", savedPath);
                        Toast.makeText(this, "Patched APKM saved to Downloads/BYD-Multi-Patcher.", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                if (clusterBridge) prefs.edit().putString("pending_cluster_start_package", pkg).apply();
                else prefs.edit().remove("pending_cluster_start_package").apply();
                showProgressFromWorker(95, "STARTING INSTALL", "Creating Android split-APK installation session…");
                PackageInstallHelper.installSplitApks(this, signedApks, dm, pkg);
                runOnUiThread(() -> showProgress(98, "INSTALLING",
                        "Confirm the Android installation prompt if shown, then wait for Spotify installation to complete."));
            } catch (Exception e) { reportPatchFailure(e); }
        });
    }

    private void patchAndInstall89() { patchSpotify89(false); }

    private void patchSpotify89(boolean saveOnly) {
        boolean secondary = radioSecondary.isChecked();
        Spotify89ResourceUtil.FontPreset fontPreset = selected89FontPreset();
        boolean rightPanel = radio89PanelRight.isChecked();
        boolean preventPortrait = switch89PreventPortrait.isChecked();
        boolean autoResume = switchAutoResume.isChecked();
        boolean clusterBridge = isClusterBridgeSelected();
        int iconHue = seekIconHue.getProgress();
        boolean iconBadge = switchIconBadge.isChecked();
        String appName = editAppName.getText().toString().trim();
        if (appName.isEmpty()) appName = secondary ? "SpotifyPlus2" : "SpotifyPlus";
        String pkg = secondary ? BuildConfigData.V89_SECONDARY_PACKAGE : BuildConfigData.V89_PRIMARY_PACKAGE;
        final String finalAppName = appName;
        final File source = sourceFile(true);

        setBusy(true, saveOnly ? "Starting Spotify 8.9 patch for APKM export…" : "Starting Spotify 8.9 patch…");
        showProgress(5, "PREPARING", "Preparing Spotify 8.9 APKM bundle…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "build89");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create 8.9 build directory");
                File signedDir = new File(work, "signed");
                runOnUiThread(() -> showIndeterminate("PATCHING SPOTIFY 8.9", "Applying Spotify 8.9 settings to the APKM bundle…"));
                List<File> signedApks = ApkPatchEngine89.buildSignedBundle(this, source, signedDir, secondary, finalAppName,
                        fontPreset, rightPanel, preventPortrait, autoResume, clusterBridge, iconHue, iconBadge, this::postPatchDetail);
                showProgressFromWorker(91, "SIGNING COMPLETE", "Spotify 8.9 base and compatible splits signed with one local certificate.");
                if (saveOnly) {
                    showProgressFromWorker(95, "SAVING APKM", "Saving patched APKM to Downloads/BYD-Multi-Patcher…");
                    String mode = clusterBridge ? (secondary ? "musib-cluster" : "musia-cluster") : (secondary ? "musib" : "musia");
                    String savedPath = PatchedBundleArchive.saveApkmToDownloads(this, source, signedApks, null, "Spotify", BuildConfigData.V89_SUPPORTED_VERSION,
                            pkg, mode, finalAppName, "font=" + fontPreset.name().toLowerCase(Locale.ROOT) + ";panel=" + (rightPanel ? "right" : "left")
                                    + ";autoResume=" + autoResume + ";clusterBridge=" + clusterBridge);
                    runOnUiThread(() -> {
                        setBusy(false, "Patched APKM saved: " + savedPath);
                        showProgress(100, "APKM SAVED", savedPath);
                        Toast.makeText(this, "Patched APKM saved to Downloads/BYD-Multi-Patcher.", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                if (clusterBridge) prefs.edit().putString("pending_cluster_start_package", pkg).apply();
                else prefs.edit().remove("pending_cluster_start_package").apply();
                showProgressFromWorker(95, "STARTING INSTALL", "Creating Android split-APK installation session…");
                PackageInstallHelper.installSplitApks(this, signedApks, pkg);
                runOnUiThread(() -> showProgress(98, "INSTALLING",
                        "Confirm the Android installation prompt if shown, then wait for Spotify installation to complete."));
            } catch (Exception e) { reportPatchFailure(e); }
        });
    }

    private void reportPatchFailure(Exception e) {
        runOnUiThread(() -> {
            String failure = engineAbrp ? "ABRP patch failed" : "Patch failed";
            String detail = failure + ": " + e.getClass().getSimpleName() + " · " + e.getMessage();
            setBusy(false, detail);
            showProgress(0, engineAbrp ? "INSTALL FAILED" : "PATCH FAILED", detail);
            Toast.makeText(this, failure + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void postPatchDetail(String message) {
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setIndeterminate(true);
            if (txtProgressStage != null) txtProgressStage.setText(engineAbrp ? "PREPARING ABRP"
                    : (isWazeProfile() ? "PATCHING " + (engineWaze59 ? "WAZE 5.9" : "WAZE 5.24")
                    : (engine89 ? "PATCHING SPOTIFY 8.9" : "PATCHING SPOTIFY 9.1")));
            if (txtProgressPercent != null) txtProgressPercent.setText("…");
            if (txtProgress != null) txtProgress.setText(message);
        });
    }

    private void showProgressFromWorker(int percent, String stage, String message) { runOnUiThread(() -> showProgress(percent, stage, message)); }

    private void showProgress(int percent, String stage, String message) {
        int value = Math.max(0, Math.min(100, percent));
        if (progressBar != null) { progressBar.setIndeterminate(false); progressBar.setProgress(value); }
        if (txtProgressStage != null) txtProgressStage.setText(stage);
        if (txtProgressPercent != null) txtProgressPercent.setText(value + "%");
        if (txtProgress != null) txtProgress.setText(message);
    }

    private void showIndeterminate(String stage, String message) {
        if (progressBar != null) progressBar.setIndeterminate(true);
        if (txtProgressStage != null) txtProgressStage.setText(stage);
        if (txtProgressPercent != null) txtProgressPercent.setText("…");
        if (txtProgress != null) txtProgress.setText(message);
    }

    private void consumePersistedInstallResult() {
        if (prefs == null || !prefs.contains("last_install_status")) return;
        int status = prefs.getInt("last_install_status", -999);
        String message = prefs.getString("last_install_message", "");
        String targetPackage = prefs.getString("last_install_package", "");
        prefs.edit().remove("last_install_status").remove("last_install_message").remove("last_install_package").apply();
        String installedName = displayNameForPackage(targetPackage);
        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            setBusy(false, installedName + " installed/updated successfully.");
            showProgress(100, "COMPLETE", installedName + " installed/updated successfully.");
        } else {
            String detail = message == null || message.isEmpty() ? "Android reported installation failure." : "Install result: " + message;
            setBusy(false, detail);
            showProgress(100, "INSTALL FAILED", detail);
        }
    }

    private void refreshPackages() {
        if (engineAbrp) {
            PackageInfo abrp = installedPackageInfo(BuildConfigData.ABRP_PACKAGE);
            txtAbrpInstalled.setText(abrp == null ? "Not installed\n" + BuildConfigData.ABRP_PACKAGE
                    : "Installed · " + abrp.versionName + "\n" + BuildConfigData.ABRP_PACKAGE);
            btnAbrpInfo.setEnabled(abrp != null);
            updatePatchButton();
            return;
        }
        if (isWazeProfile()) {
            PackageInfo waze = installedPackageInfo(BuildConfigData.WAZE_PACKAGE);
            txtWazeInstalled.setText(waze == null ? "Not installed\n" + BuildConfigData.WAZE_PACKAGE
                    : "Installed · " + waze.versionName + "\n" + BuildConfigData.WAZE_PACKAGE);
            btnWazeInfo.setEnabled(waze != null);
            updatePatchButton();
            return;
        }
        txtOfficial.setText(slotStatus(BuildConfigData.OFFICIAL_PACKAGE, true));
        txtPrimary.setText(slotStatus(currentPrimaryPackage(), false));
        txtSecondary.setText(slotStatus(currentSecondaryPackage(), false));
        btnPrimaryInfo.setEnabled(isInstalled(currentPrimaryPackage()));
        btnSecondaryInfo.setEnabled(isInstalled(currentSecondaryPackage()));
        updatePatchButton();
    }

    private void updatePatchButton() {
        if (btnPatchInstall == null) return;
        if (btnPatchSaveApkm != null) {
            btnPatchSaveApkm.setVisibility(View.VISIBLE);
            btnPatchSaveApkm.setEnabled(currentSourceValid());
        }
        if (engineAbrp) {
            PackageInfo installed = installedPackageInfo(BuildConfigData.ABRP_PACKAGE);
            if (!currentSourceValid()) {
                btnPatchInstall.setText("SELECT ABRP 7.1.7 APKM");
            } else if (installed == null) {
                btnPatchInstall.setText("INSTALL ABRP 7.1.7");
            } else if (installed.getLongVersionCode() == BuildConfigData.ABRP_SUPPORTED_VERSION_CODE) {
                btnPatchInstall.setText("REINSTALL ABRP 7.1.7");
            } else if (installed.getLongVersionCode() < BuildConfigData.ABRP_SUPPORTED_VERSION_CODE) {
                btnPatchInstall.setText("UPDATE TO ABRP 7.1.7");
            } else {
                btnPatchInstall.setText("DOWNGRADE ABRP · UNINSTALL FIRST");
            }
            btnPatchInstall.setEnabled(currentSourceValid());
            return;
        }
        btnPatchInstall.setEnabled(true);
        if (isWazeProfile()) {
            PackageInfo installed = installedPackageInfo(BuildConfigData.WAZE_PACKAGE);
            WazePatchEngine.Validation source = currentWazeValidation();
            long targetCode = source != null && source.ok ? source.versionCode : currentTargetVersionCode();
            if (installed == null) btnPatchInstall.setText("PATCH & INSTALL WAZE");
            else if (!isSignedByManager(BuildConfigData.WAZE_PACKAGE)) btnPatchInstall.setText("REINSTALL PATCHED WAZE");
            else if (installed.getLongVersionCode() == targetCode) btnPatchInstall.setText("APPLY WIDE-SCREEN PATCH");
            else if (installed.getLongVersionCode() < targetCode) btnPatchInstall.setText("UPDATE & PATCH WAZE");
            else btnPatchInstall.setText("DOWNGRADE & PATCH WAZE");
            return;
        }
        String pkg = radioSecondary != null && radioSecondary.isChecked() ? currentSecondaryPackage() : currentPrimaryPackage();
        PackageInfo installed = installedPackageInfo(pkg);
        if (installed == null) {
            btnPatchInstall.setText("PATCH & INSTALL");
            return;
        }

        long installedCode = installed.getLongVersionCode();
        long targetCode = currentTargetVersionCode();
        if (installedCode == targetCode) {
            btnPatchInstall.setText("APPLY CHANGES");
        } else if (installedCode < targetCode) {
            btnPatchInstall.setText("UPGRADE SLOT TO " + (engine89 ? "8.9" : "9.1"));
        } else {
            btnPatchInstall.setText("SWITCH SLOT TO " + (engine89 ? "8.9" : "9.1"));
        }
    }

    private PackageInfo installedPackageInfo(String pkg) {
        try { return getPackageManager().getPackageInfo(pkg, 0); }
        catch (Exception e) { return null; }
    }

    private String installedVersion(String pkg) {
        PackageInfo pi = installedPackageInfo(pkg);
        return pi == null ? null : pi.versionName;
    }

    private boolean isSignedByManager(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null || pi.signingInfo.getApkContentsSigners() == null || pi.signingInfo.getApkContentsSigners().length == 0) return false;
            byte[] installedCert = pi.signingInfo.getApkContentsSigners()[0].toByteArray();
            byte[] managerCert = SigningKeyStore.getOrCreate().certificate.getEncoded();
            return Arrays.equals(installedCert, managerCert);
        } catch (Exception e) { return false; }
    }

    private void showWazeReinstallDialog(PackageInfo installed, WazePatchEngine.Validation target) {
        boolean newer = installed != null && installed.getLongVersionCode() > target.versionCode;
        boolean signatureMismatch = installed != null && !isSignedByManager(BuildConfigData.WAZE_PACKAGE);
        String reason = signatureMismatch
                ? "The installed Waze uses a different signature. Android cannot replace it with the locally signed patched build."
                : "The installed Waze is newer than the selected source, and Android blocks an in-place downgrade.";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Reinstall Waze for patching?")
                .setMessage("Installed Waze: " + (installed == null ? "unknown" : installed.versionName) + "\n"
                        + "Selected source: Waze " + target.displayVersion() + "\n\n"
                        + reason + "\n\nThe current Waze must be uninstalled first. This removes its local app data. "
                        + "After uninstalling, return to the patcher and press Patch & Install Waze again."
                        + (newer ? "\n\nThis will install Waze " + target.displayVersion() + "." : ""))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall Waze", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + BuildConfigData.WAZE_PACKAGE))); }
                    catch (Exception e) { Toast.makeText(this, "Cannot open Android uninstall screen.", Toast.LENGTH_LONG).show(); }
                })
                .show();
    }

    private void showAbrpReinstallDialog(PackageInfo installed) {
        boolean newer = installed.getLongVersionCode() > BuildConfigData.ABRP_SUPPORTED_VERSION_CODE;
        boolean officialSignature = !isSignedByManager(BuildConfigData.ABRP_PACKAGE);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Uninstall existing ABRP first?")
                .setMessage("Installed ABRP: " + installed.versionName + "\n"
                        + "Selected source: ABRP " + BuildConfigData.ABRP_SUPPORTED_VERSION + "\n\n"
                        + (newer ? "Android blocks an in-place downgrade. " : "")
                        + (officialSignature ? "The BYD-patched build uses the Patcher's local signing certificate, so Android cannot install it over the official ABRP signature. " : "")
                        + "Uninstalling ABRP removes its local app data. "
                        + "After uninstalling, return here and press Install ABRP 7.1.7 again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall ABRP", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + BuildConfigData.ABRP_PACKAGE))); }
                    catch (Exception e) { Toast.makeText(this, "Cannot open Android uninstall screen.", Toast.LENGTH_LONG).show(); }
                })
                .show();
    }

    private void showSavedBundleSignatureDialog(PatchedBundleArchive.Info info) {
        String extra = "";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Uninstall existing app first?")
                .setMessage("The saved patched bundle targets " + info.packageName + ", but the installed app uses a different signing certificate. Android cannot replace it with this saved build." + extra)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall existing", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + info.packageName))); }
                    catch (Exception e) { Toast.makeText(this, "Cannot open Android uninstall screen.", Toast.LENGTH_LONG).show(); }
                })
                .show();
    }

    private void showProfileDowngradeDialog(String pkg, String installedVersion) {
        String target = engine89 ? "Spotify 8.9" : "Spotify 9.1";
        String slot = radioSecondary.isChecked() ? "Secondary" : "Primary";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Switch " + slot + " slot to " + target + "?")
                .setMessage(slot + " currently contains Spotify " + installedVersion + " at " + pkg + ".\n\n"
                        + "This change goes to an older Spotify version. Android does not allow a normal in-place downgrade, so the current app must be uninstalled first. App data for this slot will be removed.\n\n"
                        + "Upgrading in the other direction (8.9 -> 9.1) is supported in place and does not require this uninstall step.\n\n"
                        + "After uninstalling, return here and press Patch & Install again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall current", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))); }
                    catch (Exception e) { Toast.makeText(this, "Cannot open Android uninstall screen.", Toast.LENGTH_LONG).show(); }
                })
                .show();
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    private String slotStatus(String pkg, boolean official) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            if (official) return "Installed · " + pi.versionName + "\n" + pkg;
            CharSequence label = pi.applicationInfo.loadLabel(getPackageManager());
            String shown = label == null ? "SpotifyPlus" : label.toString().trim();
            return shown + "\nInstalled · " + pi.versionName + "\n" + pkg;
        } catch (Exception e) { return "Not installed\n" + pkg; }
    }

    private String diagnosticStatus(String label, String pkg) {
        try { PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0); return label + ": installed · " + pi.versionName + " · " + pkg; }
        catch (Exception e) { return label + ": not installed · " + pkg; }
    }

    private void openAppInfo(String pkg) {
        if (!isInstalled(pkg)) { Toast.makeText(this, "App is not installed.", Toast.LENGTH_SHORT).show(); return; }
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))); }
        catch (Exception e) { Toast.makeText(this, "Cannot open app info.", Toast.LENGTH_SHORT).show(); }
    }


    private void copyDiagnostics() {
        StringBuilder s = new StringBuilder();
        s.append("BYD Multi Patcher ").append(BuildConfigData.MANAGER_VERSION).append("\n")
                .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
                .append("Android: ").append(Build.VERSION.RELEASE).append(" API ").append(Build.VERSION.SDK_INT).append("\n")
                .append("Selected profile: ").append(engineAbrp ? "ABRP " + BuildConfigData.ABRP_SUPPORTED_VERSION
                        : (isWazeProfile() ? "Waze " + currentTargetVersion()
                        : "Spotify " + (engine89 ? BuildConfigData.V89_SUPPORTED_VERSION : BuildConfigData.SUPPORTED_VERSION))).append("\n")
                .append("Source valid: ").append(currentSourceValid()).append("\n");
        if (engineAbrp) {
            s.append(diagnosticStatus("ABRP", BuildConfigData.ABRP_PACKAGE)).append("\n")
                    .append("Supported ABRP version: ").append(BuildConfigData.ABRP_SUPPORTED_VERSION).append(" (")
                    .append(BuildConfigData.ABRP_SUPPORTED_VERSION_CODE).append(")\n")
                    .append("ABRP source type: APKM universal 120-640dpi split bundle\n")
                    .append("Direct BYD battery data: ").append(switchAbrpDirectBattery != null && switchAbrpDirectBattery.isChecked()).append("\n")
                    .append("Default appearance Automatic (Day/Night): ").append(switchAbrpAutoDayNight != null && switchAbrpAutoDayNight.isChecked()).append("\n")
                    .append("BYD native Cerence TTS: ").append(switchAbrpNativeTts != null && switchAbrpNativeTts.isChecked()).append("\n")
                    .append("5-minute privacy-safe startup debug log: ").append(switchAbrpDebugLogging != null && switchAbrpDebugLogging.isChecked()).append("\n");
        } else if (isWazeProfile()) {
            WazePatchEngine.Validation v = currentWazeValidation();
            s.append(diagnosticStatus("Waze", BuildConfigData.WAZE_PACKAGE)).append("\n")
                    .append("Waze profile: ").append(engineWaze59 ? "5.9 older / lighter" : "5.24 current").append("\n")
                    .append("Target Waze version: ").append(currentTargetVersion()).append(" (").append(currentTargetVersionCode()).append(")\n")
                    .append("Waze signed by this patcher: ").append(isInstalled(BuildConfigData.WAZE_PACKAGE) && isSignedByManager(BuildConfigData.WAZE_PACKAGE)).append("\n")
                    .append("Selected Waze source: ").append(v == null ? "not validated" : v.displayVersion()).append("\n")
                    .append("Selected source supported: ").append(isSupportedWazeVersion(v)).append("\n")
                    .append("BYD wide-screen layout: forced ON\n")
                    .append("Disable audio player controls: ").append(switchWazeDisableAudio.isChecked()).append("\n")
                    .append("Remove search microphone: ").append(switchWazeRemoveMic.isChecked()).append("\n")
                    .append("EV features + CCS2 default: ").append(switchWazeEv.isChecked()).append("\n")
                    .append("5-minute privacy-safe startup debug log: ").append(isWazeProfile() && switchWazeDebugLogging.isChecked()).append("\n")
                    .append("Waze source type: APKM split bundle\n");
        } else {
            s.append(diagnosticStatus("Official", BuildConfigData.OFFICIAL_PACKAGE)).append("\n")
                    .append(diagnosticStatus("Primary", currentPrimaryPackage())).append("\n")
                    .append(diagnosticStatus("Secondary", currentSecondaryPackage())).append("\n")
                    .append("Selected slot: ").append(radioPrimary.isChecked() ? "Primary / com.spotify.musia" : "Secondary / com.spotify.musib").append("\n")
                    .append("BYD instrument cluster display: ").append(isClusterBridgeSelected()).append("\n")
                    .append("Stock com.spotify.music is never replaced by this mode.\n");
            if (engine89) {
                s.append("8.9 font: ").append(selected89FontCode()).append("\n")
                        .append("8.9 side panel: ").append(radio89PanelLeft.isChecked() ? "Left/LHD" : "Right/RHD").append("\n")
                        .append("8.9 prevent portrait: ").append(switch89PreventPortrait.isChecked()).append("\n")
                        .append("8.9 auto-resume: enabled\n");
            } else {
                s.append("Email OTP login compatibility: forced ON\n")
                        .append("Performance optimisation: ").append(switchOptimise.isChecked()).append("\n")
                        .append("Wide-screen layout fixes: ").append(switchBydLayout.isChecked()).append("\n")
                        .append("Scale: ").append(selectedScaleCode()).append("% (from stock Spotify)\n")
                        .append("Player position: ").append(radioPlayerLhs.isChecked() ? "LHS" : "RHS").append("\n")
                        .append("Auto-resume: ").append(switchAutoResume.isChecked()).append("\n");
            }
            s.append("Icon hue: ").append(seekIconHue.getProgress()).append("° · + badge: ").append(switchIconBadge.isChecked()).append("\n");
        }
        try {
            SigningKeyStore.KeyMaterial km = SigningKeyStore.getOrCreate();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] fp = md.digest(km.certificate.getEncoded());
            StringBuilder f = new StringBuilder();
            for (byte b : fp) f.append(String.format(Locale.US, "%02X", b));
            s.append("Local signing cert SHA-256: ").append(f).append("\n");
        } catch (Exception e) { s.append("Signing identity: unavailable\n"); }
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("BYD Multi Patcher diagnostics", s.toString()));
        Toast.makeText(this, "Diagnostic summary copied.", Toast.LENGTH_SHORT).show();
    }


    private void setBusy(boolean busy, String message) {
        btnPatchInstall.setEnabled(!busy && (!engineAbrp || currentSourceValid()));
        if (btnPatchSaveApkm != null) {
            btnPatchSaveApkm.setVisibility(View.VISIBLE);
            btnPatchSaveApkm.setEnabled(!busy && currentSourceValid());
        }
        radioEngine91.setEnabled(!busy);
        radioEngine89.setEnabled(!busy);
        radioEngineWaze.setEnabled(!busy);
        radioEngineWaze59.setEnabled(!busy);
        radioEngineAbrp.setEnabled(!busy);
        if (switchBydClusterBridge != null) switchBydClusterBridge.setEnabled(!busy);
        if (radioSecondary != null) radioSecondary.setEnabled(!busy);
        if (switchWazeDisableAudio != null) switchWazeDisableAudio.setEnabled(!busy);
        if (switchWazeEv != null) switchWazeEv.setEnabled(!busy);
        if (switchWazeRemoveMic != null) switchWazeRemoveMic.setEnabled(!busy);
        if (switchWazeDebugLogging != null) switchWazeDebugLogging.setEnabled(!busy);
        if (switchAbrpDirectBattery != null) switchAbrpDirectBattery.setEnabled(!busy);
        if (switchAbrpAutoDayNight != null) switchAbrpAutoDayNight.setEnabled(!busy);
        if (switchAbrpNativeTts != null) switchAbrpNativeTts.setEnabled(!busy);
        if (switchAbrpDebugLogging != null) switchAbrpDebugLogging.setEnabled(!busy);
        if (txtProgress != null) txtProgress.setText(message);
    }

    private static String displayNameForPackage(String packageName) {
        if (BuildConfigData.ABRP_PACKAGE.equals(packageName)) return "ABRP";
        if (BuildConfigData.WAZE_PACKAGE.equals(packageName)) return "Waze";
        if (BuildConfigData.OFFICIAL_PACKAGE.equals(packageName)) return "Spotify (stock)";
        return "Spotify clone";
    }

    @Override protected void onStart() {
        super.onStart();
        if (!installReceiverRegistered) {
            IntentFilter filter = new IntentFilter(InstallResultReceiver.ACTION_INSTALL_FINISHED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(installRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(installRefreshReceiver, filter);
            installReceiverRegistered = true;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPackages();
        consumePersistedInstallResult();
        mainHandler.postDelayed(this::refreshPackages, 400);
        mainHandler.postDelayed(this::refreshPackages, 1400);
    }

    @Override protected void onStop() {
        if (installReceiverRegistered) {
            try { unregisterReceiver(installRefreshReceiver); } catch (Exception ignored) {}
            installReceiverRegistered = false;
        }
        super.onStop();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        f.delete();
    }

    @Override protected void onDestroy() {
        saveCurrentSlotUi();
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        clearIconPreviews();
        super.onDestroy();
    }
}

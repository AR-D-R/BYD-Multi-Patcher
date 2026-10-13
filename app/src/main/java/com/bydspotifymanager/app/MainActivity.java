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
    private static final int CONTROL_UNCHECKED = Color.rgb(179, 179, 179);
    private static final int CONTROL_DISABLED = Color.rgb(105, 105, 105);
    private static final int SCALE_MIN_PERCENT = 100;
    private static final int SCALE_MAX_PERCENT = 150;
    private static final int SCALE_STEP_PERCENT = 5;
    private static final int SCALE_DEFAULT_PERCENT = 115;

    // Waze versions supported by this patcher build.
    private static final String WAZE_STABLE_VERSION = "5.24.0.1";
    private static final long WAZE_STABLE_VERSION_CODE = 1030729L;
    private static final String WAZE_BETA_VERSION = "5.24.4.900";
    private static final long WAZE_BETA_VERSION_CODE = 1030731L;
    private static final String WAZE_SUPPORTED_VERSIONS =
            WAZE_STABLE_VERSION + " (stable) · " + WAZE_BETA_VERSION + " (beta)";
    private static final String WAZE_USER_DESCRIPTION =
            "Wide-screen changes:\n"
            + "• Centres the car and speedometer.\n"
            + "• Makes the navigation panel narrower to show more map.\n"
            + "• Moves top controls below thick system bars.\n"
            + "• Improves route preview and overview placement.\n"
            + "• Keeps the search drawer smaller when collapsed.\n"
            + "• Can remove the unsupported microphone button from search.\n\n"
            + "The EV option turns on Waze’s EV setting and selects CCS2, allowing compatible charging stations to be shown.\n\n"
            + "Tested on BYD Android head units. It may also work on other systems with similar wide displays and top bars.\n\n"
            + "Supported Waze versions in this build: " + WAZE_SUPPORTED_VERSIONS + ".";

    private TextView txtOfficial, txtPrimary, txtSecondary, txtSourceStatus, txtProgress;
    private TextView txtOptimiseHint, txtSelectedSlotTitle, txtManagerVersion, txtIconHue, txtScaleValue;
    private TextView txtProgressStage, txtProgressPercent, txtSourceVersion, txtSlotsVersion, txtBrandingHint, txtWazeInstalled, txtAbrpInstalled;
    private ProgressBar progressBar;

    private RadioButton radioEngine91, radioEngine89, radioEngineWaze, radioEngineAbrp;
    private RadioButton radioPrimary, radioSecondary;
    private RadioButton radioPlayerLhs, radioPlayerRhs;
    private RadioButton radio89FontStock, radio89FontModerate, radio89FontLarge;
    private RadioButton radio89PanelLeft, radio89PanelRight;

    private EditText editAppName;
    private SwitchMaterial switchOptimise, switchBydLayout, switchAutoResume, switchIconBadge, switch89PreventPortrait, switchWazeWide, switchWazeEv, switchWazeRemoveMic, switchAbrpDirectBattery, switchAbrpAutoDayNight, switchAbrpNativeTts;
    private Button btnPatchInstall, btnPrimaryInfo, btnSecondaryInfo, btnWazeInfo, btnAbrpInfo, btnBrowse;
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
    private boolean engine89;
    private boolean engineWaze;
    private boolean engineAbrp;
    private boolean sourceValid91;
    private boolean sourceValid89;
    private boolean sourceValidWaze;
    private boolean sourceValidAbrp;
    private volatile WazePatchEngine.Validation wazeSourceValidation;
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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("manager_settings", MODE_PRIVATE);
        bind();
        switchWazeEv.setChecked(prefs.getBoolean("waze_enable_ev", true));
        switchWazeRemoveMic.setChecked(prefs.getBoolean("waze_remove_microphone", true));
        switchAbrpDirectBattery.setChecked(prefs.getBoolean("abrp_direct_battery", true));
        switchAbrpAutoDayNight.setChecked(prefs.getBoolean("abrp_auto_day_night_default", true));
        switchAbrpNativeTts.setChecked(prefs.getBoolean("abrp_native_tts", true));
        setupActions();
        String selectedProfile = prefs.getString("selected_engine", "91");
        engine89 = "89".equals(selectedProfile);
        engineWaze = "waze".equals(selectedProfile);
        engineAbrp = "abrp".equals(selectedProfile);
        radioEngine89.setChecked(engine89);
        radioEngineWaze.setChecked(engineWaze);
        radioEngineAbrp.setChecked(engineAbrp);
        radioEngine91.setChecked(!engine89 && !engineWaze && !engineAbrp);
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
        txtAbrpInstalled = findViewById(R.id.txtAbrpInstalled);
        txtProgress = findViewById(R.id.txtProgress);
        txtProgressStage = findViewById(R.id.txtProgressStage);
        txtProgressPercent = findViewById(R.id.txtProgressPercent);
        progressBar = findViewById(R.id.progressBar);
        txtOptimiseHint = findViewById(R.id.txtOptimiseHint);
        txtSelectedSlotTitle = findViewById(R.id.txtSelectedSlotTitle);
        txtManagerVersion = findViewById(R.id.txtManagerVersion);
        txtIconHue = findViewById(R.id.txtIconHue);
        txtScaleValue = findViewById(R.id.txtScaleValue);
        if (txtManagerVersion != null) txtManagerVersion.setText("v" + BuildConfigData.MANAGER_VERSION);

        radioEngine91 = findViewById(R.id.radioEngine91);
        radioEngine89 = findViewById(R.id.radioEngine89);
        radioEngineWaze = findViewById(R.id.radioEngineWaze);
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
        switchIconBadge = findViewById(R.id.switchIconBadge);
        switch89PreventPortrait = findViewById(R.id.switch89PreventPortrait);
        switchWazeWide = findViewById(R.id.switchWazeWide);
        switchWazeEv = findViewById(R.id.switchWazeEv);
        switchWazeRemoveMic = findViewById(R.id.switchWazeRemoveMic);
        switchAbrpDirectBattery = findViewById(R.id.switchAbrpDirectBattery);
        switchAbrpAutoDayNight = findViewById(R.id.switchAbrpAutoDayNight);
        switchAbrpNativeTts = findViewById(R.id.switchAbrpNativeTts);
        seekIconHue = findViewById(R.id.seekIconHue);
        seekInterfaceScale = findViewById(R.id.seekInterfaceScale);
        imgIconPreview = findViewById(R.id.imgIconPreview);

        btnPatchInstall = findViewById(R.id.btnPatchInstall);
        btnPrimaryInfo = findViewById(R.id.btnPrimaryInfo);
        btnSecondaryInfo = findViewById(R.id.btnSecondaryInfo);
        btnWazeInfo = findViewById(R.id.btnWazeInfo);
        btnAbrpInfo = findViewById(R.id.btnAbrpInfo);
        btnBrowse = findViewById(R.id.btnBrowse);
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
        btnBrowse.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            boolean bundleProfile = true;
            i.setType(bundleProfile ? "*/*" : "application/vnd.android.package-archive");
            if (bundleProfile) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/vnd.android.package-archive"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            pickApk.launch(i);
        });
        findViewById(R.id.btnApkMirror).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentApkMirrorUrl()))));
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            refreshPackages();
            validateStoredSource();
        });
        findViewById(R.id.btnDiagnostics).setOnClickListener(v -> copyDiagnostics());
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

        radioEngine91.setOnClickListener(v -> switchProfile("91"));
        radioEngine89.setOnClickListener(v -> switchProfile("89"));
        radioEngineWaze.setOnClickListener(v -> switchProfile("waze"));
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
        switchWazeEv.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("waze_enable_ev", isChecked).apply());
        switchWazeRemoveMic.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("waze_remove_microphone", isChecked).apply());
        btnPatchInstall.setOnClickListener(v -> patchAndInstall());
    }

    private void switchProfile(String profile) {
        boolean new89 = "89".equals(profile);
        boolean newWaze = "waze".equals(profile);
        boolean newAbrp = "abrp".equals(profile);
        if (new89 == engine89 && newWaze == engineWaze && newAbrp == engineAbrp) return;
        if (!engineWaze && !engineAbrp) saveCurrentSlotUi();
        engine89 = new89;
        engineWaze = newWaze;
        engineAbrp = newAbrp;
        prefs.edit().putString("selected_engine", currentProfileCode()).apply();
        radioEngine89.setChecked(engine89);
        radioEngineWaze.setChecked(engineWaze);
        radioEngineAbrp.setChecked(engineAbrp);
        radioEngine91.setChecked(!engine89 && !engineWaze && !engineAbrp);
        applyEngineUi(true);
    }

    private void applyEngineUi(boolean resetProgress) {
        boolean spotify = !engineWaze && !engineAbrp;
        applyProfileAccent();
        spotifySlotsPanel.setVisibility(spotify ? View.VISIBLE : View.GONE);
        wazeStatusPanel.setVisibility(engineWaze ? View.VISIBLE : View.GONE);
        abrpStatusPanel.setVisibility(engineAbrp ? View.VISIBLE : View.GONE);
        spotifySettingsPanel.setVisibility(spotify ? View.VISIBLE : View.GONE);
        panelWaze.setVisibility(engineWaze ? View.VISIBLE : View.GONE);
        panelAbrp.setVisibility(engineAbrp ? View.VISIBLE : View.GONE);
        panel91.setVisibility(spotify && !engine89 ? View.VISIBLE : View.GONE);
        panel89.setVisibility(spotify && engine89 ? View.VISIBLE : View.GONE);

        if (engineAbrp) {
            txtSelectedSlotTitle.setText("ABRP Display & Location Improvements Patch");
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

        if (engineWaze) {
            txtSelectedSlotTitle.setText("WAZE · WIDE-SCREEN PATCH");
            txtSourceVersion.setText("Supported Waze: " + WAZE_SUPPORTED_VERSIONS
                    + " · Android 10+ · arm64 · 480-640dpi APKM");
            btnBrowse.setText("BROWSE APKM");
            if (switchWazeWide != null) { switchWazeWide.setChecked(true); switchWazeWide.setEnabled(false); }
            sourceApk = sourceFileForProfile("waze");
            clearIconPreviews();
            refreshPackages();
            if (resetProgress) showProgress(0, "READY", "Supported Waze versions: " + WAZE_SUPPORTED_VERSIONS + ".");
            if (sourceApk.isFile()) validateStoredSource();
            else {
                wazeSourceValidation = null;
                setCurrentSourceValid(false);
                txtSourceStatus.setText("No Waze APKM selected · supported: " + WAZE_SUPPORTED_VERSIONS);
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
        refreshPackages();
        if (resetProgress) showProgress(0, "READY", "Switched to Spotify " + (engine89 ? "8.9" : "9.1") + ".");
        if (sourceApk.isFile()) validateStoredSource();
        else {
            setCurrentSourceValid(false);
            txtSourceStatus.setText(engine89 ? "No Spotify 8.9 APKM selected" : "No Spotify 9.1 APKM selected");
            updateIconPreview();
        }
    }

    private void applyProfileAccent() {
        int accent = engineAbrp ? ABRP_ACCENT : (engineWaze ? WAZE_ACCENT : SPOTIFY_ACCENT);
        int accentDark = engineAbrp ? ABRP_ACCENT_DARK : (engineWaze ? WAZE_ACCENT_DARK : SPOTIFY_ACCENT_DARK);

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
                radioEngine91, radioEngine89, radioEngineWaze, radioEngineAbrp,
                radioPrimary, radioSecondary,
                radioPlayerLhs, radioPlayerRhs,
                radio89FontStock, radio89FontModerate, radio89FontLarge,
                radio89PanelLeft, radio89PanelRight
        };
        for (RadioButton radio : radios) {
            if (radio != null) radio.setButtonTintList(controlTint);
        }

        SwitchMaterial[] switches = {
                switchOptimise, switchBydLayout, switchAutoResume, switchIconBadge,
                switch89PreventPortrait, switchWazeWide, switchWazeEv, switchWazeRemoveMic,
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
                        : (engineWaze ? Color.rgb(15, 34, 42) : Color.rgb(20, 36, 26)));
                bg.setCornerRadius(dpToPx(10));
                bg.setStroke(Math.max(1, Math.round(dpToPx(1))), accentDark);
                parent.setBackground(bg);
            }
        }

        int[] accentButtonIds = {
                R.id.btnPatchInstall, R.id.btnPrimaryInfo, R.id.btnSecondaryInfo, R.id.btnWazeInfo, R.id.btnAbrpInfo,
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

    private int withAlpha(int rgb, int alpha) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private void selectSlot(boolean primary) {
        radioPrimary.setChecked(primary);
        radioSecondary.setChecked(!primary);
        updateSlotUi();
    }

    private void updateSlotUi() {
        loadingSlotUi = true;
        if (engineWaze || engineAbrp) {
            txtSelectedSlotTitle.setText(engineAbrp ? "ABRP Display & Location Improvements Patch" : "WAZE · WIDE-SCREEN PATCH");
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
            txtSelectedSlotTitle.setText((primary ? "PRIMARY SETTINGS" : "SECONDARY SETTINGS") + " · SPOTIFY 8.9");
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
            txtSelectedSlotTitle.setText((primary ? "PRIMARY SETTINGS" : "SECONDARY SETTINGS") + " · SPOTIFY 9.1");
        }
        loadingSlotUi = false;
        updateIconPreview();
        updatePatchButton();
    }

    private void saveCurrentSlotUi() {
        if (engineWaze || engineAbrp || prefs == null || radioPrimary == null) return;
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
        if (engineWaze || engineAbrp) return;
        if (stockIconPreview == null && sourceApk != null && sourceApk.isFile()) {
            stockIconPreview = IconBrandingUtil.loadStockPreviewFromApkm(sourceApk);
        }
    }

    private void updateIconPreview() {
        if (engineWaze || engineAbrp || imgIconPreview == null) return;
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
        if ("waze".equals(profile)) return new File(dir, "waze_source.apkm");
        if ("abrp".equals(profile)) return new File(dir, "abrp_7.1.7.apkm");
        return new File(dir, "89".equals(profile) ? "spotify_8.9.76.538.apkm" : "spotify_9.1.82.2161.apkm");
    }

    private String currentProfileCode() { return engineAbrp ? "abrp" : (engineWaze ? "waze" : (engine89 ? "89" : "91")); }
    private String currentPrimaryPackage() { return engineAbrp ? BuildConfigData.ABRP_PACKAGE : (engineWaze ? BuildConfigData.WAZE_PACKAGE : (engine89 ? BuildConfigData.V89_PRIMARY_PACKAGE : BuildConfigData.PRIMARY_PACKAGE)); }
    private String currentSecondaryPackage() { return engineAbrp ? BuildConfigData.ABRP_PACKAGE : (engineWaze ? BuildConfigData.WAZE_PACKAGE : (engine89 ? BuildConfigData.V89_SECONDARY_PACKAGE : BuildConfigData.SECONDARY_PACKAGE)); }
    private String currentTargetVersion() { return engineAbrp ? BuildConfigData.ABRP_SUPPORTED_VERSION : (engineWaze ? BuildConfigData.WAZE_SUPPORTED_VERSION : (engine89 ? BuildConfigData.V89_SUPPORTED_VERSION : BuildConfigData.SUPPORTED_VERSION)); }
    private long currentTargetVersionCode() { return engineAbrp ? BuildConfigData.ABRP_SUPPORTED_VERSION_CODE : (engineWaze ? BuildConfigData.WAZE_SUPPORTED_VERSION_CODE : (engine89 ? BuildConfigData.V89_SUPPORTED_VERSION_CODE : BuildConfigData.SUPPORTED_VERSION_CODE)); }
    private String currentApkMirrorUrl() { return engineAbrp ? BuildConfigData.ABRP_APKMIRROR_URL : (engineWaze ? BuildConfigData.WAZE_APKMIRROR_URL : (engine89 ? BuildConfigData.V89_APKMIRROR_URL : BuildConfigData.APKMIRROR_URL)); }
    private boolean currentSourceValid() { return engineAbrp ? sourceValidAbrp : (engineWaze ? sourceValidWaze : (engine89 ? sourceValid89 : sourceValid91)); }
    private void setCurrentSourceValid(boolean valid) { if (engineAbrp) sourceValidAbrp = valid; else if (engineWaze) sourceValidWaze = valid; else if (engine89) sourceValid89 = valid; else sourceValid91 = valid; }
    private void setSourceValid(String profile, boolean valid) { if ("abrp".equals(profile)) sourceValidAbrp = valid; else if ("waze".equals(profile)) sourceValidWaze = valid; else if ("89".equals(profile)) sourceValid89 = valid; else sourceValid91 = valid; }

    private boolean isSupportedWazeVersion(WazePatchEngine.Validation validation) {
        if (validation == null || !validation.ok) return false;
        return (WAZE_STABLE_VERSION.equals(validation.versionName)
                && validation.versionCode == WAZE_STABLE_VERSION_CODE)
                || (WAZE_BETA_VERSION.equals(validation.versionName)
                && validation.versionCode == WAZE_BETA_VERSION_CODE);
    }

    private String supportedWazeDetail(WazePatchEngine.Validation validation) {
        String channel = WAZE_BETA_VERSION.equals(validation.versionName) ? "beta" : "stable";
        return "SUPPORTED · Waze " + validation.displayVersion() + " (" + channel + ")"
                + " · " + validation.apkCount + " APK splits";
    }

    private void applyWazeStaticText() {
        if (panelWaze instanceof LinearLayout) {
            LinearLayout panel = (LinearLayout) panelWaze;
            if (panel.getChildCount() >= 3 && panel.getChildAt(2) instanceof TextView) {
                ((TextView) panel.getChildAt(2)).setText(WAZE_USER_DESCRIPTION);
            }
        }
        if (wazeStatusPanel instanceof LinearLayout) {
            LinearLayout status = (LinearLayout) wazeStatusPanel;
            if (status.getChildCount() >= 2 && status.getChildAt(1) instanceof LinearLayout) {
                LinearLayout card = (LinearLayout) status.getChildAt(1);
                if (card.getChildCount() >= 1 && card.getChildAt(0) instanceof TextView) {
                    ((TextView) card.getChildAt(0)).setText("SUPPORTED: 5.24.0.1 / 5.24.4.900");
                }
            }
        }
    }

    private void importSource(Uri uri) {
        final String profile = currentProfileCode();
        final File target = sourceFileForProfile(profile);
        final boolean waze = "waze".equals(profile);
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
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IOException("Cannot read selected file");
                    IoUtil.copy(in, out);
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
                    if (!isSupportedWazeVersion(validation)) {
                        tmp.delete();
                        throw new IllegalArgumentException("Unsupported Waze " + validation.displayVersion()
                                + ". This build supports only " + WAZE_SUPPORTED_VERSIONS + ".");
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
                if (waze) wazeSourceValidation = selectedWazeValidation;
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
                if (waze) wazeSourceValidation = null;
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
            if ("waze".equals(profile)) wazeSourceValidation = null;
            setSourceValid(profile, false);
            txtSourceStatus.setText(engineAbrp ? "Choose the ABRP 7.1.7 file to start."
                    : (engineWaze ? "No Waze APKM selected · supported: " + WAZE_SUPPORTED_VERSIONS
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
                } else if ("waze".equals(profile)) {
                    WazePatchEngine.Validation v = WazePatchEngine.validateSource(target);
                    valid = v.ok && isSupportedWazeVersion(v);
                    wazeSourceValidation = valid ? v : null;
                    if (!v.ok) {
                        detail = "✕ " + v.detail;
                    } else if (!valid) {
                        detail = "✕ Unsupported Waze " + v.displayVersion() + ". Supported: " + WAZE_SUPPORTED_VERSIONS;
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
                if ("waze".equals(profile)) wazeSourceValidation = null;
                detail = "Source check failed: " + e.getMessage();
            }
            final boolean result = valid;
            final String message = detail;
            final WazePatchEngine.Validation storedWazeValidation = "waze".equals(profile) ? wazeSourceValidation : null;
            setSourceValid(profile, result);
            runOnUiThread(() -> {
                if (currentProfileCode().equals(profile)) {
                    sourceApk = target;
                    txtSourceStatus.setText(message);
                    if ("waze".equals(profile) && storedWazeValidation != null) updateWazeSourceHeader(storedWazeValidation);
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
            String channel = WAZE_BETA_VERSION.equals(validation.versionName) ? "beta" : "stable";
            txtSourceVersion.setText("Waze " + validation.displayVersion()
                    + " · SUPPORTED · " + channel + " · Android 10+ · arm64 · 480-640dpi APKM");
        } else {
            txtSourceVersion.setText("Unsupported Waze " + validation.displayVersion()
                    + " · supported: " + WAZE_SUPPORTED_VERSIONS);
        }
    }

    private void patchAndInstall() {
        if (!currentSourceValid()) {
            String needed = engineAbrp
                    ? "Choose the original ABRP 7.1.7 file first."
                    : engineWaze
                    ? "Select a valid Waze Android 10+ · arm64 · 480-640dpi APKM first."
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

        if (engineWaze) {
            WazePatchEngine.Validation validation = wazeSourceValidation;
            if (validation == null || !validation.ok) {
                Toast.makeText(this, "Waze source is still being checked. Press Refresh if needed.", Toast.LENGTH_LONG).show();
                return;
            }
            if (!isSupportedWazeVersion(validation)) {
                Toast.makeText(this, "Unsupported Waze " + validation.displayVersion()
                        + ". Supported: " + WAZE_SUPPORTED_VERSIONS, Toast.LENGTH_LONG).show();
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
        saveCurrentSlotUi();
        if (engine89) patchAndInstall89(); else patchAndInstall91();
    }

    private void patchAndInstallAbrp() {
        final File source = sourceFileForProfile("abrp");
        final boolean directBydBattery = switchAbrpDirectBattery != null && switchAbrpDirectBattery.isChecked();
        final boolean autoDayNightDefault = switchAbrpAutoDayNight != null && switchAbrpAutoDayNight.isChecked();
        final boolean nativeBydTts = switchAbrpNativeTts != null && switchAbrpNativeTts.isChecked();
        String abrpPatchSummary = "Preparing ABRP display & location improvements"
                + (directBydBattery ? ", direct BYD battery data" : "")
                + (autoDayNightDefault ? ", Automatic Day/Night default" : "")
                + (nativeBydTts ? ", BYD native TTS (Zoe)" : "") + "…";
        setBusy(true, abrpPatchSummary);
        showProgress(5, "PREPARING", "Checking which ABRP files this device needs…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "install_abrp");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create ABRP install directory");
                File extractedDir = new File(work, "apks");
                List<File> installApks = AbrpInstallEngine.prepareInstallBundle(this, source, extractedDir,
                        directBydBattery, autoDayNightDefault, nativeBydTts, this::postPatchDetail);
                String readyDetail = "Display/location fixes are ready"
                        + (directBydBattery ? ", with direct BYD battery injection" : "")
                        + (autoDayNightDefault ? ", and fresh-install Automatic Day/Night appearance" : "")
                        + (nativeBydTts ? ", and BYD native Cerence TTS using Zoe" : "") + ".";
                showProgressFromWorker(90, "ABRP PATCH READY", readyDetail);
                showProgressFromWorker(95, "STARTING INSTALL", "Asking Android to install ABRP…");
                PackageInstallHelper.installSplitApks(this, installApks, BuildConfigData.ABRP_PACKAGE);
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

    private void patchAndInstallWaze(WazePatchEngine.Validation validation) {
        final File source = sourceFileForProfile("waze");
        final boolean enableEvFeatures = switchWazeEv.isChecked();
        final boolean removeMicrophoneButton = switchWazeRemoveMic.isChecked();
        if (enableEvFeatures && !validation.tested) {
            Toast.makeText(this, "EV + CCS2 defaults require the exact tested Waze "
                    + BuildConfigData.WAZE_SUPPORTED_VERSION + " source bundle.", Toast.LENGTH_LONG).show();
            return;
        }
        setBusy(true, "Starting Waze wide-screen patch…");
        showProgress(5, "PREPARING",
                "Opening supported Waze " + validation.displayVersion() + "…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "build_waze");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create Waze build directory");
                File signedDir = new File(work, "signed");
                runOnUiThread(() -> showIndeterminate("PATCHING WAZE", "Applying wide-screen display optimisations…"));
                List<File> signedApks = WazePatchEngine.buildSignedBundle(source, signedDir, false,
                        enableEvFeatures, removeMicrophoneButton, message -> {
                    String cleaned = message;
                    if (cleaned != null && cleaned.startsWith("Compatibility mode · Waze ")) {
                        cleaned = "Supported Waze " + validation.displayVersion() + " · applying wide-screen patch…";
                    }
                    postPatchDetail(cleaned);
                });
                showProgressFromWorker(91, "SIGNING COMPLETE", "Patched base and all Waze splits use the same local certificate.");
                showProgressFromWorker(95, "STAGING INSTALL", "Creating one Android split-APK installation session…");
                PackageInstallHelper.installSplitApks(this, signedApks, BuildConfigData.WAZE_PACKAGE);
                runOnUiThread(() -> showProgress(98, "INSTALLING", "Confirm the Android installation prompt if shown, then wait for Waze installation to complete."));
            } catch (Exception e) { reportPatchFailure(e); }
        });
    }

    private void patchAndInstall91() {
        boolean secondary = radioSecondary.isChecked();
        boolean optimise = switchOptimise.isChecked();
        boolean bydLayout = switchBydLayout.isChecked();
        boolean autoResume = switchAutoResume.isChecked();
        boolean playerLeft = radioPlayerLhs.isChecked();
        int iconHue = seekIconHue.getProgress();
        boolean iconBadge = switchIconBadge.isChecked();
        String appName = editAppName.getText().toString().trim();
        if (appName.isEmpty()) appName = secondary ? "SpotifyPlus2" : "SpotifyPlus";
        String pkg = secondary ? BuildConfigData.SECONDARY_PACKAGE : BuildConfigData.PRIMARY_PACKAGE;
        float scaleFactor = selectedScaleFactor();
        final String finalAppName = appName;
        final File source = sourceFile(false);

        setBusy(true, "Starting Spotify 9.1 patch…");
        showProgress(5, "PREPARING", "Preparing Spotify 9.1.82 APKM bundle…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "build91");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create 9.1 build directory");
                File signedDir = new File(work, "signed");
                runOnUiThread(() -> showIndeterminate("PATCHING SPOTIFY 9.1", "Applying Spotify 9.1 settings to the APKM bundle…"));
                List<File> signedApks = ApkPatchEngine91.buildSignedBundle(this, source, signedDir, secondary, finalAppName,
                        scaleFactor, bydLayout, playerLeft, autoResume, iconHue, iconBadge, this::postPatchDetail);
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
                showProgressFromWorker(95, "STAGING INSTALL", "Creating one Android split-APK installation session…");
                PackageInstallHelper.installSplitApks(this, signedApks, dm, pkg);
                runOnUiThread(() -> showProgress(98, "INSTALLING", "Confirm the Android installation prompt if shown, then wait for Spotify installation to complete."));
            } catch (Exception e) { reportPatchFailure(e); }
        });
    }

    private void patchAndInstall89() {
        boolean secondary = radioSecondary.isChecked();
        Spotify89ResourceUtil.FontPreset fontPreset = selected89FontPreset();
        boolean rightPanel = radio89PanelRight.isChecked();
        boolean preventPortrait = switch89PreventPortrait.isChecked();
        boolean autoResume = switchAutoResume.isChecked();
        int iconHue = seekIconHue.getProgress();
        boolean iconBadge = switchIconBadge.isChecked();
        String appName = editAppName.getText().toString().trim();
        if (appName.isEmpty()) appName = secondary ? "SpotifyPlus2" : "SpotifyPlus";
        String pkg = secondary ? BuildConfigData.V89_SECONDARY_PACKAGE : BuildConfigData.V89_PRIMARY_PACKAGE;
        final String finalAppName = appName;
        final File source = sourceFile(true);

        setBusy(true, "Starting Spotify 8.9 patch…");
        showProgress(5, "PREPARING", "Preparing Spotify 8.9 APKM bundle…");
        worker.execute(() -> {
            File work = new File(getCacheDir(), "build89");
            try {
                deleteTree(work);
                if (!work.mkdirs()) throw new IOException("Cannot create 8.9 build directory");
                File signedDir = new File(work, "signed");
                runOnUiThread(() -> showIndeterminate("PATCHING SPOTIFY 8.9", "Applying Spotify 8.9 settings to the APKM bundle…"));
                List<File> signedApks = ApkPatchEngine89.buildSignedBundle(this, source, signedDir, secondary, finalAppName,
                        fontPreset, rightPanel, preventPortrait, autoResume, iconHue, iconBadge, this::postPatchDetail);
                showProgressFromWorker(91, "SIGNING COMPLETE", "Spotify 8.9 base and compatible splits signed with one local certificate.");
                showProgressFromWorker(95, "STAGING INSTALL", "Creating one Android split-APK installation session…");
                PackageInstallHelper.installSplitApks(this, signedApks, pkg);
                runOnUiThread(() -> showProgress(98, "INSTALLING", "Confirm the Android installation prompt if shown, then wait for installation to complete."));
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
                    : (engineWaze ? "PATCHING WAZE" : (engine89 ? "PATCHING SPOTIFY 8.9" : "PATCHING SPOTIFY 9.1")));
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
        if (engineWaze) {
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
        if (engineWaze) {
            PackageInfo installed = installedPackageInfo(BuildConfigData.WAZE_PACKAGE);
            WazePatchEngine.Validation source = wazeSourceValidation;
            long targetCode = source != null && source.ok ? source.versionCode : BuildConfigData.WAZE_SUPPORTED_VERSION_CODE;
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
                        : (engineWaze ? "Waze · supported " + WAZE_SUPPORTED_VERSIONS
                        : "Spotify " + (engine89 ? BuildConfigData.V89_SUPPORTED_VERSION : BuildConfigData.SUPPORTED_VERSION))).append("\n")
                .append("Source valid: ").append(currentSourceValid()).append("\n");
        if (engineAbrp) {
            s.append(diagnosticStatus("ABRP", BuildConfigData.ABRP_PACKAGE)).append("\n")
                    .append("Supported ABRP version: ").append(BuildConfigData.ABRP_SUPPORTED_VERSION).append(" (")
                    .append(BuildConfigData.ABRP_SUPPORTED_VERSION_CODE).append(")\n")
                    .append("ABRP source type: APKM universal 120-640dpi split bundle\n")
                    .append("Direct BYD battery data: ").append(switchAbrpDirectBattery != null && switchAbrpDirectBattery.isChecked()).append("\n")
                    .append("Default appearance Automatic (Day/Night): ").append(switchAbrpAutoDayNight != null && switchAbrpAutoDayNight.isChecked()).append("\n")
                    .append("BYD native Cerence TTS: ").append(switchAbrpNativeTts != null && switchAbrpNativeTts.isChecked()).append("\n");
        } else if (engineWaze) {
            WazePatchEngine.Validation v = wazeSourceValidation;
            s.append(diagnosticStatus("Waze", BuildConfigData.WAZE_PACKAGE)).append("\n")
                    .append("Waze signed by this patcher: ").append(isInstalled(BuildConfigData.WAZE_PACKAGE) && isSignedByManager(BuildConfigData.WAZE_PACKAGE)).append("\n")
                    .append("Supported Waze versions: ").append(WAZE_SUPPORTED_VERSIONS).append("\n")
                    .append("Selected Waze source: ").append(v == null ? "not validated" : v.displayVersion()).append("\n")
                    .append("Selected source supported: ").append(isSupportedWazeVersion(v)).append("\n")
                    .append("Wide-screen optimisations: forced ON\n")
                    .append("EV features + CCS2 default: ").append(switchWazeEv.isChecked()).append("\n")
                    .append("Remove unsupported microphone button: ").append(switchWazeRemoveMic.isChecked()).append("\n")
                    .append("Waze source type: APKM split bundle\n");
        } else {
            s.append(diagnosticStatus("Official", BuildConfigData.OFFICIAL_PACKAGE)).append("\n")
                    .append(diagnosticStatus("Primary", currentPrimaryPackage())).append("\n")
                    .append(diagnosticStatus("Secondary", currentSecondaryPackage())).append("\n")
                    .append("Selected slot: ").append(radioPrimary.isChecked() ? "Primary" : "Secondary").append("\n");
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
        radioEngine91.setEnabled(!busy);
        radioEngine89.setEnabled(!busy);
        radioEngineWaze.setEnabled(!busy);
        radioEngineAbrp.setEnabled(!busy);
        if (switchWazeEv != null) switchWazeEv.setEnabled(!busy);
        if (switchWazeRemoveMic != null) switchWazeRemoveMic.setEnabled(!busy);
        if (switchAbrpDirectBattery != null) switchAbrpDirectBattery.setEnabled(!busy);
        if (switchAbrpAutoDayNight != null) switchAbrpAutoDayNight.setEnabled(!busy);
        if (switchAbrpNativeTts != null) switchAbrpNativeTts.setEnabled(!busy);
        if (txtProgress != null) txtProgress.setText(message);
    }

    private static String displayNameForPackage(String packageName) {
        if (BuildConfigData.ABRP_PACKAGE.equals(packageName)) return "ABRP";
        if (BuildConfigData.WAZE_PACKAGE.equals(packageName)) return "Waze";
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

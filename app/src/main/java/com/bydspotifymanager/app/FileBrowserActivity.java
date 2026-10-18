package com.bydspotifymanager.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// hand rolled file browser - the IVI's native file manager only has launch intent support
public class FileBrowserActivity extends AppCompatActivity {
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_SUBTITLE = "subtitle";
    static final String EXTRA_ACCENT = "accent";
    static final String EXTRA_ON_ACCENT = "on_accent";
    /** A file name "matches" when it contains one of these versions and one of the keywords. */
    static final String EXTRA_MATCH_VERSIONS = "match_versions";
    static final String EXTRA_MATCH_KEYWORDS = "match_keywords";
    static final String EXTRA_RESULT_PATH = "result_path";
    /** Result code asking the caller to fall back to a third-party file manager. */
    static final int RESULT_USE_EXTERNAL = RESULT_FIRST_USER + 1;

    private static final int REQUEST_STORAGE = 41;
    private static final int SCAN_MAX_DEPTH = 4;
    private static final int SCAN_MAX_DIRS = 4000;
    private static final String[] BYD_REMOVABLE_ROOTS = {"/storage/usbotg", "/storage/sdcard"};

    private static final int ROW_SELECTED_ALPHA = 34;
    private static final int NEUTRAL_ICON = Color.rgb(205, 205, 205);
    private static final int NEUTRAL_ICON_BG = Color.rgb(42, 42, 42);
    private static final int DIM_TEXT = Color.rgb(120, 120, 120);

    private static final class Location {
        final String label, detail;
        final int icon;
        final File root;        // null for the scan view
        Location(String label, String detail, int icon, File root) {
            this.label = label; this.detail = detail; this.icon = icon; this.root = root;
        }
        boolean isScan() { return root == null; }
    }

    private static final class Entry {
        final File file;
        final boolean dir, match, source;
        final long size, modified;
        final String where;     // scan view: folder the file was found in
        Entry(File file, boolean dir, boolean match, boolean source, String where) {
            this.file = file; this.dir = dir; this.match = match; this.source = source; this.where = where;
            this.size = dir ? 0 : file.length();
            this.modified = file.lastModified();
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int loadGeneration;

    private int accent, onAccent;
    private String[] matchVersions = new String[0], matchKeywords = new String[0];

    private LinearLayout locationsContainer, selectionBar, stateBox;
    private ListView listFiles;
    private ProgressBar progress;
    private TextView txtCurrentPath, btnFolderUp, btnShowAll, txtStateTitle, txtStateMessage;
    private TextView txtSelectedName, txtSelectedMeta;
    private ImageView imgCurrentLocation, imgState;
    private Button btnStateAction, btnUseFile;
    private HorizontalScrollView pathScroll;

    private final List<Location> locations = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final EntryAdapter adapter = new EntryAdapter();
    private Location currentLocation;
    private File currentDir;
    private Entry selected;
    private boolean sourceFilesOnly = true;
    private boolean permissionAsked, permissionDenied;

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshLocations(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);
        Intent in = getIntent();
        accent = in.getIntExtra(EXTRA_ACCENT, Color.rgb(29, 185, 84));
        onAccent = in.getIntExtra(EXTRA_ON_ACCENT, Color.BLACK);
        if (in.getStringArrayExtra(EXTRA_MATCH_VERSIONS) != null) matchVersions = in.getStringArrayExtra(EXTRA_MATCH_VERSIONS);
        if (in.getStringArrayExtra(EXTRA_MATCH_KEYWORDS) != null) matchKeywords = in.getStringArrayExtra(EXTRA_MATCH_KEYWORDS);

        locationsContainer = findViewById(R.id.locationsContainer);
        selectionBar = findViewById(R.id.selectionBar);
        stateBox = findViewById(R.id.stateBox);
        listFiles = findViewById(R.id.listFiles);
        progress = findViewById(R.id.progressBrowser);
        txtCurrentPath = findViewById(R.id.txtCurrentPath);
        btnFolderUp = findViewById(R.id.btnFolderUp);
        btnShowAll = findViewById(R.id.btnShowAllFiles);
        txtStateTitle = findViewById(R.id.txtStateTitle);
        txtStateMessage = findViewById(R.id.txtStateMessage);
        txtSelectedName = findViewById(R.id.txtSelectedName);
        txtSelectedMeta = findViewById(R.id.txtSelectedMeta);
        imgCurrentLocation = findViewById(R.id.imgCurrentLocation);
        imgState = findViewById(R.id.imgState);
        btnStateAction = findViewById(R.id.btnStateAction);
        btnUseFile = findViewById(R.id.btnUseFile);
        pathScroll = findViewById(R.id.pathScroll);

        String title = in.getStringExtra(EXTRA_TITLE);
        if (title != null) ((TextView) findViewById(R.id.txtBrowserTitle)).setText(title);
        ((TextView) findViewById(R.id.txtBrowserSubtitle)).setText(in.getStringExtra(EXTRA_SUBTITLE));

        applyAccent();
        listFiles.setAdapter(adapter);
        listFiles.setOnItemClickListener((parent, view, position, id) -> onEntryClicked(entries.get(position)));
        ((ImageButton) findViewById(R.id.btnBrowserClose)).setOnClickListener(v -> finish());
        findViewById(R.id.btnBrowserRefresh).setOnClickListener(v -> refreshLocations());
        btnFolderUp.setOnClickListener(v -> goUp());
        btnShowAll.setOnClickListener(v -> {
            sourceFilesOnly = !sourceFilesOnly;
            styleFilterChip();
            reload();
        });
        btnUseFile.setOnClickListener(v -> { if (selected != null) returnFile(selected.file); });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (canGoUp()) goUp(); else finish();
            }
        });

        refreshLocations();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addDataScheme("file");
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    @Override protected void onStop() {
        super.onStop();
        unregisterReceiver(mediaReceiver);
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from Android's permission settings screen.
        if (currentLocation != null && stateBox.getVisibility() == View.VISIBLE && hasStorageAccess()) refreshLocations();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }


    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    
    private void showPermissionState() {
        entries.clear();
        adapter.notifyDataSetChanged();
        progress.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent settings = allFilesAccessIntent();
            if (settings != null) {
                showState(android.R.drawable.ic_lock_idle_lock, "Allow access to files",
                        "Android needs \"All files access\" for this app to read .apkm files from storage and USB drives.",
                        "OPEN SETTINGS", () -> startActivity(settings));
            } else {
                showState(android.R.drawable.ic_lock_idle_lock, "File access is blocked on this system",
                        "This head unit has no settings screen for \"All files access\". Use another file manager, "
                                + "or grant access once from a computer:\nadb shell appops set " + getPackageName()
                                + " MANAGE_EXTERNAL_STORAGE allow",
                        "USE OTHER FILE MANAGER", this::returnUseExternal);
            }
            return;
        }
        boolean permanentlyDenied = permissionDenied
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (permanentlyDenied) {
            showState(android.R.drawable.ic_lock_idle_lock, "Storage access is turned off",
                    "Turn on the Storage permission for BYD Multi Patcher in app settings, then come back.",
                    "OPEN APP SETTINGS", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))));
        } else {
            showState(android.R.drawable.ic_lock_idle_lock, "Allow storage access",
                    "The built-in browser needs permission to read files on this unit and on USB drives.",
                    "ALLOW ACCESS", this::requestStorage);
        }
    }

    private Intent allFilesAccessIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        Intent perApp = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
        if (perApp.resolveActivity(getPackageManager()) != null) return perApp;
        Intent list = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        return list.resolveActivity(getPackageManager()) != null ? list : null;
    }

    private void requestStorage() {
        permissionAsked = true;
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;
        if (hasStorageAccess()) {
            refreshLocations();
        } else {
            permissionDenied = true;
            showPermissionState();
        }
    }


    private void refreshLocations() {
        Location previous = currentLocation;
        locations.clear();
        locations.addAll(discoverLocations());
        Location target = locations.get(0);
        if (previous != null && !previous.isScan()) {
            for (Location l : locations) if (!l.isScan() && l.root.equals(previous.root)) target = l;
        }
        renderLocations();
        boolean sameRoot = previous != null && !previous.isScan() && !target.isScan() && target.root.equals(previous.root);
        openLocation(target, sameRoot && currentDir != null && currentDir.isDirectory() ? currentDir : null);
    }

    private List<Location> discoverLocations() {
        List<Location> out = new ArrayList<>();
        out.add(new Location("All APKM files", "Search this unit and USB", android.R.drawable.ic_menu_search, null));
        Set<String> seen = new HashSet<>();
        File internal = Environment.getExternalStorageDirectory();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads.isDirectory()) out.add(new Location("Downloads", "Internal storage", android.R.drawable.stat_sys_download, downloads));
        out.add(new Location("Internal storage", internal.getPath(), android.R.drawable.ic_menu_save, internal));
        seen.add(canonical(internal));

        StorageManager sm = getSystemService(StorageManager.class);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable()) continue;
                String state = v.getState();
                if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                File dir = volumeDirectory(v);
                if (dir == null || !seen.add(canonical(dir))) continue;
                String desc = v.getDescription(this);
                boolean sd = desc != null && desc.toLowerCase(Locale.ROOT).contains("sd");
                out.add(new Location(desc == null || desc.isEmpty() ? (sd ? "SD card" : "USB drive") : desc,
                        dir.getPath(), sd ? android.R.drawable.stat_notify_sdcard : android.R.drawable.stat_notify_sdcard_usb, dir));
            }
        }
        // BYD exposes its USB and SD mounts under fixed paths as well (see the stock FileManager).
        for (String path : BYD_REMOVABLE_ROOTS) {
            File dir = new File(path);
            if (dir.isDirectory() && dir.list() != null && seen.add(canonical(dir))) {
                boolean sd = path.endsWith("sdcard");
                out.add(new Location(sd ? "SD card" : "USB drive", path, sd ? android.R.drawable.stat_notify_sdcard : android.R.drawable.stat_notify_sdcard_usb, dir));
            }
        }
        // Anything else mounted under /storage that we can read.
        File[] mounts = new File("/storage").listFiles();
        if (mounts != null) {
            Arrays.sort(mounts);
            for (File dir : mounts) {
                String name = dir.getName();
                if ("emulated".equals(name) || "self".equals(name) || !dir.isDirectory() || dir.list() == null) continue;
                if (seen.add(canonical(dir))) out.add(new Location("Storage " + name, dir.getPath(), android.R.drawable.stat_notify_sdcard_usb, dir));
            }
        }
        return out;
    }

    private static File volumeDirectory(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return v.getDirectory();
        try {
            return (File) StorageVolume.class.getMethod("getPathFile").invoke(v);
        } catch (Exception ignored) {}
        try {
            Object path = StorageVolume.class.getMethod("getPath").invoke(v);
            return path == null ? null : new File(path.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String canonical(File f) {
        try { return f.getCanonicalPath(); } catch (IOException e) { return f.getAbsolutePath(); }
    }

    private void renderLocations() {
        locationsContainer.removeAllViews();
        boolean horizontal = locationsContainer.getOrientation() == LinearLayout.HORIZONTAL;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Location loc : locations) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(dp(horizontal ? 48 : 60));
            item.setPadding(dp(12), dp(8), dp(horizontal ? 16 : 12), dp(8));
            item.setTag(loc);

            ImageView icon = new ImageView(this);
            icon.setImageResource(loc.icon);
            item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = horizontal
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.setMarginStart(dp(12));
            item.addView(texts, textLp);

            TextView label = new TextView(this);
            label.setText(loc.label);
            label.setTextSize(15);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            label.setSingleLine(true);
            texts.addView(label);
            if (!horizontal) {
                TextView detail = new TextView(this);
                detail.setText(loc.detail);
                detail.setTextSize(12);
                detail.setTextColor(getColor(R.color.text_secondary));
                detail.setSingleLine(true);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                texts.addView(detail);
            }

            LinearLayout.LayoutParams lp = horizontal
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (horizontal) lp.setMarginEnd(dp(8)); else lp.bottomMargin = dp(6);
            item.setOnClickListener(v -> openLocation(loc, null));
            locationsContainer.addView(item, lp);
        }
        styleLocations();
    }

    private void styleLocations() {
        for (int i = 0; i < locationsContainer.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) locationsContainer.getChildAt(i);
            boolean active = item.getTag() == currentLocation;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(active ? BrowseChoiceDialog.withAlpha(accent, 38) : Color.rgb(32, 32, 32));
            bg.setStroke(dp(1), active ? accent : Color.rgb(48, 48, 48));
            item.setBackground(ripple(bg, 10));
            ((ImageView) item.getChildAt(0)).setImageTintList(ColorStateList.valueOf(active ? accent : NEUTRAL_ICON));
            TextView label = (TextView) ((LinearLayout) item.getChildAt(1)).getChildAt(0);
            label.setTextColor(active ? accent : getColor(R.color.text_primary));
        }
    }

    private void openLocation(Location loc, File dir) {
        currentLocation = loc;
        currentDir = loc.isScan() ? null : (dir != null ? dir : loc.root);
        styleLocations();
        reload();
    }


    private boolean canGoUp() {
        return currentDir != null && currentLocation != null && !currentLocation.isScan()
                && !canonical(currentDir).equals(canonical(currentLocation.root)) && currentDir.getParentFile() != null;
    }

    private void goUp() {
        if (!canGoUp()) return;
        currentDir = currentDir.getParentFile();
        reload();
    }

    private void reload() {
        setSelected(null);
        updatePathBar();
        if (!hasStorageAccess()) {
            if (!permissionAsked && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) requestStorage();
            showPermissionState();
            return;
        }
        final int generation = ++loadGeneration;
        final Location loc = currentLocation;
        final File dir = currentDir;
        final boolean filter = sourceFilesOnly;
        final List<Location> roots = new ArrayList<>(locations);
        entries.clear();
        adapter.notifyDataSetChanged();
        stateBox.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            List<Entry> result;
            boolean unreadable = false;
            if (loc.isScan()) {
                result = scanForSourceFiles(roots);
            } else {
                File[] files = dir.listFiles();
                unreadable = files == null;
                result = new ArrayList<>();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        boolean isDir = f.isDirectory();
                        boolean source = !isDir && isSourceFile(f.getName());
                        if (filter && !isDir && !source) continue;
                        result.add(new Entry(f, isDir, source && matches(f.getName()), source, null));
                    }
                }
                sortEntries(result);
            }
            final List<Entry> done = result;
            final boolean failed = unreadable;
            main.post(() -> {
                if (generation != loadGeneration || isFinishing()) return;
                progress.setVisibility(View.GONE);
                entries.clear();
                entries.addAll(done);
                adapter.notifyDataSetChanged();
                listFiles.setSelection(0);
                if (failed) {
                    showState(android.R.drawable.ic_lock_idle_lock, "Can't open this folder",
                            "Android does not allow reading this location.", null, null);
                } else if (done.isEmpty()) {
                    if (loc.isScan()) {
                        showState(android.R.drawable.ic_menu_search, "No .apkm files found",
                                "Download the file from APKMirror, or copy it to a USB drive, then press refresh.", null, null);
                    } else {
                        showState(R.drawable.ic_folder, filter ? "No .apkm files in this folder" : "This folder is empty",
                                filter ? "Folders are still shown. Switch to ALL FILES to see everything." : "", null, null);
                    }
                } else {
                    stateBox.setVisibility(View.GONE);
                }
            });
        });
    }

    // scan everything
    private List<Entry> scanForSourceFiles(List<Location> roots) {
        List<Entry> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        for (Location l : roots) {
            if (l.isScan()) continue;
            queue.add(l.root);
            depths.add(0);
        }
        int dirs = 0;
        while (!queue.isEmpty() && dirs < SCAN_MAX_DIRS && !Thread.currentThread().isInterrupted()) {
            File dir = queue.poll();
            int depth = depths.poll();
            if (!visited.add(canonical(dir))) continue;
            dirs++;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith(".")) continue;
                if (f.isDirectory()) {
                    if (depth < SCAN_MAX_DEPTH && !"Android".equals(name)) {
                        queue.add(f);
                        depths.add(depth + 1);
                    }
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".apkm")) {
                    found.add(new Entry(f, false, matches(name), true, describeFolder(f.getParentFile(), roots)));
                }
            }
        }
        sortEntries(found);
        return found;
    }

    private String describeFolder(File folder, List<Location> roots) {
        String path = folder.getAbsolutePath();
        Location best = null;
        for (Location l : roots) {
            if (l.isScan() || "Downloads".equals(l.label)) continue;
            String root = l.root.getAbsolutePath();
            if ((path.equals(root) || path.startsWith(root + "/"))
                    && (best == null || root.length() > best.root.getAbsolutePath().length())) best = l;
        }
        if (best == null) return path;
        String rel = path.substring(best.root.getAbsolutePath().length());
        return best.label + rel.replace("/", " › ");
    }

    private static boolean isSourceFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".apkm") || n.endsWith(".apk") || n.endsWith(".zip");
    }

    private boolean matches(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean version = false, keyword = matchKeywords.length == 0;
        for (String v : matchVersions) if (n.contains(v.toLowerCase(Locale.ROOT))) version = true;
        for (String k : matchKeywords) if (n.contains(k.toLowerCase(Locale.ROOT))) keyword = true;
        return version && keyword;
    }

    // Folders A–Z, then matching files, then other files newest first
    private static void sortEntries(List<Entry> list) {
        Collections.sort(list, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            if (a.dir) return a.file.getName().compareToIgnoreCase(b.file.getName());
            if (a.match != b.match) return a.match ? -1 : 1;
            if (a.source != b.source) return a.source ? -1 : 1;
            return Long.compare(b.modified, a.modified);
        });
    }

    private void onEntryClicked(Entry e) {
        if (e.dir) {
            currentDir = e.file;
            reload();
        } else if (e.source || !sourceFilesOnly) {
            if (selected == e) returnFile(e.file); else setSelected(e);
        }
    }

    private void setSelected(Entry e) {
        selected = e;
        adapter.notifyDataSetChanged();
        if (e == null) {
            selectionBar.setVisibility(View.GONE);
            return;
        }
        selectionBar.setVisibility(View.VISIBLE);
        txtSelectedName.setText(e.file.getName());
        txtSelectedMeta.setText(Formatter.formatShortFileSize(this, e.size)
                + (e.match ? " · matches the selected version" : "")
                + " · " + (e.where != null ? e.where : e.file.getParent()));
    }

    private void updatePathBar() {
        if (currentLocation == null) return;
        imgCurrentLocation.setImageResource(currentLocation.isScan() ? android.R.drawable.ic_menu_search : currentLocation.icon);
        if (currentLocation.isScan()) {
            txtCurrentPath.setText("All .apkm files on this unit and connected drives");
            btnFolderUp.setVisibility(View.GONE);
        } else {
            String root = currentLocation.root.getAbsolutePath();
            String path = currentDir.getAbsolutePath();
            String rel = path.length() > root.length() && path.startsWith(root) ? path.substring(root.length()) : "";
            txtCurrentPath.setText(currentLocation.label + rel.replace("/", "  ›  "));
            btnFolderUp.setVisibility(View.VISIBLE);
            boolean up = canGoUp();
            btnFolderUp.setEnabled(up);
            btnFolderUp.setAlpha(up ? 1f : 0.4f);
        }
        btnShowAll.setVisibility(currentLocation.isScan() ? View.GONE : View.VISIBLE);
        pathScroll.post(() -> pathScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void showState(int icon, String title, String message, String action, Runnable onAction) {
        stateBox.setVisibility(View.VISIBLE);
        imgState.setImageResource(icon);
        txtStateTitle.setText(title);
        txtStateMessage.setText(message);
        txtStateMessage.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
        if (action == null) {
            btnStateAction.setVisibility(View.GONE);
        } else {
            btnStateAction.setVisibility(View.VISIBLE);
            btnStateAction.setText(action);
            btnStateAction.setOnClickListener(v -> onAction.run());
        }
    }

    private void returnFile(File file) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_PATH, file.getAbsolutePath()));
        finish();
    }

    private void returnUseExternal() {
        setResult(RESULT_USE_EXTERNAL);
        finish();
    }

    private void applyAccent() {
        ColorStateList buttonTint = ColorStateList.valueOf(accent);
        for (Button b : new Button[]{btnUseFile, btnStateAction}) {
            b.setBackgroundTintList(buttonTint);
            b.setTextColor(onAccent);
        }
        imgCurrentLocation.setImageTintList(ColorStateList.valueOf(accent));
        GradientDrawable stateCircle = new GradientDrawable();
        stateCircle.setShape(GradientDrawable.OVAL);
        stateCircle.setColor(BrowseChoiceDialog.withAlpha(accent, 40));
        imgState.setBackground(stateCircle);
        imgState.setImageTintList(ColorStateList.valueOf(accent));
        ((ImageView) findViewById(R.id.imgSelected)).setImageTintList(ColorStateList.valueOf(accent));
        Drawable up = getDrawable(android.R.drawable.arrow_up_float);
        if (up != null) {
            up.setBounds(0, 0, dp(18), dp(18));
            btnFolderUp.setCompoundDrawablesRelative(up, null, null, null);
            btnFolderUp.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.text_primary)));
        }
        progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        styleFilterChip();
    }

    private void styleFilterChip() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(sourceFilesOnly ? BrowseChoiceDialog.withAlpha(accent, 40) : Color.rgb(36, 36, 36));
        bg.setStroke(dp(1), sourceFilesOnly ? accent : Color.rgb(64, 64, 64));
        btnShowAll.setBackground(ripple(bg, 20));
        btnShowAll.setText(sourceFilesOnly ? "APKM ONLY" : "ALL FILES");
        btnShowAll.setTextColor(sourceFilesOnly ? accent : getColor(R.color.text_primary));
    }

    private Drawable ripple(Drawable content, int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(BrowseChoiceDialog.withAlpha(accent, 60)), content, mask);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(FileBrowserActivity.this).inflate(R.layout.item_file_row, parent, false);
            Entry e = entries.get(position);
            boolean isSelected = e == selected;
            boolean pickable = e.dir || e.source || !sourceFilesOnly;

            ImageView icon = row.findViewById(R.id.imgFileIcon);
            icon.setImageResource(e.dir ? R.drawable.ic_folder : android.R.drawable.ic_input_get);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setCornerRadius(dp(12));
            iconBg.setColor(e.source ? BrowseChoiceDialog.withAlpha(accent, e.match ? 60 : 32) : NEUTRAL_ICON_BG);
            icon.setBackground(iconBg);
            icon.setImageTintList(ColorStateList.valueOf(e.source ? accent : NEUTRAL_ICON));

            TextView name = row.findViewById(R.id.txtFileName);
            name.setText(e.file.getName());
            name.setTextColor(pickable ? getColor(R.color.text_primary) : DIM_TEXT);
            name.setTypeface(null, e.match || isSelected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

            TextView meta = row.findViewById(R.id.txtFileMeta);
            String date = DateUtils.formatDateTime(FileBrowserActivity.this, e.modified,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_YEAR);
            if (e.dir) meta.setText("Folder · " + date);
            else meta.setText(Formatter.formatShortFileSize(FileBrowserActivity.this, e.size) + " · " + date
                    + (e.where != null ? " · " + e.where : ""));

            TextView tag = row.findViewById(R.id.txtFileTag);
            if (e.match) {
                tag.setVisibility(View.VISIBLE);
                tag.setText("MATCH");
                GradientDrawable tagBg = new GradientDrawable();
                tagBg.setCornerRadius(dp(6));
                tagBg.setColor(BrowseChoiceDialog.withAlpha(accent, 40));
                tag.setBackground(tagBg);
                tag.setTextColor(accent);
                tag.setLetterSpacing(0.06f);
            } else {
                tag.setVisibility(View.GONE);
            }
            row.findViewById(R.id.imgFileChevron).setVisibility(e.dir ? View.VISIBLE : View.GONE);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            if (isSelected) {
                bg.setColor(BrowseChoiceDialog.withAlpha(accent, ROW_SELECTED_ALPHA));
                bg.setStroke(dp(2), accent);
            } else {
                bg.setColor(Color.TRANSPARENT);
            }
            row.setBackground(pickable ? ripple(bg, 12) : new ColorDrawable(Color.TRANSPARENT));
            return row;
        }
    }
}

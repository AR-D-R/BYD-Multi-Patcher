package com.bydspotifymanager.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in source-file browser based on PR #7 by lexwah.
 * It exists because DiLink 3 commonly has no useful ACTION_OPEN_DOCUMENT handler for APKM files.
 */
public class FileBrowserActivity extends AppCompatActivity {
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_SUBTITLE = "subtitle";
    static final String EXTRA_ACCENT = "accent";
    static final String EXTRA_ON_ACCENT = "on_accent";
    static final String EXTRA_MATCH_VERSIONS = "match_versions";
    static final String EXTRA_MATCH_KEYWORDS = "match_keywords";
    static final String EXTRA_RESULT_PATH = "result_path";
    static final int RESULT_USE_EXTERNAL = RESULT_FIRST_USER + 1;

    private static final int REQUEST_STORAGE = 41;
    private static final int SCAN_MAX_DEPTH = 4;
    private static final int SCAN_MAX_DIRS = 4000;
    private static final String[] BYD_REMOVABLE_ROOTS = {"/storage/usbotg", "/storage/sdcard"};

    private static final int CARD = Color.rgb(27, 27, 27);
    private static final int CARD_ALT = Color.rgb(32, 32, 32);
    private static final int STROKE = Color.rgb(52, 52, 52);
    private static final int SECONDARY = Color.rgb(179, 179, 179);

    private static final class Location {
        final String label;
        final String detail;
        final File root; // null = scan view
        Location(String label, String detail, File root) {
            this.label = label;
            this.detail = detail;
            this.root = root;
        }
        boolean isScan() { return root == null; }
    }

    private static final class Entry {
        final File file;
        final boolean dir;
        final boolean source;
        final boolean match;
        final String where;
        Entry(File file, boolean dir, boolean source, boolean match, String where) {
            this.file = file;
            this.dir = dir;
            this.source = source;
            this.match = match;
            this.where = where;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Location> locations = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final EntryAdapter adapter = new EntryAdapter();

    private int accent;
    private int onAccent;
    private String[] matchVersions = new String[0];
    private String[] matchKeywords = new String[0];
    private int loadGeneration;
    private boolean receiverRegistered;
    private boolean permissionRequested;
    private boolean showAllFiles;

    private LinearLayout locationsContainer;
    private TextView txtPath;
    private TextView txtEmpty;
    private TextView txtSelected;
    private Button btnUp;
    private Button btnFilter;
    private Button btnUse;
    private ProgressBar progress;
    private ListView list;
    private Location currentLocation;
    private File currentDir;
    private Entry selected;

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshLocations();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        accent = in.getIntExtra(EXTRA_ACCENT, Color.rgb(29, 185, 84));
        onAccent = in.getIntExtra(EXTRA_ON_ACCENT, Color.BLACK);
        String[] versions = in.getStringArrayExtra(EXTRA_MATCH_VERSIONS);
        String[] keywords = in.getStringArrayExtra(EXTRA_MATCH_KEYWORDS);
        if (versions != null) matchVersions = versions;
        if (keywords != null) matchKeywords = keywords;
        buildUi(in.getStringExtra(EXTRA_TITLE), in.getStringExtra(EXTRA_SUBTITLE));

        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onEntryClicked(entries.get(position)));
        btnUp.setOnClickListener(v -> goUp());
        btnFilter.setOnClickListener(v -> {
            showAllFiles = !showAllFiles;
            btnFilter.setText(showAllFiles ? "SOURCE FILES" : "ALL FILES");
            reload();
        });
        btnUse.setOnClickListener(v -> { if (selected != null) returnFile(selected.file); });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (canGoUp()) goUp(); else finish();
            }
        });

        ensureAccessAndRefresh();
    }

    private void buildUi(String titleText, String subtitleText) {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(17, 17, 17));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        Button close = smallButton("BACK");
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(92), dp(48)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(14));
        header.addView(titleBox, titleLp);

        TextView title = text(titleText == null ? "SELECT SOURCE FILE" : titleText, 20, Color.WHITE, true);
        titleBox.addView(title);
        TextView subtitle = text(subtitleText == null ? "" : subtitleText, 13, SECONDARY, false);
        titleBox.addView(subtitle);

        Button other = smallButton("OTHER MANAGER");
        other.setOnClickListener(v -> returnUseExternal());
        header.addView(other, new LinearLayout.LayoutParams(dp(160), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView locScroll = new HorizontalScrollView(this);
        locScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams locScrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        locScrollLp.topMargin = dp(10);
        root.addView(locScroll, locScrollLp);
        locationsContainer = new LinearLayout(this);
        locationsContainer.setOrientation(LinearLayout.HORIZONTAL);
        locScroll.addView(locationsContainer, new HorizontalScrollView.LayoutParams(HorizontalScrollView.LayoutParams.WRAP_CONTENT, HorizontalScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout pathRow = cardRow();
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pathLp.topMargin = dp(10);
        root.addView(pathRow, pathLp);
        btnUp = smallButton("UP");
        pathRow.addView(btnUp, new LinearLayout.LayoutParams(dp(76), dp(44)));
        txtPath = text("", 14, Color.WHITE, false);
        txtPath.setSingleLine(true);
        txtPath.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathTextLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pathTextLp.setMarginStart(dp(10));
        pathRow.addView(txtPath, pathTextLp);
        btnFilter = smallButton("ALL FILES");
        pathRow.addView(btnFilter, new LinearLayout.LayoutParams(dp(112), dp(44)));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.topMargin = dp(8);
        root.addView(progress, progressLp);

        txtEmpty = text("", 15, SECONDARY, false);
        txtEmpty.setGravity(Gravity.CENTER);
        txtEmpty.setPadding(dp(18), dp(24), dp(18), dp(24));
        txtEmpty.setVisibility(View.GONE);
        root.addView(txtEmpty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDividerHeight(0);
        list.setCacheColorHint(Color.TRANSPARENT);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(4);
        root.addView(list, listLp);

        LinearLayout selection = cardRow();
        LinearLayout.LayoutParams selectionLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selectionLp.topMargin = dp(10);
        root.addView(selection, selectionLp);
        txtSelected = text("Select a file", 14, SECONDARY, false);
        LinearLayout.LayoutParams selectedLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        selection.addView(txtSelected, selectedLp);
        btnUse = accentButton("USE FILE");
        btnUse.setEnabled(false);
        selection.addView(btnUse, new LinearLayout.LayoutParams(dp(140), dp(48)));

        setContentView(root);
    }

    private void ensureAccessAndRefresh() {
        if (hasStorageAccess()) {
            refreshLocations();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showAllFilesAccessDialog();
        } else if (!permissionRequested) {
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void showAllFilesAccessDialog() {
        Intent settings = allFilesAccessIntent();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Allow access to source files")
                .setMessage("The built-in browser needs file access to find APKM/APK files in Downloads and on USB drives.")
                .setNegativeButton("OTHER FILE MANAGER", (d, w) -> returnUseExternal());
        if (settings != null) {
            b.setPositiveButton("OPEN SETTINGS", (d, w) -> startActivity(settings));
        } else {
            b.setMessage("This head unit does not expose the Android 'All files access' settings screen. Use another file manager instead.")
                    .setPositiveButton("OK", null);
        }
        b.show();
    }

    private Intent allFilesAccessIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        Intent perApp = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
        if (perApp.resolveActivity(getPackageManager()) != null) return perApp;
        Intent all = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        return all.resolveActivity(getPackageManager()) != null ? all : null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;
        if (hasStorageAccess()) refreshLocations();
        else new AlertDialog.Builder(this)
                .setTitle("Storage access denied")
                .setMessage("Use another file manager, or grant Storage permission in Android settings.")
                .setPositiveButton("OTHER FILE MANAGER", (d, w) -> returnUseExternal())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentLocation == null && hasStorageAccess()) refreshLocations();
    }

    @Override protected void onStart() {
        super.onStart();
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addDataScheme("file");
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    @Override protected void onStop() {
        super.onStop();
        if (receiverRegistered) {
            unregisterReceiver(mediaReceiver);
            receiverRegistered = false;
        }
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refreshLocations() {
        if (!hasStorageAccess()) return;
        Location previous = currentLocation;
        locations.clear();
        locations.addAll(discoverLocations());
        renderLocations();
        Location target = locations.isEmpty() ? null : locations.get(0);
        if (previous != null && !previous.isScan()) {
            for (Location l : locations) {
                if (!l.isScan() && canonical(l.root).equals(canonical(previous.root))) {
                    target = l;
                    break;
                }
            }
        }
        if (target != null) openLocation(target, null);
    }

    private List<Location> discoverLocations() {
        List<Location> out = new ArrayList<>();
        out.add(new Location("ALL SOURCE FILES", "Search internal storage and USB", null));
        Set<String> seen = new HashSet<>();

        File internal = Environment.getExternalStorageDirectory();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads.isDirectory()) out.add(new Location("DOWNLOADS", "Internal storage", downloads));
        if (internal.isDirectory()) {
            out.add(new Location("INTERNAL", internal.getAbsolutePath(), internal));
            seen.add(canonical(internal));
        }

        StorageManager sm = getSystemService(StorageManager.class);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable()) continue;
                String state = v.getState();
                if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                File dir = volumeDirectory(v);
                if (dir == null || !dir.isDirectory() || !seen.add(canonical(dir))) continue;
                String desc = v.getDescription(this);
                out.add(new Location(desc == null || desc.trim().isEmpty() ? "USB / SD" : desc.toUpperCase(Locale.ROOT), dir.getAbsolutePath(), dir));
            }
        }

        for (String path : BYD_REMOVABLE_ROOTS) {
            File dir = new File(path);
            if (dir.isDirectory() && dir.list() != null && seen.add(canonical(dir))) {
                out.add(new Location(path.endsWith("sdcard") ? "SD CARD" : "USB DRIVE", path, dir));
            }
        }

        File[] mounts = new File("/storage").listFiles();
        if (mounts != null) {
            Arrays.sort(mounts);
            for (File dir : mounts) {
                String name = dir.getName();
                if ("emulated".equals(name) || "self".equals(name) || !dir.isDirectory() || dir.list() == null) continue;
                if (seen.add(canonical(dir))) out.add(new Location("STORAGE " + name.toUpperCase(Locale.ROOT), dir.getAbsolutePath(), dir));
            }
        }
        return out;
    }

    private static File volumeDirectory(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return v.getDirectory();
        try {
            Object path = StorageVolume.class.getMethod("getPathFile").invoke(v);
            if (path instanceof File) return (File) path;
        } catch (Exception ignored) {}
        try {
            Object path = StorageVolume.class.getMethod("getPath").invoke(v);
            return path == null ? null : new File(path.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void renderLocations() {
        locationsContainer.removeAllViews();
        for (Location loc : locations) {
            TextView chip = text(loc.label, 13, Color.WHITE, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), dp(11), dp(15), dp(11));
            chip.setTag(loc);
            chip.setOnClickListener(v -> openLocation(loc, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            locationsContainer.addView(chip, lp);
        }
        styleLocations();
    }

    private void styleLocations() {
        for (int i = 0; i < locationsContainer.getChildCount(); i++) {
            TextView chip = (TextView) locationsContainer.getChildAt(i);
            boolean active = chip.getTag() == currentLocation;
            chip.setTextColor(active ? accent : Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(active ? BrowseChoiceDialog.withAlpha(accent, 35) : CARD_ALT);
            bg.setStroke(dp(active ? 2 : 1), active ? accent : STROKE);
            chip.setBackground(ripple(bg));
        }
    }

    private void openLocation(Location loc, File dir) {
        currentLocation = loc;
        currentDir = loc.isScan() ? null : (dir != null ? dir : loc.root);
        styleLocations();
        reload();
    }

    private boolean canGoUp() {
        return currentLocation != null && !currentLocation.isScan() && currentDir != null
                && !canonical(currentDir).equals(canonical(currentLocation.root)) && currentDir.getParentFile() != null;
    }

    private void goUp() {
        if (!canGoUp()) return;
        currentDir = currentDir.getParentFile();
        reload();
    }

    private void reload() {
        clearSelection();
        updatePath();
        final int generation = ++loadGeneration;
        final Location loc = currentLocation;
        final File dir = currentDir;
        final boolean all = showAllFiles;
        final List<Location> roots = new ArrayList<>(locations);

        entries.clear();
        adapter.notifyDataSetChanged();
        txtEmpty.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);

        worker.execute(() -> {
            List<Entry> result = new ArrayList<>();
            boolean unreadable = false;
            if (loc == null) return;
            if (loc.isScan()) {
                result = scanForSourceFiles(roots);
            } else {
                File[] files = dir == null ? null : dir.listFiles();
                unreadable = files == null;
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        boolean isDir = f.isDirectory();
                        boolean source = !isDir && isSourceFile(f.getName());
                        if (!isDir && !all && !source) continue;
                        result.add(new Entry(f, isDir, source, source && matches(f.getName()), null));
                    }
                    sortEntries(result);
                }
            }
            final List<Entry> done = result;
            final boolean failed = unreadable;
            main.post(() -> {
                if (generation != loadGeneration || isFinishing()) return;
                progress.setVisibility(View.GONE);
                entries.clear();
                entries.addAll(done);
                adapter.notifyDataSetChanged();
                if (failed) showEmpty("Android does not allow reading this folder.");
                else if (done.isEmpty()) showEmpty(loc.isScan()
                        ? "No APKM/APK source files found. Download the file or connect a USB drive, then reopen this browser."
                        : (!all ? "No APKM/APK files in this folder. Folders are still shown." : "This folder is empty."));
            });
        });
    }

    private List<Entry> scanForSourceFiles(List<Location> roots) {
        List<Entry> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        for (Location l : roots) {
            if (l.isScan() || l.root == null) continue;
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
                } else if (isSourceFile(name)) {
                    found.add(new Entry(f, false, true, matches(name), describeFolder(f.getParentFile(), roots)));
                }
            }
        }
        sortEntries(found);
        return found;
    }

    private String describeFolder(File folder, List<Location> roots) {
        if (folder == null) return "";
        String path = folder.getAbsolutePath();
        Location best = null;
        for (Location l : roots) {
            if (l.isScan() || l.root == null || "DOWNLOADS".equals(l.label)) continue;
            String root = l.root.getAbsolutePath();
            if ((path.equals(root) || path.startsWith(root + "/"))
                    && (best == null || root.length() > best.root.getAbsolutePath().length())) best = l;
        }
        if (best == null) return path;
        String rel = path.substring(best.root.getAbsolutePath().length());
        return best.label + rel.replace("/", " › ");
    }

    private void onEntryClicked(Entry e) {
        if (e.dir) {
            currentDir = e.file;
            reload();
            return;
        }
        if (!e.source && !showAllFiles) return;
        if (selected == e) returnFile(e.file);
        else setSelection(e);
    }

    private void setSelection(Entry e) {
        selected = e;
        adapter.notifyDataSetChanged();
        btnUse.setEnabled(true);
        String meta = Formatter.formatShortFileSize(this, e.file.length());
        if (e.match) meta += " · matches selected profile";
        String folder = e.where != null ? e.where : e.file.getParent();
        txtSelected.setText(e.file.getName() + "\n" + meta + (folder == null ? "" : " · " + folder));
        txtSelected.setTextColor(Color.WHITE);
    }

    private void clearSelection() {
        selected = null;
        btnUse.setEnabled(false);
        txtSelected.setText("Select a file");
        txtSelected.setTextColor(SECONDARY);
    }

    private void updatePath() {
        if (currentLocation == null) return;
        if (currentLocation.isScan()) {
            txtPath.setText("All APKM/APK files on this unit and connected drives");
            btnUp.setVisibility(View.GONE);
            btnFilter.setVisibility(View.GONE);
        } else {
            String root = currentLocation.root.getAbsolutePath();
            String path = currentDir == null ? root : currentDir.getAbsolutePath();
            String rel = path.length() > root.length() && path.startsWith(root) ? path.substring(root.length()) : "";
            txtPath.setText(currentLocation.label + rel.replace("/", "  ›  "));
            btnUp.setVisibility(View.VISIBLE);
            btnUp.setEnabled(canGoUp());
            btnFilter.setVisibility(View.VISIBLE);
        }
    }

    private void showEmpty(String message) {
        txtEmpty.setText(message);
        txtEmpty.setVisibility(View.VISIBLE);
    }

    private void returnFile(File file) {
        if (file == null || !file.isFile()) {
            Toast.makeText(this, "That file is no longer available.", Toast.LENGTH_LONG).show();
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_PATH, file.getAbsolutePath()));
        finish();
    }

    private void returnUseExternal() {
        setResult(RESULT_USE_EXTERNAL);
        finish();
    }

    private boolean matches(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean version = matchVersions.length == 0;
        boolean keyword = matchKeywords.length == 0;
        for (String v : matchVersions) if (v != null && !v.isEmpty() && n.contains(v.toLowerCase(Locale.ROOT))) version = true;
        for (String k : matchKeywords) if (k != null && !k.isEmpty() && n.contains(k.toLowerCase(Locale.ROOT))) keyword = true;
        return version && keyword;
    }

    private static boolean isSourceFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".apkm") || n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".zip");
    }

    private static void sortEntries(List<Entry> list) {
        Collections.sort(list, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            if (a.dir) return a.file.getName().compareToIgnoreCase(b.file.getName());
            if (a.match != b.match) return a.match ? -1 : 1;
            if (a.source != b.source) return a.source ? -1 : 1;
            return Long.compare(b.file.lastModified(), a.file.lastModified());
        });
    }

    private static String canonical(File file) {
        if (file == null) return "";
        try { return file.getCanonicalPath(); }
        catch (IOException e) { return file.getAbsolutePath(); }
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Entry e = getItem(position);
            LinearLayout row = new LinearLayout(FileBrowserActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(11), dp(16), dp(11));
            row.setMinimumHeight(dp(66));

            TextView name = text((e.dir ? "DIR  " : (e.match ? "✓  " : "FILE  ")) + e.file.getName(), 15,
                    e.match ? accent : Color.WHITE, e.dir || e.match);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name);

            String meta;
            if (e.dir) {
                meta = "Folder";
            } else {
                meta = Formatter.formatShortFileSize(FileBrowserActivity.this, e.file.length());
                if (e.file.lastModified() > 0) meta += " · " + DateFormat.getMediumDateFormat(FileBrowserActivity.this).format(new Date(e.file.lastModified()));
                if (e.where != null && !e.where.isEmpty()) meta += " · " + e.where;
                if (e.match) meta += " · matches selected profile";
            }
            TextView detail = text(meta, 12, SECONDARY, false);
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            detailLp.topMargin = dp(3);
            row.addView(detail, detailLp);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            boolean isSelected = selected == e;
            bg.setColor(isSelected ? BrowseChoiceDialog.withAlpha(accent, 32) : Color.TRANSPARENT);
            bg.setStroke(dp(isSelected ? 2 : 1), isSelected ? accent : Color.TRANSPARENT);
            row.setBackground(ripple(bg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            row.setLayoutParams(lp);
            return row;
        }
    }

    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), STROKE);
        row.setBackground(bg);
        return row;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_ALT);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), STROKE);
        b.setBackground(ripple(bg));
        return b;
    }

    private Button accentButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setTextColor(onAccent);
        b.setBackgroundTintList(ColorStateList.valueOf(accent));
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value == null ? "" : value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private RippleDrawable ripple(GradientDrawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(10));
        return new RippleDrawable(ColorStateList.valueOf(BrowseChoiceDialog.withAlpha(accent, 48)), content, mask);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

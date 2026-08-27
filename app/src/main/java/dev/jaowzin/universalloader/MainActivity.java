package dev.jaowzin.universalloader;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaowzin.carromloader.runtime.CarromRuntimeCore;
import dev.jaowzin.carromloader.runtime.entity.pm.InstallResult;
import dev.jaowzin.carromloader.runtime.entity.pm.InstalledPackage;
import dev.jaowzin.carromloader.runtime.fake.frameworks.BPackageManager;

public final class MainActivity extends AppCompatActivity {
    private static final int USER_ID = 0;

    private static final int BLUE = Color.rgb(82, 126, 239);
    private static final int BLUE_DARK = Color.rgb(69, 109, 218);
    private static final int BG = Color.rgb(246, 247, 249);
    private static final int TEXT = Color.rgb(53, 59, 69);
    private static final int MUTED = Color.rgb(125, 132, 143);
    private static final int DIVIDER = Color.rgb(224, 227, 232);
    private static final int BADGE = Color.rgb(51, 113, 238);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private GridLayout appGrid;
    private TextView emptyHint;
    private TextView titleView;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildContent());
        refreshGrid();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGrid();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BLUE_DARK);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(14), dp(10), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyHint = text("", 13f, MUTED, false);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(dp(16), dp(28), dp(16), dp(6));
        emptyHint.setVisibility(View.GONE);
        content.addView(emptyHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        appGrid = new GridLayout(this);
        appGrid.setColumnCount(4);
        appGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        appGrid.setUseDefaultMargins(false);
        content.addView(appGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);
        bar.setBackgroundColor(BLUE);
        bar.setElevation(dp(4));

        TextView menu = toolbarIcon("☰");
        menu.setContentDescription("Menu");
        menu.setOnClickListener(this::showMainMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(46), dp(58)));

        titleView = text("Universal Loader", 19f, Color.WHITE, true);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(8), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, dp(58), 1f));

        TextView settings = toolbarIcon("⚙");
        settings.setContentDescription("Settings");
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(58)));
        return bar;
    }

    private TextView toolbarIcon(String glyph) {
        TextView view = text(glyph, 25f, Color.WHITE, false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(selectableBackground());
        return view;
    }

    private void refreshGrid() {
        if (loading || appGrid == null) return;
        loading = true;
        worker.execute(() -> {
            List<AppEntry> entries = loadVirtualApps();
            runOnUiThread(() -> {
                loading = false;
                renderGrid(entries);
            });
        });
    }

    private List<AppEntry> loadVirtualApps() {
        ArrayList<AppEntry> result = new ArrayList<>();
        try {
            List<InstalledPackage> installed = BPackageManager.get().getInstalledPackagesAsUser(USER_ID);
            PackageManager hostPm = getPackageManager();
            for (InstalledPackage pkg : installed) {
                if (pkg == null || pkg.packageName == null || pkg.packageName.equals(getPackageName())) continue;
                String label = pkg.packageName;
                Drawable icon = null;
                try {
                    ApplicationInfo hostInfo = hostPm.getApplicationInfo(pkg.packageName, 0);
                    CharSequence raw = hostPm.getApplicationLabel(hostInfo);
                    if (raw != null) label = raw.toString();
                    icon = hostPm.getApplicationIcon(hostInfo);
                } catch (Throwable ignored) {
                    try {
                        ApplicationInfo virtualInfo = pkg.getApplication();
                        if (virtualInfo != null) {
                            CharSequence raw = virtualInfo.loadLabel(hostPm);
                            if (raw != null) label = raw.toString();
                            icon = virtualInfo.loadIcon(hostPm);
                        }
                    } catch (Throwable ignoredAgain) {
                    }
                }
                result.add(new AppEntry(label, pkg.packageName, icon));
            }
        } catch (Throwable ignored) {
        }
        result.sort(Comparator.comparing(a -> a.label.toLowerCase()));
        return result;
    }

    private void renderGrid(List<AppEntry> apps) {
        appGrid.removeAllViews();
        emptyHint.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        emptyHint.setText(apps.isEmpty()
                ? "No cloned apps yet. Tap + to add one."
                : "");

        for (AppEntry app : apps) {
            appGrid.addView(buildAppTile(app), tileLayoutParams());
        }
        appGrid.addView(buildAddTile(), tileLayoutParams());
    }

    private GridLayout.LayoutParams tileLayoutParams() {
        int width = Math.max(dp(78),
                (getResources().getDisplayMetrics().widthPixels - dp(20)) / 4);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        lp.height = dp(112);
        lp.setMargins(0, dp(2), 0, dp(4));
        return lp;
    }

    private View buildAppTile(AppEntry app) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(5), dp(4), 0);
        tile.setBackground(selectableBackground());
        tile.setOnClickListener(v -> launchVirtual(app.packageName));
        tile.setOnLongClickListener(v -> {
            showAppActions(app);
            return true;
        });

        FrameLayout iconWrap = new FrameLayout(this);
        LinearLayout.LayoutParams iconWrapLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        tile.addView(iconWrap, iconWrapLp);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app.icon != null) {
            icon.setImageDrawable(app.icon);
        } else {
            GradientDrawable fallback = roundRect(Color.rgb(220, 224, 231), dp(14));
            icon.setBackground(fallback);
            TextView letter = text(app.label.substring(0, 1).toUpperCase(), 24f, BLUE, true);
            letter.setGravity(Gravity.CENTER);
            iconWrap.addView(letter, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        iconLp.topMargin = dp(1);
        iconWrap.addView(icon, 0, iconLp);

        TextView badge = text("Dual", 9f, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(4), dp(1), dp(4), dp(1));
        badge.setBackground(roundRect(BADGE, dp(8)));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.RIGHT | Gravity.BOTTOM);
        badgeLp.rightMargin = dp(1);
        badgeLp.bottomMargin = dp(3);
        iconWrap.addView(badge, badgeLp);

        TextView label = text(app.label, 12f, TEXT, false);
        label.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        label.setMaxLines(2);
        label.setPadding(dp(1), dp(3), dp(1), 0);
        tile.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tile;
    }

    private View buildAddTile() {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(5), dp(4), 0);
        tile.setBackground(selectableBackground());
        tile.setOnClickListener(v -> chooseApp());

        FrameLayout addBox = new FrameLayout(this);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(4));
        outline.setStroke(dp(1), Color.rgb(165, 170, 179), dp(4), dp(3));
        addBox.setBackground(outline);
        tile.addView(addBox, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView plus = text("+", 37f, BLUE, false);
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(0, 0, 0, dp(4));
        addBox.addView(plus, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = text("Add app", 12f, MUTED, false);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(7), 0, 0);
        tile.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tile;
    }

    private void chooseApp() {
        if (loading) return;
        loading = true;
        toast("Loading apps…");
        worker.execute(() -> {
            List<AppEntry> choices = loadHostAppsNotCloned();
            runOnUiThread(() -> {
                loading = false;
                showAppPicker(choices);
            });
        });
    }

    private List<AppEntry> loadHostAppsNotCloned() {
        ArrayList<AppEntry> apps = new ArrayList<>();
        Set<String> cloned = new HashSet<>();
        try {
            for (InstalledPackage item : BPackageManager.get().getInstalledPackagesAsUser(USER_ID)) {
                if (item != null && item.packageName != null) cloned.add(item.packageName);
            }
        } catch (Throwable ignored) {
        }

        PackageManager pm = getPackageManager();
        try {
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (info.packageName.equals(getPackageName())) continue;
                if (cloned.contains(info.packageName)) continue;
                if (pm.getLaunchIntentForPackage(info.packageName) == null) continue;
                CharSequence raw = pm.getApplicationLabel(info);
                String label = raw == null ? info.packageName : raw.toString();
                Drawable icon = null;
                try { icon = pm.getApplicationIcon(info); } catch (Throwable ignored) { }
                apps.add(new AppEntry(label, info.packageName, icon));
            }
        } catch (Throwable ignored) {
        }
        apps.sort(Comparator.comparing(a -> a.label.toLowerCase()));
        return apps;
    }

    private void showAppPicker(List<AppEntry> apps) {
        if (apps.isEmpty()) {
            toast("No more launchable apps found");
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label + "\n" + apps.get(i).packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add app")
                .setItems(labels, (dialog, which) -> cloneApp(apps.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void cloneApp(AppEntry app) {
        if (loading) return;
        loading = true;
        toast("Creating " + app.label + "…");
        worker.execute(() -> {
            try {
                CarromRuntimeCore core = CarromRuntimeCore.get();
                InstallResult result = core.installPackageAsUser(app.packageName, USER_ID);
                runOnUiThread(() -> {
                    loading = false;
                    if (result.success) {
                        toast(app.label + " cloned");
                        refreshGrid();
                    } else {
                        showError("Clone failed", result.msg);
                    }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    loading = false;
                    showError("Clone failed", String.valueOf(error));
                });
            }
        });
    }

    private void launchVirtual(String packageName) {
        TargetStore.setSelectedPackage(this, packageName);
        worker.execute(() -> {
            try {
                boolean launched = CarromRuntimeCore.get().launchApk(packageName, USER_ID);
                if (!launched) runOnUiThread(() -> toast("Could not open app"));
            } catch (Throwable error) {
                runOnUiThread(() -> showError("Launch error", String.valueOf(error)));
            }
        });
    }

    private void showAppActions(AppEntry app) {
        String[] actions = {"Open", "Plugins", "Reset clone"};
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) launchVirtual(app.packageName);
                    else if (which == 1) showPluginManager(app);
                    else confirmReset(app);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmReset(AppEntry app) {
        new AlertDialog.Builder(this)
                .setTitle("Remove cloned app?")
                .setMessage("This deletes only the isolated copy of " + app.label + ". The original app is untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> resetVirtual(app))
                .show();
    }

    private void resetVirtual(AppEntry app) {
        worker.execute(() -> {
            try {
                CarromRuntimeCore.get().uninstallPackageAsUser(app.packageName, USER_ID);
                runOnUiThread(() -> {
                    toast("Clone removed");
                    refreshGrid();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> showError("Remove failed", String.valueOf(error)));
            }
        });
    }

    private void showPluginManager(AppEntry app) {
        List<WorkspacePluginRegistry.Plugin> source = WorkspacePluginRegistry.list(this);
        ArrayList<WorkspacePluginRegistry.Plugin> plugins = new ArrayList<>();
        for (WorkspacePluginRegistry.Plugin plugin : source) {
            if (plugin.targetPackage.equals(app.packageName)) plugins.add(plugin);
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(2), dp(22), dp(4));

        TextView summary = text(plugins.isEmpty()
                ? "No plugin profiles for this app."
                : plugins.size() + " plugin profile(s)", 13f, MUTED, false);
        summary.setPadding(0, dp(4), 0, dp(12));
        body.addView(summary);

        for (WorkspacePluginRegistry.Plugin plugin : plugins) {
            TextView row = text((plugin.enabled ? "● " : "○ ") + plugin.name, 14f,
                    plugin.enabled ? TEXT : MUTED, false);
            row.setPadding(0, dp(8), 0, dp(8));
            row.setOnClickListener(v -> {
                WorkspacePluginRegistry.setEnabled(this, plugin.id, !plugin.enabled);
                showPluginManager(app);
            });
            row.setOnLongClickListener(v -> {
                WorkspacePluginRegistry.remove(this, plugin.id);
                toast("Plugin profile removed");
                showPluginManager(app);
                return true;
            });
            body.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle(app.label + " plugins")
                .setView(body)
                .setPositiveButton("Add profile", (dialog, which) -> addPluginProfile(app))
                .setNegativeButton("Close", null)
                .show();
    }

    private void addPluginProfile(AppEntry app) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("Plugin name");
        EditText description = new EditText(this);
        description.setHint("Description / notes");
        form.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("New plugin profile")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    WorkspacePluginRegistry.add(this,
                            name.getText().toString(),
                            app.packageName,
                            description.getText().toString());
                    toast("Plugin profile added");
                })
                .show();
    }

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Add app");
        menu.getMenu().add("Refresh");
        menu.getMenu().add("About");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if (title.equals("Add app")) chooseApp();
            else if (title.equals("Refresh")) refreshGrid();
            else showAbout();
            return true;
        });
        menu.show();
    }

    private void showSettings() {
        boolean runtimeOnline = false;
        int cloneCount = 0;
        try {
            runtimeOnline = CarromRuntimeCore.get().areServicesAvailable();
            cloneCount = BPackageManager.get().getInstalledPackagesAsUser(USER_ID).size();
        } catch (Throwable ignored) {
        }

        String message = "Runtime: " + (runtimeOnline ? "online" : "starting")
                + "\nCloned apps: " + cloneCount
                + "\n\nLong-press an app to manage plugins or remove its clone.";
        new AlertDialog.Builder(this)
                .setTitle("Universal Loader")
                .setMessage(message)
                .setPositiveButton("Refresh", (dialog, which) -> refreshGrid())
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Universal Loader")
                .setMessage("Isolated multi-app workspace. Tap + to clone an installed app, tap a tile to launch it, or long-press for options.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null ? "Unknown error" : message)
                .setPositiveButton("OK", null)
                .show();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable roundRect(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private Drawable selectableBackground() {
        android.util.TypedValue out = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true);
        try {
            return getDrawable(out.resourceId);
        } catch (Throwable ignored) {
            return ColorDrawableCompat.transparent();
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;

        AppEntry(String label, String packageName, Drawable icon) {
            this.label = label == null || label.trim().isEmpty() ? packageName : label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private static final class ColorDrawableCompat {
        static Drawable transparent() {
            return new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
        }
    }
}

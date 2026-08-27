package dev.jaowzin.universalloader;

import android.content.Intent;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaowzin.carromloader.runtime.CarromRuntimeCore;
import dev.jaowzin.carromloader.runtime.entity.pm.InstalledPackage;
import dev.jaowzin.carromloader.runtime.fake.frameworks.BPackageManager;

public final class LauncherActivity extends AppCompatActivity {
    private static final int USER_ID = 0;
    private static final int BLUE = Color.rgb(82, 126, 239);
    private static final int BLUE_DARK = Color.rgb(69, 109, 218);
    private static final int BG = Color.rgb(246, 247, 249);
    private static final int TEXT = Color.rgb(53, 59, 69);
    private static final int MUTED = Color.rgb(125, 132, 143);
    private static final int BADGE = Color.rgb(51, 113, 238);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GridLayout grid;
    private TextView empty;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(14), dp(10), dp(28));

        empty = text("No cloned apps yet. Tap + to add one.", 13f, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(24), dp(16), dp(8));
        empty.setVisibility(View.GONE);
        content.addView(empty);

        grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        content.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(content, new ScrollView.LayoutParams(
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

        TextView menu = text("☰", 25f, Color.WHITE, false);
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(46), dp(58)));

        TextView title = text("Universal Loader", 19f, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1f));

        TextView settings = text("⚙", 25f, Color.WHITE, false);
        settings.setGravity(Gravity.CENTER);
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(58)));
        return bar;
    }

    private void refresh() {
        if (loading || grid == null) return;
        loading = true;
        worker.execute(() -> {
            List<AppEntry> apps = loadClones();
            runOnUiThread(() -> {
                loading = false;
                render(apps);
            });
        });
    }

    private List<AppEntry> loadClones() {
        ArrayList<AppEntry> result = new ArrayList<>();
        PackageManager pm = getPackageManager();
        try {
            for (InstalledPackage item : BPackageManager.get().getInstalledPackagesAsUser(USER_ID)) {
                if (item == null || item.packageName == null || item.packageName.equals(getPackageName())) continue;
                String label = item.packageName;
                Drawable icon = null;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(item.packageName, 0);
                    CharSequence raw = pm.getApplicationLabel(info);
                    if (raw != null) label = raw.toString();
                    icon = pm.getApplicationIcon(info);
                } catch (Throwable ignored) {
                    try {
                        ApplicationInfo virtualInfo = item.getApplication();
                        if (virtualInfo != null) {
                            CharSequence raw = virtualInfo.loadLabel(pm);
                            if (raw != null) label = raw.toString();
                            icon = virtualInfo.loadIcon(pm);
                        }
                    } catch (Throwable ignoredAgain) {}
                }
                result.add(new AppEntry(label, item.packageName, icon));
            }
        } catch (Throwable ignored) {}
        result.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    private void render(List<AppEntry> apps) {
        grid.removeAllViews();
        empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        for (AppEntry app : apps) grid.addView(buildAppTile(app), tileParams());
        grid.addView(buildAddTile(), tileParams());
    }

    private GridLayout.LayoutParams tileParams() {
        int width = Math.max(dp(78), (getResources().getDisplayMetrics().widthPixels - dp(20)) / 4);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        lp.height = dp(116);
        lp.setMargins(0, dp(2), 0, dp(5));
        return lp;
    }

    private View buildAppTile(AppEntry app) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(5), dp(4), 0);
        tile.setOnClickListener(v -> open(app));
        tile.setOnLongClickListener(v -> {
            showActions(app);
            return true;
        });

        FrameLayout wrap = new FrameLayout(this);
        tile.addView(wrap, new LinearLayout.LayoutParams(dp(64), dp(64)));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app.icon != null) icon.setImageDrawable(app.icon);
        else icon.setBackground(roundRect(Color.rgb(220, 224, 231), dp(14)));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        iconLp.topMargin = dp(1);
        wrap.addView(icon, iconLp);

        TextView badge = text("Dual", 9f, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(4), dp(1), dp(4), dp(1));
        badge.setBackground(roundRect(BADGE, dp(8)));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.RIGHT | Gravity.BOTTOM);
        badgeLp.bottomMargin = dp(3);
        wrap.addView(badge, badgeLp);

        TextView name = text(app.label, 12f, TEXT, false);
        name.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setPadding(dp(1), dp(3), dp(1), 0);
        tile.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tile;
    }

    private View buildAddTile() {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(5), dp(4), 0);
        tile.setOnClickListener(v -> startActivity(new Intent(this, AppLibraryActivity.class)));

        FrameLayout box = new FrameLayout(this);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(4));
        outline.setStroke(dp(1), Color.rgb(165, 170, 179), dp(4), dp(3));
        box.setBackground(outline);
        tile.addView(box, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView plus = text("+", 37f, BLUE, false);
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(0, 0, 0, dp(4));
        box.addView(plus, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = text("Add app", 12f, MUTED, false);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(7), 0, 0);
        tile.addView(label);
        return tile;
    }

    private void open(AppEntry app) {
        TargetStore.setSelectedPackage(this, app.packageName);
        worker.execute(() -> {
            try {
                boolean ok = CarromRuntimeCore.get().launchApk(app.packageName, USER_ID);
                if (!ok) runOnUiThread(() -> toast("Could not open app"));
            } catch (Throwable error) {
                runOnUiThread(() -> showError("Launch failed", String.valueOf(error)));
            }
        });
    }

    private void showActions(AppEntry app) {
        String[] actions = {"Open", "App details", "Remove clone"};
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage(app.packageName)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) open(app);
                    else if (which == 1) showDetails(app);
                    else confirmRemove(app);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDetails(AppEntry app) {
        int plugins = WorkspacePluginRegistry.countEnabledFor(this, app.packageName);
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage("Package\n" + app.packageName + "\n\nEnabled plugin profiles\n" + plugins)
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmRemove(AppEntry app) {
        new AlertDialog.Builder(this)
                .setTitle("Remove cloned app?")
                .setMessage("Only the isolated copy of " + app.label + " will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> worker.execute(() -> {
                    try {
                        CarromRuntimeCore.get().uninstallPackageAsUser(app.packageName, USER_ID);
                        runOnUiThread(() -> {
                            toast("Clone removed");
                            refresh();
                        });
                    } catch (Throwable error) {
                        runOnUiThread(() -> showError("Remove failed", String.valueOf(error)));
                    }
                }))
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("App Library");
        menu.getMenu().add("Refresh");
        menu.getMenu().add("About");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if (title.equals("App Library")) startActivity(new Intent(this, AppLibraryActivity.class));
            else if (title.equals("Refresh")) refresh();
            else showAbout();
            return true;
        });
        menu.show();
    }

    private void showSettings() {
        boolean online = false;
        int clones = 0;
        try {
            online = CarromRuntimeCore.get().areServicesAvailable();
            clones = BPackageManager.get().getInstalledPackagesAsUser(USER_ID).size();
        } catch (Throwable ignored) {}
        new AlertDialog.Builder(this)
                .setTitle("Universal Loader")
                .setMessage("Runtime: " + (online ? "online" : "starting") + "\nCloned apps: " + clones
                        + "\n\nLong-press an app for details or removal.")
                .setPositiveButton("App Library", (dialog, which) ->
                        startActivity(new Intent(this, AppLibraryActivity.class)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Universal Loader")
                .setMessage("Home shows only isolated app copies. App Library shows installed apps with icon, name and package name.")
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

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
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
}

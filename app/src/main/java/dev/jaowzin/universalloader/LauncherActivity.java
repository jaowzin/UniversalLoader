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
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

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
    private static final int SURFACE = Color.WHITE;
    private static final int TEXT = Color.rgb(53, 59, 69);
    private static final int MUTED = Color.rgb(125, 132, 143);
    private static final int BORDER = Color.rgb(224, 227, 232);
    private static final int BADGE = Color.rgb(51, 113, 238);
    private static final int DANGER = Color.rgb(214, 67, 67);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private GridLayout appGrid;
    private TextView appsEmpty;
    private LinearLayout pluginList;
    private TextView pluginsEmpty;
    private ViewPager2 pager;
    private View appsPage;
    private View pluginsPage;
    private boolean loadingApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshApps();
        refreshPlugins();
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

        TabLayout tabs = new TabLayout(this);
        tabs.setBackgroundColor(BLUE);
        tabs.setTabTextColors(Color.rgb(214, 225, 255), Color.WHITE);
        tabs.setSelectedTabIndicatorColor(Color.WHITE);
        tabs.setSelectedTabIndicatorHeight(dp(3));
        tabs.setTabMode(TabLayout.MODE_FIXED);
        root.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        appsPage = buildAppsPage();
        pluginsPage = buildPluginsPage();

        pager = new ViewPager2(this);
        pager.setAdapter(new PagesAdapter());
        pager.setOffscreenPageLimit(2);
        root.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        new TabLayoutMediator(tabs, pager, (tab, position) ->
                tab.setText(position == 0 ? "Apps" : "Plugins")
        ).attach();

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position == 0) refreshApps();
                else refreshPlugins();
            }
        });

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

    private View buildAppsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(14), dp(10), dp(28));

        appsEmpty = text("No cloned apps yet. Tap + to add one.", 13f, MUTED, false);
        appsEmpty.setGravity(Gravity.CENTER);
        appsEmpty.setPadding(dp(16), dp(24), dp(16), dp(8));
        appsEmpty.setVisibility(View.GONE);
        content.addView(appsEmpty);

        appGrid = new GridLayout(this);
        appGrid.setColumnCount(4);
        appGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        appGrid.setUseDefaultMargins(false);
        content.addView(appGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildPluginsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(4), dp(2), dp(4), dp(12));

        TextView title = text("Plugins", 18f, TEXT, true);
        TextView subtitle = text("Load native .so libraries inside cloned app processes", 12f, MUTED, false);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        header.addView(title);
        header.addView(subtitle);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView importSo = text("⇩  Import .so", 14f, Color.WHITE, true);
        importSo.setGravity(Gravity.CENTER);
        importSo.setPadding(dp(15), dp(10), dp(15), dp(10));
        importSo.setBackground(roundRect(BLUE, dp(14)));
        importSo.setOnClickListener(v -> openNativePluginImporter());
        actions.addView(importSo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView addProfile = text("+  Profile", 14f, BLUE, true);
        addProfile.setGravity(Gravity.CENTER);
        addProfile.setPadding(dp(12), dp(10), dp(12), dp(10));
        addProfile.setBackground(roundRect(Color.rgb(233, 239, 255), dp(14)));
        addProfile.setOnClickListener(v -> choosePluginTarget());
        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        profileLp.leftMargin = dp(8);
        actions.addView(addProfile, profileLp);

        header.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(header);

        TextView nativeHelp = text(
                "Import .so → select library → choose cloned app → optionally set a process. " +
                        "Enabled native plugins are loaded before the target Application.onCreate().",
                11.5f,
                MUTED,
                false);
        nativeHelp.setPadding(dp(6), dp(2), dp(6), dp(14));
        content.addView(nativeHelp);

        pluginsEmpty = text("No plugins yet. Tap Import .so to add a native library.", 13f, MUTED, false);
        pluginsEmpty.setGravity(Gravity.CENTER);
        pluginsEmpty.setPadding(dp(18), dp(26), dp(18), dp(18));
        content.addView(pluginsEmpty);

        pluginList = new LinearLayout(this);
        pluginList.setOrientation(LinearLayout.VERTICAL);
        content.addView(pluginList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void openNativePluginImporter() {
        try {
            startActivity(new Intent(this, NativePluginImportActivity.class));
        } catch (Throwable error) {
            showError("Could not open .so importer", String.valueOf(error));
        }
    }

    private void refreshApps() {
        if (loadingApps || appGrid == null) return;
        loadingApps = true;
        worker.execute(() -> {
            List<AppEntry> apps = loadClones();
            runOnUiThread(() -> {
                loadingApps = false;
                renderApps(apps);
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
                    } catch (Throwable ignoredAgain) { }
                }
                result.add(new AppEntry(label, item.packageName, icon));
            }
        } catch (Throwable ignored) { }
        result.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    private void renderApps(List<AppEntry> apps) {
        appGrid.removeAllViews();
        appsEmpty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        for (AppEntry app : apps) appGrid.addView(buildAppTile(app), tileParams());
        appGrid.addView(buildAddTile(), tileParams());
    }

    private GridLayout.LayoutParams tileParams() {
        int width = Math.max(dp(78), (getResources().getDisplayMetrics().widthPixels - dp(20)) / 4);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        lp.height = dp(118);
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
            showCloneMenu(v, app);
            return true;
        });

        FrameLayout wrap = new FrameLayout(this);
        tile.addView(wrap, new LinearLayout.LayoutParams(dp(70), dp(66)));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app.icon != null) icon.setImageDrawable(app.icon);
        else icon.setBackground(roundRect(Color.rgb(220, 224, 231), dp(14)));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        iconLp.topMargin = dp(2);
        wrap.addView(icon, iconLp);

        TextView badge = text("Dual", 9f, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(4), dp(1), dp(4), dp(1));
        badge.setBackground(roundRect(BADGE, dp(8)));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.RIGHT | Gravity.BOTTOM);
        badgeLp.rightMargin = dp(5);
        badgeLp.bottomMargin = dp(3);
        wrap.addView(badge, badgeLp);

        TextView more = text("⋮", 21f, TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setBackground(roundRect(Color.argb(225, 255, 255, 255), dp(11)));
        more.setOnClickListener(v -> showCloneMenu(v, app));
        FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(dp(24), dp(30), Gravity.RIGHT | Gravity.TOP);
        moreLp.rightMargin = 0;
        wrap.addView(more, moreLp);

        TextView name = text(app.label, 12f, TEXT, false);
        name.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setPadding(dp(1), dp(2), dp(1), 0);
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

    private void showCloneMenu(View anchor, AppEntry app) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Open");
        menu.getMenu().add("App details");
        menu.getMenu().add("Plugins");
        menu.getMenu().add("Remove clone");
        menu.setOnMenuItemClickListener(item -> {
            String action = String.valueOf(item.getTitle());
            if (action.equals("Open")) open(app);
            else if (action.equals("App details")) showDetails(app);
            else if (action.equals("Plugins")) {
                pager.setCurrentItem(1, true);
                toast("Plugins for " + app.label);
            } else if (action.equals("Remove clone")) confirmRemove(app);
            return true;
        });
        menu.show();
    }

    private void showDetails(AppEntry app) {
        int plugins = WorkspacePluginRegistry.countEnabledFor(this, app.packageName);
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage("Package\n" + app.packageName + "\n\nEnabled plugins\n" + plugins)
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmRemove(AppEntry app) {
        new AlertDialog.Builder(this)
                .setTitle("Remove cloned app?")
                .setMessage("Only the isolated copy of " + app.label + " will be removed. The original app stays installed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> worker.execute(() -> {
                    try {
                        CarromRuntimeCore.get().uninstallPackageAsUser(app.packageName, USER_ID);
                        runOnUiThread(() -> {
                            toast("Clone removed");
                            refreshApps();
                            refreshPlugins();
                        });
                    } catch (Throwable error) {
                        runOnUiThread(() -> showError("Remove failed", String.valueOf(error)));
                    }
                }))
                .show();
    }

    private void refreshPlugins() {
        if (pluginList == null) return;
        List<WorkspacePluginRegistry.Plugin> plugins = WorkspacePluginRegistry.list(this);
        pluginList.removeAllViews();
        pluginsEmpty.setVisibility(plugins.isEmpty() ? View.VISIBLE : View.GONE);
        plugins.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
        for (WorkspacePluginRegistry.Plugin plugin : plugins) {
            pluginList.addView(buildPluginRow(plugin), pluginRowParams());
        }
    }

    private LinearLayout.LayoutParams pluginRowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private View buildPluginRow(WorkspacePluginRegistry.Plugin plugin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(10), dp(12));
        GradientDrawable bg = roundRect(SURFACE, dp(16));
        bg.setStroke(dp(1), BORDER);
        card.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        String typeLabel = plugin.isNativeLibrary() ? "Native .so" : "Profile";
        TextView name = text(plugin.name, 16f, TEXT, true);
        TextView pkg = text(typeLabel + "  •  " +
                        (plugin.targetPackage.isEmpty() ? "All workspaces" : plugin.targetPackage),
                11.5f, BLUE, false);
        pkg.setPadding(0, dp(2), 0, 0);
        copy.addView(name);
        copy.addView(pkg);

        if (plugin.isNativeLibrary()) {
            String process = plugin.processName.isEmpty() ? "all app processes" : plugin.processName;
            String phase = WorkspacePluginRegistry.PHASE_AFTER_ONCREATE.equals(plugin.loadPhase)
                    ? "after Application.onCreate"
                    : "before Application.onCreate";
            TextView runtime = text("Process: " + process + "  •  Load: " + phase,
                    10.5f, MUTED, false);
            runtime.setPadding(0, dp(3), 0, 0);
            copy.addView(runtime);
        }

        top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SwitchMaterial toggle = new SwitchMaterial(this);
        toggle.setChecked(plugin.enabled);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            WorkspacePluginRegistry.setEnabled(this, plugin.id, checked);
            NativePluginRuntime.reload(this);
            refreshPlugins();
        });
        top.addView(toggle);

        TextView more = text("⋮", 22f, TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> showPluginMenu(v, plugin));
        top.addView(more, new LinearLayout.LayoutParams(dp(38), dp(42)));
        card.addView(top);

        if (plugin.description != null && !plugin.description.trim().isEmpty()) {
            TextView desc = text(plugin.description, 12.5f, MUTED, false);
            desc.setPadding(0, dp(8), dp(6), 0);
            card.addView(desc);
        }
        return card;
    }

    private void showPluginMenu(View anchor, WorkspacePluginRegistry.Plugin plugin) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(plugin.enabled ? "Disable" : "Enable");
        if (plugin.isNativeLibrary()) menu.getMenu().add("Import another .so");
        menu.getMenu().add("Remove plugin");
        menu.setOnMenuItemClickListener(item -> {
            String action = String.valueOf(item.getTitle());
            if (action.equals("Enable") || action.equals("Disable")) {
                WorkspacePluginRegistry.setEnabled(this, plugin.id, !plugin.enabled);
                NativePluginRuntime.reload(this);
                refreshPlugins();
            } else if (action.equals("Import another .so")) {
                openNativePluginImporter();
            } else {
                confirmRemovePlugin(plugin);
            }
            return true;
        });
        menu.show();
    }

    private void confirmRemovePlugin(WorkspacePluginRegistry.Plugin plugin) {
        String detail = plugin.isNativeLibrary() ? "Native .so" : "Plugin profile";
        new AlertDialog.Builder(this)
                .setTitle("Remove plugin?")
                .setMessage(plugin.name + "\n" + detail + "\n" + plugin.targetPackage)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    if (plugin.isNativeLibrary()) {
                        NativePluginRuntime.removePluginFiles(this, plugin);
                    }
                    WorkspacePluginRegistry.remove(this, plugin.id);
                    NativePluginRuntime.reload(this);
                    refreshPlugins();
                    toast("Plugin removed");
                })
                .show();
    }

    private void choosePluginTarget() {
        worker.execute(() -> {
            List<AppEntry> clones = loadClones();
            runOnUiThread(() -> {
                if (clones.isEmpty()) {
                    toast("Clone an app first");
                    return;
                }
                String[] names = new String[clones.size()];
                for (int i = 0; i < clones.size(); i++) {
                    names[i] = clones.get(i).label + "\n" + clones.get(i).packageName;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Profile target")
                        .setItems(names, (dialog, which) -> showCreatePlugin(clones.get(which)))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void showCreatePlugin(AppEntry app) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(2), dp(20), 0);

        TextView target = text(app.label + "\n" + app.packageName, 12f, MUTED, false);
        target.setPadding(0, dp(4), 0, dp(8));
        form.addView(target);

        EditText name = new EditText(this);
        name.setHint("Profile name");
        form.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText description = new EditText(this);
        description.setHint("Description / notes");
        form.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("New profile")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    WorkspacePluginRegistry.add(
                            this,
                            name.getText().toString(),
                            app.packageName,
                            description.getText().toString()
                    );
                    NativePluginRuntime.reload(this);
                    refreshPlugins();
                    pager.setCurrentItem(1, true);
                    toast("Profile added");
                })
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("App Library");
        menu.getMenu().add("Apps");
        menu.getMenu().add("Plugins");
        menu.getMenu().add("Import native .so");
        menu.getMenu().add("Refresh");
        menu.getMenu().add("About");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if (title.equals("App Library")) startActivity(new Intent(this, AppLibraryActivity.class));
            else if (title.equals("Apps")) pager.setCurrentItem(0, true);
            else if (title.equals("Plugins")) pager.setCurrentItem(1, true);
            else if (title.equals("Import native .so")) openNativePluginImporter();
            else if (title.equals("Refresh")) {
                NativePluginRuntime.reload(this);
                refreshApps();
                refreshPlugins();
            } else showAbout();
            return true;
        });
        menu.show();
    }

    private void showSettings() {
        boolean online = false;
        int clones = 0;
        int plugins = WorkspacePluginRegistry.list(this).size();
        try {
            online = CarromRuntimeCore.get().areServicesAvailable();
            clones = BPackageManager.get().getInstalledPackagesAsUser(USER_ID).size();
        } catch (Throwable ignored) { }
        new AlertDialog.Builder(this)
                .setTitle("Universal Loader")
                .setMessage("Runtime: " + (online ? "online" : "starting")
                        + "\nCloned apps: " + clones
                        + "\nPlugins: " + plugins
                        + "\n\nUse Plugins → Import .so to load a native library into a cloned app process.")
                .setPositiveButton("App Library", (dialog, which) ->
                        startActivity(new Intent(this, AppLibraryActivity.class)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Universal Loader")
                .setMessage("Clone an app, open the Plugins tab, tap Import .so, select a native library and choose the target workspace. Enabled native plugins are loaded inside the virtual app process.")
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

    private final class PagesAdapter extends RecyclerView.Adapter<PageHolder> {
        @Override
        public PageHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(parent.getContext());
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return new PageHolder(container);
        }

        @Override
        public void onBindViewHolder(PageHolder holder, int position) {
            View page = position == 0 ? appsPage : pluginsPage;
            if (page.getParent() instanceof ViewGroup) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            holder.container.removeAllViews();
            holder.container.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        final FrameLayout container;

        PageHolder(FrameLayout container) {
            super(container);
            this.container = container;
        }
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

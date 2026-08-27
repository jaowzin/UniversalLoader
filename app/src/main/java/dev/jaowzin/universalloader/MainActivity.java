package dev.jaowzin.universalloader;

import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaowzin.carromloader.runtime.CarromRuntimeCore;
import dev.jaowzin.carromloader.runtime.entity.pm.InstallResult;

public final class MainActivity extends AppCompatActivity {
    private static final int USER_ID = 0;

    private static final int BG = Color.rgb(8, 12, 18);
    private static final int CARD = Color.rgb(16, 23, 32);
    private static final int CARD_ALT = Color.rgb(20, 29, 40);
    private static final int STROKE = Color.rgb(39, 53, 69);
    private static final int TEXT = Color.rgb(240, 247, 246);
    private static final int MUTED = Color.rgb(139, 157, 171);
    private static final int ACCENT = Color.rgb(110, 243, 196);
    private static final int BLUE = Color.rgb(122, 168, 255);
    private static final int WARNING = Color.rgb(255, 199, 92);
    private static final int DANGER = Color.rgb(255, 140, 151);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler statusHandler = new Handler(Looper.getMainLooper());

    private String selectedPackage = "";
    private boolean polling;

    private ImageView selectedIcon;
    private TextView selectedName;
    private TextView selectedPackageView;
    private TextView runtimeBadge;
    private TextView runtimeDetail;
    private MaterialButton primaryAction;
    private TextView pluginSummary;
    private LinearLayout pluginContainer;
    private TextView console;

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            refreshStatus();
            statusHandler.postDelayed(this, 900L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedPackage = TargetStore.getSelectedPackage(this);
        configureWindow();
        setContentView(buildContent());
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        statusHandler.removeCallbacks(statusTicker);
        statusHandler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        polling = false;
        statusHandler.removeCallbacks(statusTicker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        statusHandler.removeCallbacks(statusTicker);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BG);

        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(22), dp(20), dp(38));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(buildHeader());
        root.addView(buildSelectedAppCard());
        root.addView(sectionTitle("WORKSPACE", "Create and manage an isolated copy of the selected app."));
        root.addView(buildWorkspaceActions());
        root.addView(sectionTitle("PLUGINS", "Loader-side profiles attached to this workspace."));
        root.addView(buildPluginsCard());
        root.addView(sectionTitle("RUNTIME", "Status of the virtual-app engine."));
        root.addView(buildRuntimeCard());
        root.addView(sectionTitle("CONSOLE", "Live workspace summary."));
        root.addView(buildConsole());

        TextView footer = text("Universal Loader  •  Runtime workspace v0.1", 12f, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, dp(4));
        root.addView(footer, matchWrap());
        return scroll;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(18));

        LinearLayout copy = vertical();
        TextView eyebrow = text("VIRTUAL WORKSPACE", 11f, ACCENT, true);
        eyebrow.setLetterSpacing(0.17f);
        copy.addView(eyebrow);
        TextView title = text("Universal Loader", 30f, TEXT, true);
        title.setPadding(0, dp(2), 0, 0);
        copy.addView(title);
        TextView subtitle = text("Run isolated app instances from one clean dashboard.", 12.5f, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        copy.addView(subtitle);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView mark = text("UL", 15f, BG, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundRect(ACCENT, dp(16)));
        row.addView(mark, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private View buildSelectedAppCard() {
        MaterialCardView card = card(CARD_ALT, 24);
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        selectedIcon = new ImageView(this);
        selectedIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        selectedIcon.setBackground(roundRect(Color.rgb(29, 41, 54), dp(16)));
        row.addView(selectedIcon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(14), 0, dp(8), 0);
        selectedName = text("Choose an app", 19f, TEXT, true);
        selectedPackageView = text("No workspace selected", 12f, MUTED, false);
        selectedPackageView.setPadding(0, dp(3), 0, 0);
        copy.addView(selectedName);
        copy.addView(selectedPackageView);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        runtimeBadge = text("IDLE", 10f, MUTED, true);
        runtimeBadge.setGravity(Gravity.CENTER);
        runtimeBadge.setPadding(dp(10), dp(7), dp(10), dp(7));
        runtimeBadge.setBackground(roundRect(Color.rgb(33, 42, 53), dp(99)));
        row.addView(runtimeBadge);
        body.addView(row);

        runtimeDetail = text("Select an installed app to create a virtual workspace.", 12.5f, MUTED, false);
        runtimeDetail.setPadding(0, dp(14), 0, dp(14));
        body.addView(runtimeDetail);

        primaryAction = primaryButton("CHOOSE APP");
        primaryAction.setOnClickListener(v -> {
            if (selectedPackage.isEmpty()) chooseApp();
            else setupAndLaunch();
        });
        body.addView(primaryAction, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        card.addView(body);
        return card;
    }

    private View buildWorkspaceActions() {
        MaterialCardView card = card(CARD, 20);
        LinearLayout body = vertical();
        body.setPadding(dp(14), dp(14), dp(14), dp(14));

        MaterialButton choose = secondaryButton("Choose another app");
        choose.setOnClickListener(v -> chooseApp());
        body.addView(choose, fullButtonLp());

        MaterialButton reset = secondaryButton("Reset selected virtual app");
        reset.setTextColor(DANGER);
        reset.setOnClickListener(v -> confirmReset());
        LinearLayout.LayoutParams resetLp = fullButtonLp();
        resetLp.setMargins(0, dp(8), 0, 0);
        body.addView(reset, resetLp);

        card.addView(body);
        return card;
    }

    private View buildPluginsCard() {
        MaterialCardView card = card(CARD, 20);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = vertical();
        pluginSummary = text("No plugin profiles", 16f, TEXT, true);
        copy.addView(pluginSummary);
        TextView hint = text("Profiles are stored by target package and can carry workspace notes/settings.", 12f, MUTED, false);
        hint.setPadding(0, dp(4), dp(10), 0);
        copy.addView(hint);
        top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = text("SAFE", 10f, BLUE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(6), dp(9), dp(6));
        badge.setBackground(roundRect(Color.rgb(27, 40, 61), dp(99)));
        top.addView(badge);
        body.addView(top);

        MaterialButton add = secondaryButton("Add plugin profile");
        add.setOnClickListener(v -> addPluginProfile());
        LinearLayout.LayoutParams addLp = fullButtonLp();
        addLp.setMargins(0, dp(14), 0, 0);
        body.addView(add, addLp);

        pluginContainer = vertical();
        body.addView(pluginContainer, matchWrap());
        card.addView(body);
        return card;
    }

    private View buildRuntimeCard() {
        MaterialCardView card = card(CARD, 20);
        LinearLayout body = vertical();
        body.setPadding(dp(14), dp(14), dp(14), dp(14));

        MaterialButton refresh = secondaryButton("Refresh runtime status");
        refresh.setOnClickListener(v -> refreshAll());
        body.addView(refresh, fullButtonLp());

        TextView note = text(
                "The universal build keeps isolation and app lifecycle support, while Carrom-specific hooks and runtime anti-detection modules are excluded.",
                12f,
                MUTED,
                false
        );
        note.setPadding(dp(4), dp(12), dp(4), dp(2));
        body.addView(note);
        card.addView(body);
        return card;
    }

    private View buildConsole() {
        MaterialCardView card = card(Color.rgb(10, 15, 21), 18);
        console = text("Starting runtime…", 12f, Color.rgb(174, 197, 191), false);
        console.setTypeface(Typeface.MONOSPACE);
        console.setTextIsSelectable(true);
        console.setPadding(dp(16), dp(15), dp(16), dp(15));
        console.setMinHeight(dp(116));
        card.addView(console);
        return card;
    }

    private void chooseApp() {
        toast("Loading installed apps…");
        worker.execute(() -> {
            List<AppChoice> apps = new ArrayList<>();
            PackageManager pm = getPackageManager();
            try {
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    if (info.packageName.equals(getPackageName())) continue;
                    if (pm.getLaunchIntentForPackage(info.packageName) == null) continue;
                    CharSequence raw = pm.getApplicationLabel(info);
                    String label = raw == null ? info.packageName : raw.toString();
                    apps.add(new AppChoice(label, info.packageName));
                }
            } catch (Throwable error) {
                runOnUiThread(() -> toast("Could not read installed apps"));
                return;
            }
            apps.sort(Comparator.comparing(a -> a.label.toLowerCase()));
            runOnUiThread(() -> showAppPicker(apps));
        });
    }

    private void showAppPicker(List<AppChoice> apps) {
        if (apps.isEmpty()) {
            toast("No launchable apps found");
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppChoice app = apps.get(i);
            labels[i] = app.label + "\n" + app.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose virtual app")
                .setItems(labels, (dialog, which) -> {
                    AppChoice choice = apps.get(which);
                    selectedPackage = choice.packageName;
                    TargetStore.setSelectedPackage(this, selectedPackage);
                    refreshAll();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setupAndLaunch() {
        if (selectedPackage.isEmpty()) {
            chooseApp();
            return;
        }
        primaryAction.setEnabled(false);
        primaryAction.setText("PREPARING…");
        setConsole("Preparing virtual workspace for\n" + selectedPackage);

        worker.execute(() -> {
            try {
                CarromRuntimeCore core = CarromRuntimeCore.get();
                if (!core.isInstalled(selectedPackage, USER_ID)) {
                    InstallResult result = core.installPackageAsUser(selectedPackage, USER_ID);
                    if (!result.success) {
                        runOnUiThread(() -> {
                            primaryAction.setEnabled(true);
                            setConsole("INSTALL FAILED\n" + result.msg);
                            refreshStatus();
                        });
                        return;
                    }
                }
                boolean launched = core.launchApk(selectedPackage, USER_ID);
                runOnUiThread(() -> {
                    primaryAction.setEnabled(true);
                    refreshAll();
                    if (!launched) toast("Virtual launch returned false");
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    primaryAction.setEnabled(true);
                    setConsole("LAUNCH ERROR\n" + error);
                    refreshStatus();
                });
            }
        });
    }

    private void confirmReset() {
        if (selectedPackage.isEmpty()) {
            toast("Choose an app first");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Reset virtual app?")
                .setMessage("This removes only the isolated copy of " + selectedPackage + ". The original installation is untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> resetVirtual())
                .show();
    }

    private void resetVirtual() {
        final String target = selectedPackage;
        setConsole("Resetting virtual workspace…");
        worker.execute(() -> {
            try {
                CarromRuntimeCore.get().uninstallPackageAsUser(target, USER_ID);
                runOnUiThread(() -> {
                    setConsole("Virtual copy removed.\nOriginal app was not changed.");
                    refreshAll();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> setConsole("RESET ERROR\n" + error));
            }
        });
    }

    private void addPluginProfile() {
        if (selectedPackage.isEmpty()) {
            toast("Choose an app first");
            return;
        }
        LinearLayout form = vertical();
        int pad = dp(18);
        form.setPadding(pad, dp(4), pad, 0);
        EditText name = new EditText(this);
        name.setHint("Plugin name");
        EditText description = new EditText(this);
        description.setHint("Description or workspace note");
        form.addView(name, matchWrap());
        form.addView(description, matchWrap());

        new AlertDialog.Builder(this)
                .setTitle("New plugin profile")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    WorkspacePluginRegistry.add(
                            this,
                            name.getText().toString(),
                            selectedPackage,
                            description.getText().toString()
                    );
                    renderPlugins();
                })
                .show();
    }

    private void renderPlugins() {
        if (pluginContainer == null) return;
        pluginContainer.removeAllViews();
        List<WorkspacePluginRegistry.Plugin> all = WorkspacePluginRegistry.list(this);
        List<WorkspacePluginRegistry.Plugin> visible = new ArrayList<>();
        for (WorkspacePluginRegistry.Plugin plugin : all) {
            if (selectedPackage.isEmpty() || plugin.targetPackage.isEmpty() || plugin.targetPackage.equals(selectedPackage)) {
                visible.add(plugin);
            }
        }
        if (pluginSummary != null) {
            pluginSummary.setText(visible.isEmpty()
                    ? "No plugin profiles"
                    : visible.size() + (visible.size() == 1 ? " plugin profile" : " plugin profiles"));
        }
        for (WorkspacePluginRegistry.Plugin plugin : visible) {
            MaterialCardView item = card(Color.rgb(12, 19, 27), 16);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(10), dp(12));

            LinearLayout copy = vertical();
            TextView title = text(plugin.name, 14f, TEXT, true);
            TextView desc = text(plugin.description, 11.5f, MUTED, false);
            desc.setPadding(0, dp(3), dp(8), 0);
            copy.addView(title);
            copy.addView(desc);
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            SwitchMaterial toggle = new SwitchMaterial(this);
            toggle.setChecked(plugin.enabled);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                WorkspacePluginRegistry.setEnabled(this, plugin.id, checked);
                refreshStatus();
            });
            row.addView(toggle);

            TextView remove = text("×", 22f, DANGER, true);
            remove.setGravity(Gravity.CENTER);
            remove.setPadding(dp(9), 0, dp(4), 0);
            remove.setOnClickListener(v -> {
                WorkspacePluginRegistry.remove(this, plugin.id);
                renderPlugins();
                refreshStatus();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(38), dp(44)));
            item.addView(row);

            LinearLayout.LayoutParams lp = matchWrap();
            lp.setMargins(0, dp(10), 0, 0);
            pluginContainer.addView(item, lp);
        }
    }

    private void refreshAll() {
        refreshSelectedApp();
        renderPlugins();
        refreshStatus();
    }

    private void refreshSelectedApp() {
        if (selectedName == null) return;
        if (selectedPackage.isEmpty()) {
            selectedName.setText("Choose an app");
            selectedPackageView.setText("No workspace selected");
            selectedIcon.setImageDrawable(null);
            primaryAction.setText("CHOOSE APP");
            return;
        }
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(selectedPackage, 0);
            selectedName.setText(pm.getApplicationLabel(info));
            selectedPackageView.setText(selectedPackage);
            Drawable icon = pm.getApplicationIcon(info);
            selectedIcon.setImageDrawable(icon);
        } catch (Throwable error) {
            selectedName.setText("Unavailable app");
            selectedPackageView.setText(selectedPackage);
            selectedIcon.setImageDrawable(null);
        }
    }

    private void refreshStatus() {
        boolean services = false;
        boolean virtualInstalled = false;
        try {
            CarromRuntimeCore core = CarromRuntimeCore.get();
            services = core.areServicesAvailable();
            if (!selectedPackage.isEmpty()) {
                virtualInstalled = core.isInstalled(selectedPackage, USER_ID);
            }
        } catch (Throwable ignored) {
        }

        if (runtimeBadge != null) {
            runtimeBadge.setText(services ? "ONLINE" : "STARTING");
            runtimeBadge.setTextColor(services ? ACCENT : WARNING);
            runtimeBadge.setBackground(roundRect(
                    services ? Color.rgb(20, 50, 42) : Color.rgb(48, 40, 24),
                    dp(99)
            ));
        }

        if (runtimeDetail != null) {
            if (selectedPackage.isEmpty()) {
                runtimeDetail.setText("Select an installed app to create a virtual workspace.");
            } else {
                int profiles = WorkspacePluginRegistry.countEnabledFor(this, selectedPackage);
                runtimeDetail.setText(
                        (virtualInstalled ? "Virtual instance ready" : "Virtual instance not prepared")
                                + "  •  " + profiles + (profiles == 1 ? " profile enabled" : " profiles enabled")
                );
            }
        }

        if (primaryAction != null && primaryAction.isEnabled()) {
            if (selectedPackage.isEmpty()) primaryAction.setText("CHOOSE APP");
            else primaryAction.setText(virtualInstalled ? "OPEN VIRTUAL APP" : "SET UP & OPEN");
        }

        if (console != null) {
            StringBuilder text = new StringBuilder();
            text.append("runtime=").append(services ? "ONLINE" : "STARTING");
            text.append("\nselected=").append(selectedPackage.isEmpty() ? "none" : selectedPackage);
            text.append("\nvirtual=").append(virtualInstalled ? "READY" : "NOT_PREPARED");
            if (!selectedPackage.isEmpty()) {
                text.append("\nprofiles=").append(WorkspacePluginRegistry.countEnabledFor(this, selectedPackage));
            }
            console.setText(text.toString());
        }
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout box = vertical();
        box.setPadding(0, dp(20), 0, dp(10));
        TextView t = text(title, 11f, MUTED, true);
        t.setLetterSpacing(0.14f);
        box.addView(t);
        TextView s = text(subtitle, 12.5f, MUTED, false);
        s.setPadding(0, dp(4), 0, 0);
        box.addView(s);
        return box;
    }

    private MaterialCardView card(int color, int radiusDp) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color);
        card.setRadius(dp(radiusDp));
        card.setCardElevation(0f);
        card.setStrokeColor(STROKE);
        card.setStrokeWidth(dp(1));
        return card;
    }

    private MaterialButton primaryButton(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(BG);
        button.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        button.setCornerRadius(dp(17));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private MaterialButton secondaryButton(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setStrokeColor(ColorStateList.valueOf(STROKE));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(15));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private android.graphics.drawable.GradientDrawable roundRect(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullButtonLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
    }

    private void setConsole(String value) {
        if (console != null) console.setText(value);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AppChoice {
        final String label;
        final String packageName;

        AppChoice(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}

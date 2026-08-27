package dev.jaowzin.universalloader;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaowzin.carromloader.runtime.CarromRuntimeCore;
import dev.jaowzin.carromloader.runtime.entity.pm.InstallResult;
import dev.jaowzin.carromloader.runtime.entity.pm.InstalledPackage;
import dev.jaowzin.carromloader.runtime.fake.frameworks.BPackageManager;

public final class AppLibraryActivity extends AppCompatActivity {
    private static final int USER_ID = 0;
    private static final int BLUE = Color.rgb(82, 126, 239);
    private static final int BLUE_DARK = Color.rgb(69, 109, 218);
    private static final int BG = Color.rgb(246, 247, 249);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(48, 54, 64);
    private static final int MUTED = Color.rgb(125, 132, 143);
    private static final int STROKE = Color.rgb(225, 228, 234);
    private static final int GREEN = Color.rgb(38, 168, 112);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<AppEntry> allApps = new ArrayList<>();

    private LinearLayout appContainer;
    private EditText search;
    private TextView countView;
    private TextView emptyView;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildContent());
        loadApps();
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

        LinearLayout searchArea = new LinearLayout(this);
        searchArea.setOrientation(LinearLayout.VERTICAL);
        searchArea.setPadding(dp(16), dp(16), dp(16), dp(10));

        TextView heading = text("Installed apps", 22f, TEXT, true);
        searchArea.addView(heading);

        TextView hint = text("Choose an app to create or open its isolated copy.", 13f, MUTED, false);
        hint.setPadding(0, dp(4), 0, dp(12));
        searchArea.addView(hint);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search by app name or package…");
        search.setTextSize(14f);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(roundRect(Color.WHITE, dp(12), STROKE));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderApps(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchArea.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        countView = text("Loading apps…", 12f, MUTED, false);
        countView.setPadding(dp(2), dp(10), 0, 0);
        searchArea.addView(countView);
        root.addView(searchArea);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(2), dp(12), dp(24));

        emptyView = text("", 14f, MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(20), dp(36), dp(20), dp(36));
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView);

        appContainer = new LinearLayout(this);
        appContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(appContainer, new LinearLayout.LayoutParams(
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
        bar.setPadding(dp(4), 0, dp(12), 0);
        bar.setBackgroundColor(BLUE);
        bar.setElevation(dp(4));

        TextView back = text("‹", 38f, Color.WHITE, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(58)));

        TextView title = text("App Library", 19f, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(4), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1f));
        return bar;
    }

    private void loadApps() {
        if (loading) return;
        loading = true;
        countView.setText("Loading apps…");
        worker.execute(() -> {
            ArrayList<AppEntry> result = new ArrayList<>();
            Set<String> cloned = new HashSet<>();
            try {
                for (InstalledPackage item : BPackageManager.get().getInstalledPackagesAsUser(USER_ID)) {
                    if (item != null && item.packageName != null) cloned.add(item.packageName);
                }
            } catch (Throwable ignored) {}

            PackageManager pm = getPackageManager();
            try {
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    if (info.packageName.equals(getPackageName())) continue;
                    if (pm.getLaunchIntentForPackage(info.packageName) == null) continue;
                    CharSequence raw = pm.getApplicationLabel(info);
                    String label = raw == null ? info.packageName : raw.toString();
                    Drawable icon = null;
                    try { icon = pm.getApplicationIcon(info); } catch (Throwable ignored) {}
                    result.add(new AppEntry(label, info.packageName, icon, cloned.contains(info.packageName)));
                }
            } catch (Throwable ignored) {}
            result.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));

            runOnUiThread(() -> {
                loading = false;
                allApps.clear();
                allApps.addAll(result);
                renderApps();
            });
        });
    }

    private void renderApps() {
        if (appContainer == null) return;
        appContainer.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        int clonedCount = 0;
        for (AppEntry app : allApps) {
            if (app.cloned) clonedCount++;
            if (!query.isEmpty()
                    && !app.label.toLowerCase(Locale.ROOT).contains(query)
                    && !app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            appContainer.addView(buildAppCard(app));
            shown++;
        }
        countView.setText(allApps.size() + " apps  •  " + clonedCount + " cloned");
        emptyView.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        emptyView.setText(allApps.isEmpty() ? "No launchable apps found." : "No apps match your search.");
    }

    private View buildAppCard(AppEntry app) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(CARD);
        card.setRadius(dp(16));
        card.setCardElevation(0f);
        card.setStrokeColor(STROKE);
        card.setStrokeWidth(dp(1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app.icon != null) icon.setImageDrawable(app.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(app.label, 16f, TEXT, true);
        name.setSingleLine(true);
        copy.addView(name);

        TextView packageView = text(app.packageName, 11.5f, MUTED, false);
        packageView.setSingleLine(true);
        packageView.setPadding(0, dp(3), 0, 0);
        copy.addView(packageView);

        TextView state = text(app.cloned ? "● Cloned" : "Available", 11.5f,
                app.cloned ? GREEN : MUTED, app.cloned);
        state.setPadding(0, dp(4), 0, 0);
        copy.addView(state);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton action = new MaterialButton(this);
        action.setAllCaps(false);
        action.setText(app.cloned ? "Open" : "Add");
        action.setTextSize(13f);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setCornerRadius(dp(12));
        action.setInsetTop(0);
        action.setInsetBottom(0);
        if (app.cloned) {
            action.setTextColor(BLUE);
            action.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(237, 242, 255)));
            action.setOnClickListener(v -> openVirtual(app));
        } else {
            action.setTextColor(Color.WHITE);
            action.setBackgroundTintList(ColorStateList.valueOf(BLUE));
            action.setOnClickListener(v -> cloneApp(app));
        }
        row.addView(action, new LinearLayout.LayoutParams(dp(82), dp(42)));

        card.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(lp);
        card.setOnClickListener(v -> {
            if (app.cloned) openVirtual(app);
            else cloneApp(app);
        });
        return card;
    }

    private void cloneApp(AppEntry app) {
        if (loading) return;
        loading = true;
        Toast.makeText(this, "Creating " + app.label + "…", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            try {
                InstallResult result = CarromRuntimeCore.get().installPackageAsUser(app.packageName, USER_ID);
                runOnUiThread(() -> {
                    loading = false;
                    if (result.success) {
                        app.cloned = true;
                        Toast.makeText(this, app.label + " cloned", Toast.LENGTH_SHORT).show();
                        renderApps();
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

    private void openVirtual(AppEntry app) {
        TargetStore.setSelectedPackage(this, app.packageName);
        worker.execute(() -> {
            try {
                boolean ok = CarromRuntimeCore.get().launchApk(app.packageName, USER_ID);
                if (!ok) runOnUiThread(() -> showError("Launch failed", "Virtual launch returned false."));
            } catch (Throwable error) {
                runOnUiThread(() -> showError("Launch failed", String.valueOf(error)));
            }
        });
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

    private GradientDrawable roundRect(int color, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;
        boolean cloned;

        AppEntry(String label, String packageName, Drawable icon, boolean cloned) {
            this.label = label == null || label.trim().isEmpty() ? packageName : label;
            this.packageName = packageName;
            this.icon = icon;
            this.cloned = cloned;
        }
    }
}

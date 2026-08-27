package dev.jaowzin.universalloader;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
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

import dev.jaowzin.carromloader.runtime.entity.pm.InstalledPackage;
import dev.jaowzin.carromloader.runtime.fake.frameworks.BPackageManager;

/**
 * Explicit user-driven importer for native plugins.
 *
 * It accepts a .so shared from a file manager or opens Android's document picker, asks which
 * virtual workspace should receive the plugin, and copies the ELF into UniversalLoader private
 * storage through NativePluginRuntime. No library is executed in this Activity.
 */
public final class NativePluginImportActivity extends AppCompatActivity {
    private static final int REQUEST_LIBRARY = 4101;
    private static final int USER_ID = 0;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri selectedLibrary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri incoming = extractIncomingUri(getIntent());
        if (incoming != null) {
            selectedLibrary = incoming;
            chooseTarget();
        } else {
            openLibraryPicker();
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private Uri extractIncomingUri(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            if (Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            }
            Object value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return value instanceof Uri ? (Uri) value : null;
        }
        return null;
    }

    private void openLibraryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        try {
            startActivityForResult(intent, REQUEST_LIBRARY);
        } catch (Throwable error) {
            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            startActivityForResult(fallback, REQUEST_LIBRARY);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LIBRARY) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }

        selectedLibrary = data.getData();
        try {
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(selectedLibrary, flags);
        } catch (Throwable ignored) { }
        chooseTarget();
    }

    private void chooseTarget() {
        worker.execute(() -> {
            List<Target> targets = loadTargets();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (targets.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("No cloned apps")
                            .setMessage("Clone an app in Universal Loader before importing a native plugin.")
                            .setPositiveButton("Close", (dialog, which) -> finish())
                            .setOnCancelListener(dialog -> finish())
                            .show();
                    return;
                }

                String[] labels = new String[targets.size()];
                for (int i = 0; i < targets.size(); i++) {
                    Target target = targets.get(i);
                    labels[i] = target.label + "\n" + target.packageName;
                }

                new AlertDialog.Builder(this)
                        .setTitle("Native plugin target")
                        .setItems(labels, (dialog, which) -> configurePlugin(targets.get(which)))
                        .setNegativeButton("Cancel", (dialog, which) -> finish())
                        .setOnCancelListener(dialog -> finish())
                        .show();
            });
        });
    }

    private List<Target> loadTargets() {
        ArrayList<Target> targets = new ArrayList<>();
        PackageManager pm = getPackageManager();
        try {
            for (InstalledPackage item : BPackageManager.get().getInstalledPackagesAsUser(USER_ID)) {
                if (item == null || item.packageName == null || item.packageName.equals(getPackageName())) {
                    continue;
                }
                String label = item.packageName;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(item.packageName, 0);
                    CharSequence value = pm.getApplicationLabel(info);
                    if (value != null && value.length() > 0) label = value.toString();
                } catch (Throwable ignored) { }
                targets.add(new Target(label, item.packageName));
            }
        } catch (Throwable ignored) { }
        targets.sort(Comparator.comparing(target -> target.label.toLowerCase(Locale.ROOT)));
        return targets;
    }

    private void configurePlugin(Target target) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        form.setPadding(padding, dp(4), padding, 0);

        TextView targetView = new TextView(this);
        targetView.setText(target.label + "\n" + target.packageName);
        targetView.setPadding(0, 0, 0, dp(8));
        form.addView(targetView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText name = new EditText(this);
        name.setHint("Plugin name");
        name.setSingleLine(true);
        name.setText(defaultPluginName(selectedLibrary));
        form.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText process = new EditText(this);
        process.setHint("Process name (blank = all app processes)");
        process.setSingleLine(true);
        form.addView(process, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText description = new EditText(this);
        description.setHint("Description / notes");
        form.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Import native library")
                .setMessage("The ELF will be copied into Loader-private storage and loaded before the target Application.onCreate().")
                .setView(form)
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .setPositiveButton("Import", (dialog, which) -> importPlugin(
                        target,
                        name.getText().toString(),
                        process.getText().toString(),
                        description.getText().toString()))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void importPlugin(Target target, String name, String process, String description) {
        Uri source = selectedLibrary;
        if (source == null) {
            Toast.makeText(this, "No library selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        worker.execute(() -> {
            try {
                WorkspacePluginRegistry.Plugin plugin = NativePluginRuntime.importLibrary(
                        this,
                        source,
                        name,
                        target.packageName,
                        process,
                        description,
                        WorkspacePluginRegistry.PHASE_BEFORE_ONCREATE
                );
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            "Native plugin imported: " + plugin.name,
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Import failed")
                        .setMessage(error.getMessage() == null ? String.valueOf(error) : error.getMessage())
                        .setPositiveButton("Close", (dialog, which) -> finish())
                        .show());
            }
        });
    }

    private String defaultPluginName(Uri uri) {
        String display = null;
        if (uri != null) {
            try (Cursor cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) display = cursor.getString(index);
                }
            } catch (Throwable ignored) { }
            if ((display == null || display.trim().isEmpty()) && uri.getLastPathSegment() != null) {
                display = uri.getLastPathSegment();
            }
        }
        if (display == null || display.trim().isEmpty()) return "Native plugin";
        display = display.trim();
        if (display.toLowerCase(Locale.ROOT).endsWith(".so")) {
            display = display.substring(0, display.length() - 3);
        }
        return display.isEmpty() ? "Native plugin" : display;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Target {
        final String label;
        final String packageName;

        Target(String label, String packageName) {
            this.label = label == null || label.trim().isEmpty() ? packageName : label;
            this.packageName = packageName;
        }
    }
}

package dev.jaowzin.universalloader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class WorkspacePluginRegistry {
    static final String KIND_PROFILE = "profile";
    static final String KIND_NATIVE_LIBRARY = "native_library";
    static final String PHASE_BEFORE_ONCREATE = "before_oncreate";
    static final String PHASE_AFTER_ONCREATE = "after_oncreate";

    static final class Plugin {
        final String id;
        final String name;
        final String targetPackage;
        final String description;
        final boolean enabled;
        final String kind;
        final String libraryFile;
        final String processName;
        final String loadPhase;

        Plugin(String id,
               String name,
               String targetPackage,
               String description,
               boolean enabled,
               String kind,
               String libraryFile,
               String processName,
               String loadPhase) {
            this.id = id;
            this.name = name;
            this.targetPackage = targetPackage;
            this.description = description;
            this.enabled = enabled;
            this.kind = clean(kind, KIND_PROFILE);
            this.libraryFile = clean(libraryFile, "");
            this.processName = clean(processName, "");
            this.loadPhase = clean(loadPhase, PHASE_BEFORE_ONCREATE);
        }

        boolean isNativeLibrary() {
            return KIND_NATIVE_LIBRARY.equals(kind) && !libraryFile.isEmpty();
        }

        boolean matches(String packageName, String processName, String phase) {
            if (!enabled) return false;
            if (!targetPackage.isEmpty() && !targetPackage.equals(packageName)) return false;
            if (!this.processName.isEmpty() && !this.processName.equals(processName)) return false;
            return loadPhase.equals(phase);
        }
    }

    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "workspace_plugins.json";

    private WorkspacePluginRegistry() {}

    static List<Plugin> list(Context context) {
        synchronized (LOCK) {
            return read(context);
        }
    }

    static Plugin add(Context context, String name, String targetPackage, String description) {
        synchronized (LOCK) {
            List<Plugin> plugins = read(context);
            Plugin plugin = new Plugin(
                    UUID.randomUUID().toString(),
                    clean(name, "Plugin profile"),
                    clean(targetPackage, ""),
                    clean(description, "Loader-side workspace extension"),
                    true,
                    KIND_PROFILE,
                    "",
                    "",
                    PHASE_BEFORE_ONCREATE
            );
            plugins.add(plugin);
            write(context, plugins);
            return plugin;
        }
    }

    static Plugin addNativeLibrary(Context context,
                                   String id,
                                   String name,
                                   String targetPackage,
                                   String description,
                                   String libraryFile,
                                   String processName,
                                   String loadPhase) {
        synchronized (LOCK) {
            List<Plugin> plugins = read(context);
            Plugin plugin = new Plugin(
                    clean(id, UUID.randomUUID().toString()),
                    clean(name, "Native library"),
                    clean(targetPackage, ""),
                    clean(description, "Native library loaded inside the virtual app process"),
                    true,
                    KIND_NATIVE_LIBRARY,
                    clean(libraryFile, ""),
                    clean(processName, ""),
                    clean(loadPhase, PHASE_BEFORE_ONCREATE)
            );
            plugins.add(plugin);
            write(context, plugins);
            return plugin;
        }
    }

    static void setEnabled(Context context, String id, boolean enabled) {
        synchronized (LOCK) {
            List<Plugin> source = read(context);
            List<Plugin> result = new ArrayList<>();
            for (Plugin plugin : source) {
                result.add(plugin.id.equals(id)
                        ? new Plugin(
                                plugin.id,
                                plugin.name,
                                plugin.targetPackage,
                                plugin.description,
                                enabled,
                                plugin.kind,
                                plugin.libraryFile,
                                plugin.processName,
                                plugin.loadPhase)
                        : plugin);
            }
            write(context, result);
        }
    }

    static void remove(Context context, String id) {
        synchronized (LOCK) {
            List<Plugin> source = read(context);
            List<Plugin> result = new ArrayList<>();
            for (Plugin plugin : source) {
                if (!plugin.id.equals(id)) result.add(plugin);
            }
            write(context, result);
        }
    }

    static int countEnabledFor(Context context, String packageName) {
        int count = 0;
        for (Plugin plugin : list(context)) {
            if (plugin.enabled && (plugin.targetPackage.isEmpty() || plugin.targetPackage.equals(packageName))) {
                count++;
            }
        }
        return count;
    }

    private static List<Plugin> read(Context context) {
        ArrayList<Plugin> result = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return result;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = input.readAllBytes();
            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                result.add(new Plugin(
                        item.optString("id", UUID.randomUUID().toString()),
                        item.optString("name", "Plugin profile"),
                        item.optString("targetPackage", ""),
                        item.optString("description", ""),
                        item.optBoolean("enabled", true),
                        item.optString("kind", KIND_PROFILE),
                        item.optString("libraryFile", ""),
                        item.optString("processName", ""),
                        item.optString("loadPhase", PHASE_BEFORE_ONCREATE)
                ));
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static void write(Context context, List<Plugin> plugins) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        JSONArray array = new JSONArray();
        try {
            for (Plugin plugin : plugins) {
                JSONObject item = new JSONObject();
                item.put("id", plugin.id);
                item.put("name", plugin.name);
                item.put("targetPackage", plugin.targetPackage);
                item.put("description", plugin.description);
                item.put("enabled", plugin.enabled);
                item.put("kind", plugin.kind);
                item.put("libraryFile", plugin.libraryFile);
                item.put("processName", plugin.processName);
                item.put("loadPhase", plugin.loadPhase);
                array.put(item);
            }
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}

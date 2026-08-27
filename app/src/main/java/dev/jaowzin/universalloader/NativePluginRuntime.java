package dev.jaowzin.universalloader;

import android.content.Context;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loader-side native plugin bridge.
 *
 * The host Application initializes this class before the virtual I/O layer is enabled. The
 * resulting plugin snapshot and host storage paths are therefore stable when a guest process is
 * later bound. A native plugin is loaded from the host's private files directory with System.load,
 * so its JNI_OnLoad runs in the same Linux process as the virtualized target application.
 */
final class NativePluginRuntime {
    private static final String TAG = "ULNativePlugin";
    private static final long MAX_LIBRARY_BYTES = 128L * 1024L * 1024L;
    private static final String INTERNAL_DIR = "native_plugins";

    private static final Set<String> LOADED_PATHS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static volatile File hostFilesDir;
    private static volatile File externalPluginDir;
    private static volatile List<WorkspacePluginRegistry.Plugin> snapshot = Collections.emptyList();

    private NativePluginRuntime() {}

    static void initialize(Context hostContext) {
        Context app = hostContext.getApplicationContext();
        if (app == null) app = hostContext;
        hostFilesDir = app.getFilesDir();
        externalPluginDir = app.getExternalFilesDir("plugins");
        reload(app);
        Log.i(TAG, "initialized files=" + hostFilesDir
                + " external=" + externalPluginDir
                + " plugins=" + snapshot.size());
    }

    static void reload(Context hostContext) {
        try {
            snapshot = Collections.unmodifiableList(
                    new ArrayList<>(WorkspacePluginRegistry.list(hostContext)));
        } catch (Throwable error) {
            Log.e(TAG, "Could not refresh plugin registry", error);
            snapshot = Collections.emptyList();
        }
    }

    static void beforeApplicationOnCreate(String packageName, String processName) {
        loadForPhase(packageName, processName, WorkspacePluginRegistry.PHASE_BEFORE_ONCREATE);
    }

    static void afterApplicationOnCreate(String packageName, String processName) {
        loadForPhase(packageName, processName, WorkspacePluginRegistry.PHASE_AFTER_ONCREATE);
    }

    /**
     * Imports a library selected through Android's Storage Access Framework into private host
     * storage and creates an executable plugin record. Kept here so the UI only needs to provide
     * the Uri and target metadata.
     */
    static WorkspacePluginRegistry.Plugin importLibrary(Context hostContext,
                                                        Uri source,
                                                        String name,
                                                        String targetPackage,
                                                        String processName,
                                                        String description,
                                                        String loadPhase) throws IOException {
        if (source == null) throw new IOException("No library selected");
        ensureInitialized(hostContext);

        String id = UUID.randomUUID().toString();
        File directory = new File(requireHostFilesDir(), INTERNAL_DIR + File.separator + id);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create plugin directory");
        }

        File temporary = new File(directory, "plugin.so.part");
        File destination = new File(directory, "plugin.so");
        try (InputStream input = hostContext.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("Could not open selected file");
            copyWithLimit(input, temporary);
        } catch (Throwable error) {
            deleteQuietly(temporary);
            deleteTreeQuietly(directory);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not copy native library", error);
        }

        try {
            validateElf(temporary);
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Could not replace native library");
            }
            if (!temporary.renameTo(destination)) {
                copyFile(temporary, destination);
                deleteQuietly(temporary);
            }

            String relative = INTERNAL_DIR + "/" + id + "/plugin.so";
            WorkspacePluginRegistry.Plugin plugin = WorkspacePluginRegistry.addNativeLibrary(
                    hostContext,
                    id,
                    name,
                    targetPackage,
                    description,
                    relative,
                    processName,
                    loadPhase
            );
            reload(hostContext);
            return plugin;
        } catch (Throwable error) {
            deleteTreeQuietly(directory);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Invalid native library", error);
        }
    }

    static void removePluginFiles(Context hostContext, WorkspacePluginRegistry.Plugin plugin) {
        if (plugin == null || !plugin.isNativeLibrary()) return;
        ensureInitialized(hostContext);
        File file = resolvePrivateLibrary(plugin.libraryFile);
        if (file != null) deleteTreeQuietly(file.getParentFile());
    }

    static File getExternalPluginDirectory(Context hostContext) {
        ensureInitialized(hostContext);
        return externalPluginDir;
    }

    private static void loadForPhase(String packageName, String processName, String phase) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        String actualProcess = processName == null ? "" : processName;

        for (WorkspacePluginRegistry.Plugin plugin : snapshot) {
            try {
                if (plugin.isNativeLibrary()) {
                    if (plugin.matches(packageName, actualProcess, phase)) {
                        File library = resolvePrivateLibrary(plugin.libraryFile);
                        loadOne(plugin, library, packageName, actualProcess);
                    }
                    continue;
                }

                // Backward-compatible bridge for the plugin profiles that already exist in the UI.
                // A profile named "hook" targeting com.example.app can be paired with:
                // Android/data/<host>/files/plugins/com.example.app/hook.so
                // The file is copied to private storage before loading because shared storage is
                // normally mounted noexec.
                if (WorkspacePluginRegistry.KIND_PROFILE.equals(plugin.kind)
                        && WorkspacePluginRegistry.PHASE_BEFORE_ONCREATE.equals(phase)
                        && plugin.enabled
                        && !plugin.targetPackage.isEmpty()
                        && plugin.targetPackage.equals(packageName)) {
                    File staged = findExternalProfileLibrary(plugin);
                    if (staged != null && staged.isFile()) {
                        File privateCopy = prepareExternalLibrary(plugin, staged);
                        loadOne(plugin, privateCopy, packageName, actualProcess);
                    }
                }
            } catch (Throwable error) {
                Log.e(TAG, "Plugin load failed id=" + plugin.id
                        + " package=" + packageName
                        + " process=" + actualProcess, error);
            }
        }
    }

    private static void loadOne(WorkspacePluginRegistry.Plugin plugin,
                                File library,
                                String packageName,
                                String processName) throws IOException {
        if (library == null || !library.isFile()) {
            Log.w(TAG, "Library missing for plugin " + plugin.name + ": " + library);
            return;
        }

        validateElf(library);
        String absolute = library.getAbsolutePath();
        if (!LOADED_PATHS.add(absolute)) {
            Log.d(TAG, "already loaded " + absolute);
            return;
        }

        try {
            System.load(absolute);
            Log.i(TAG, "loaded plugin=" + plugin.name
                    + " package=" + packageName
                    + " process=" + processName
                    + " path=" + absolute);
        } catch (Throwable error) {
            LOADED_PATHS.remove(absolute);
            if (error instanceof UnsatisfiedLinkError) throw (UnsatisfiedLinkError) error;
            throw error;
        }
    }

    private static File findExternalProfileLibrary(WorkspacePluginRegistry.Plugin plugin) {
        File root = externalPluginDir;
        if (root == null) return null;
        File packageDir = new File(root, safeSegment(plugin.targetPackage));
        return new File(packageDir, safeFileStem(plugin.name) + ".so");
    }

    private static File prepareExternalLibrary(WorkspacePluginRegistry.Plugin plugin,
                                               File staged) throws IOException {
        validateElf(staged);
        File root = requireHostFilesDir();
        File directory = new File(root, INTERNAL_DIR + File.separator + "staged"
                + File.separator + safeSegment(plugin.id));
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create staged plugin directory");
        }

        File destination = new File(directory, "plugin.so");
        if (!destination.isFile()
                || destination.length() != staged.length()
                || destination.lastModified() < staged.lastModified()) {
            File temporary = new File(directory, "plugin.so.part");
            copyFile(staged, temporary);
            validateElf(temporary);
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Could not update staged native library");
            }
            if (!temporary.renameTo(destination)) {
                copyFile(temporary, destination);
                deleteQuietly(temporary);
            }
            destination.setLastModified(staged.lastModified());
        }
        return destination;
    }

    private static File resolvePrivateLibrary(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) return null;
        File root = hostFilesDir;
        if (root == null) return null;
        try {
            File candidate = new File(root, relativePath).getCanonicalFile();
            File canonicalRoot = root.getCanonicalFile();
            String rootPath = canonicalRoot.getPath() + File.separator;
            if (!candidate.getPath().startsWith(rootPath)) {
                Log.e(TAG, "Rejected plugin path outside host files: " + relativePath);
                return null;
            }
            return candidate;
        } catch (IOException error) {
            Log.e(TAG, "Could not resolve plugin path " + relativePath, error);
            return null;
        }
    }

    private static void validateElf(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Library file does not exist");
        if (file.length() < 20) throw new IOException("File is too small to be an ELF library");
        if (file.length() > MAX_LIBRARY_BYTES) throw new IOException("Native library exceeds 128 MiB");

        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset < header.length) throw new IOException("Incomplete ELF header");
        }

        if ((header[0] & 0xff) != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
            throw new IOException("Selected file is not an ELF shared library");
        }

        int elfClass = header[4] & 0xff;
        boolean process64 = Process.is64Bit();
        if ((process64 && elfClass != 2) || (!process64 && elfClass != 1)) {
            throw new IOException("Library bitness does not match the virtual process");
        }

        boolean littleEndian = (header[5] & 0xff) != 2;
        int machine = littleEndian
                ? ((header[18] & 0xff) | ((header[19] & 0xff) << 8))
                : (((header[18] & 0xff) << 8) | (header[19] & 0xff));
        if (!machineMatchesRuntime(machine)) {
            throw new IOException("Library architecture does not match "
                    + System.getProperty("os.arch", "unknown") + " (ELF machine " + machine + ")");
        }
    }

    private static boolean machineMatchesRuntime(int machine) {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        switch (machine) {
            case 183: // EM_AARCH64
                return arch.contains("aarch64") || arch.contains("arm64");
            case 40: // EM_ARM
                return arch.startsWith("arm") && !arch.contains("64");
            case 62: // EM_X86_64
                return arch.contains("x86_64") || arch.contains("amd64");
            case 3: // EM_386
                return arch.equals("x86") || arch.contains("i386") || arch.contains("i686");
            case 243: // EM_RISCV
                return arch.contains("riscv");
            default:
                // Let Android's linker provide the final diagnostic for uncommon architectures.
                return true;
        }
    }

    private static void ensureInitialized(Context context) {
        if (hostFilesDir == null) initialize(context);
    }

    private static File requireHostFilesDir() throws IOException {
        File root = hostFilesDir;
        if (root == null) throw new IOException("Native plugin runtime is not initialized");
        return root;
    }

    private static void copyWithLimit(InputStream input, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create plugin directory");
        }
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_LIBRARY_BYTES) {
                    throw new IOException("Native library exceeds 128 MiB");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            copyWithLimit(input, destination);
        }
    }

    private static String safeSegment(String value) {
        if (value == null || value.isEmpty()) return "_";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safeFileStem(String value) {
        String stem = safeSegment(value == null ? "plugin" : value.trim());
        if (stem.isEmpty() || stem.equals(".") || stem.equals("..")) return "plugin";
        return stem;
    }

    private static void deleteQuietly(File file) {
        try {
            if (file != null) file.delete();
        } catch (Throwable ignored) { }
    }

    private static void deleteTreeQuietly(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) deleteTreeQuietly(child);
                }
            }
            file.delete();
        } catch (Throwable ignored) { }
    }
}

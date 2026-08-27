# UniversalLoader

UniversalLoader is an Android virtual-app workspace built on the isolated runtime already proven in the Loader project.

## Goals

- Select an installed Android app and create an isolated virtual instance.
- Launch and reset virtual instances without modifying the original installation.
- Modern dark dashboard with per-app runtime state.
- App profiles and Loader-side plugin metadata.
- Native plugin loading inside Loader-managed virtual app processes.
- A developer extension point for controlled/debug targets.

The universal build intentionally removes the Carrom-specific aim module, root-hiding code, device-property spoofing, and anti-detection hooks from the source runtime.

## Native plugins

UniversalLoader can register an ELF shared library as a native plugin for one cloned package. The plugin can target every process of that package or one exact process name. Native plugins are copied into Loader-private storage and loaded in the virtual app process immediately before `Application.onCreate()` by default. A normal JNI `JNI_OnLoad` therefore runs inside the target virtual process.

The importer validates ELF magic, process bitness, architecture, file size, and the final private-storage path before calling `System.load()`.

### Importing a library

Share or open an `application/octet-stream` `.so` with **Universal Loader**. The native-plugin importer will:

1. Ask which cloned app is the target.
2. Ask for a plugin name and an optional exact process name. Leave the process blank to load in all processes belonging to that app.
3. Copy and validate the library.
4. Register the plugin. The plugin is loaded on the next virtual-process start.

Existing metadata-only plugin profiles remain compatible. For development, a profile named `hook` targeting `com.example.app` can also discover a staged file at the app-specific external files path `plugins/com.example.app/hook.so`; UniversalLoader copies that file to private storage before loading it.

Native libraries are intentionally scoped to UniversalLoader-managed virtual processes. This project does not add stealth, anti-detection, or external arbitrary-process injection.

## Runtime attribution

The virtual runtime contains vendored Apache-2.0 licensed components. Required legal notices are preserved under `licenses/runtime-engine/`.

## Build

GitHub Actions bootstraps the vendored runtime on the first build and then builds `app-debug.apk` with Android SDK 35 / NDK 29. Pull requests are compiled before merge.

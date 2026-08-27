# UniversalLoader

UniversalLoader is an Android virtual-app workspace built on the isolated runtime already proven in the Loader project.

## Goals

- Select an installed Android app and create an isolated virtual instance.
- Launch and reset virtual instances without modifying the original installation.
- Modern dark dashboard with per-app runtime state.
- App profiles and Loader-side plugin metadata.
- A developer extension point for a bundled/debug test target.

The universal build intentionally removes the Carrom-specific aim module, root-hiding code, device-property spoofing, and anti-detection hooks from the source runtime.

## Runtime attribution

The virtual runtime contains vendored Apache-2.0 licensed components. Required legal notices are preserved under `licenses/runtime-engine/`.

## Build

GitHub Actions bootstraps the vendored runtime on the first build and then builds `app-debug.apk` with Android SDK 35 / NDK 29.

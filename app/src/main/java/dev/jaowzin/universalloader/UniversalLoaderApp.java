package dev.jaowzin.universalloader;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;

import dev.jaowzin.carromloader.runtime.CarromRuntimeCore;
import dev.jaowzin.carromloader.runtime.app.configuration.AppLifecycleCallback;
import dev.jaowzin.carromloader.runtime.app.configuration.ClientConfiguration;

public final class UniversalLoaderApp extends Application {
    private static final String TAG = "UniversalLoader";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        // Snapshot Loader-owned plugin metadata and storage paths before the virtual I/O layer is
        // attached. Every virtual process runs this host Application before binding its guest app.
        try {
            NativePluginRuntime.initialize(base);
        } catch (Throwable error) {
            Log.e(TAG, "native plugin runtime init failed", error);
        }

        CarromRuntimeCore core = CarromRuntimeCore.get();

        try {
            core.closeCodeInit();
        } catch (Throwable error) {
            Log.w(TAG, "closeCodeInit failed", error);
        }

        try {
            core.onBeforeMainApplicationAttach(this, base);
        } catch (Throwable error) {
            Log.w(TAG, "pre-attach failed", error);
        }

        core.doAttachBaseContext(base, new ClientConfiguration() {
            @Override
            public String getHostPackageName() {
                return base.getPackageName();
            }

            @Override
            public boolean isHideRoot() {
                return false;
            }

            @Override
            public boolean isEnableDaemonService() {
                return false;
            }

            @Override
            public boolean isEnableLauncherActivity() {
                return true;
            }

            @Override
            public boolean isUseVpnNetwork() {
                return false;
            }

            @Override
            public boolean isDisableFlagSecure() {
                return false;
            }

            @Override
            public boolean requestInstallPackage(File file, int userId) {
                return false;
            }

            @Override
            public String getLogSenderChatId() {
                return "";
            }
        });

        try {
            core.onAfterMainApplicationAttach(this, base);
        } catch (Throwable error) {
            Log.w(TAG, "post-attach failed", error);
        }

        core.addAppLifecycleCallback(new AppLifecycleCallback() {
            @Override
            public void beforeCreateApplication(String packageName, String processName, Context context, int userId) {
                Log.d(TAG, "virtual beforeCreate " + packageName + " / " + processName);
            }

            @Override
            public void beforeApplicationOnCreate(String packageName, String processName, Application application, int userId) {
                Log.d(TAG, "virtual beforeOnCreate " + packageName + " / " + processName);
                FloatingMenuHost.prepare(application, packageName, processName);
                CarromCtfHost.prepare(application, packageName, processName);
                try {
                    NativePluginRuntime.beforeApplicationOnCreate(packageName, processName);
                } catch (Throwable error) {
                    Log.e(TAG, "native plugins failed before onCreate for "
                            + packageName + " / " + processName, error);
                } finally {
                    FloatingMenuHost.clearPending();
                    CarromCtfHost.clearPending();
                }
            }

            @Override
            public void afterApplicationOnCreate(String packageName, String processName, Application application, int userId) {
                FloatingMenuHost.prepare(application, packageName, processName);
                CarromCtfHost.prepare(application, packageName, processName);
                try {
                    NativePluginRuntime.afterApplicationOnCreate(packageName, processName);
                } catch (Throwable error) {
                    Log.e(TAG, "native plugins failed after onCreate for "
                            + packageName + " / " + processName, error);
                } finally {
                    FloatingMenuHost.clearPending();
                    CarromCtfHost.clearPending();
                }
                int profileCount = WorkspacePluginRegistry.countEnabledFor(UniversalLoaderApp.this, packageName);
                Log.d(TAG, "virtual ready " + packageName + " / " + processName + " profiles=" + profileCount);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LoaderDarkTheme.register(this);
        try {
            CarromRuntimeCore.get().doCreate();
        } catch (Throwable error) {
            Log.e(TAG, "runtime doCreate failed", error);
        }
    }
}

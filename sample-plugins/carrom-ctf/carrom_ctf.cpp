#include <jni.h>
#include <android/log.h>
#include <link.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <ctime>

#include "Dobby/dobby.h"

namespace {
constexpr const char *kTag = "ULCarromCTF";

// Known Carrom 19.3.0 / module 1473 aim-input RVAs. These are used only to obtain
// the game's live aim axis and normalized power. Screen-space geometry is detected
// from the real rendered board by CarromCtfHost/CarromCtfAutoPilot.
constexpr uintptr_t kGetAngleRva = 0x01A854A0;
constexpr uintptr_t kGetAngleSelectorRva = 0x012A01C0;
constexpr uintptr_t kTouchesMovedRva = 0x018ECB5C;
constexpr uintptr_t kLocalGetPowerRva = 0x018EC8D0;
constexpr uintptr_t kLocalGetAngleRva = 0x018EC994;
constexpr uintptr_t kTouchesMovedSelectorRva = 0x011FF2E3;
constexpr uintptr_t kGetPowerSelectorRva = 0x0105DE84;
constexpr uintptr_t kLocalGetAngleSelectorRva = 0x0109018B;

using GetAngleFn = double (*)(void *, void *, int);
using LocalDoubleGetterFn = double (*)(void *, void *);
using TouchesMovedFn = void (*)(void *, void *, void *, unsigned int);

GetAngleFn gOriginalGetAngle = nullptr;
TouchesMovedFn gOriginalTouchesMoved = nullptr;
LocalDoubleGetterFn gLocalGetPower = nullptr;
LocalDoubleGetterFn gLocalGetAngle = nullptr;
void *gGetPowerSelector = nullptr;
void *gLocalGetAngleSelector = nullptr;

std::atomic<int> gStatus{0};
std::atomic<double> gAngle{NAN};
std::atomic<double> gPower{NAN};
std::atomic<int64_t> gAngleNs{0};
std::atomic<int64_t> gPowerNs{0};
std::atomic<uintptr_t> gModuleBase{0};
std::atomic<bool> gInstalling{false};

int64_t monotonicNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void captureAngle(double angle) {
    if (!std::isfinite(angle)) return;
    gAngle.store(angle, std::memory_order_relaxed);
    gAngleNs.store(monotonicNs(), std::memory_order_release);
}

void capturePower(double power) {
    if (!std::isfinite(power)) return;
    if (power < 0.0) power = 0.0;
    if (power > 1.0) power = 1.0;
    gPower.store(power, std::memory_order_relaxed);
    gPowerNs.store(monotonicNs(), std::memory_order_release);
}

bool isCarromImage(const char *name) {
    return name && *name &&
           (std::strstr(name, "libgame-CARROM") || std::strstr(name, "libcarrom.so"));
}

struct FindContext { uintptr_t base = 0; };

int findModuleCallback(dl_phdr_info *info, size_t, void *data) {
    if (!info || !data || !isCarromImage(info->dlpi_name)) return 0;
    static_cast<FindContext *>(data)->base = static_cast<uintptr_t>(info->dlpi_addr);
    return 1;
}

uintptr_t findModuleBase() {
    FindContext context;
    dl_iterate_phdr(findModuleCallback, &context);
    return context.base;
}

bool selectorMatches(uintptr_t base, uintptr_t rva, const char *expected) {
    if (!base || !expected) return false;
    const char *value = reinterpret_cast<const char *>(base + rva);
    return value && std::strcmp(value, expected) == 0;
}

double hookGetAngle(void *self, void *selector, int playerId) {
    double value = gOriginalGetAngle ? gOriginalGetAngle(self, selector, playerId) : NAN;
    captureAngle(value);
    return value;
}

void hookTouchesMoved(void *self, void *selector, void *touches, unsigned int currentNumTouches) {
    if (gOriginalTouchesMoved) {
        gOriginalTouchesMoved(self, selector, touches, currentNumTouches);
    }
    if (!self) return;
    if (gLocalGetAngle) captureAngle(gLocalGetAngle(self, gLocalGetAngleSelector));
    if (gLocalGetPower) capturePower(gLocalGetPower(self, gGetPowerSelector));
}

int installHooks() {
#if !defined(__aarch64__)
    gStatus.store(-1, std::memory_order_release);
    return -1;
#else
    if (gStatus.load(std::memory_order_acquire) == 2) return 2;

    bool expected = false;
    if (!gInstalling.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        return gStatus.load(std::memory_order_acquire);
    }

    uintptr_t base = findModuleBase();
    if (!base) {
        gStatus.store(1, std::memory_order_release);
        gInstalling.store(false, std::memory_order_release);
        return 1;
    }
    gModuleBase.store(base, std::memory_order_release);

    if (!selectorMatches(base, kGetAngleSelectorRva, "controlsGetAngle:") ||
        !selectorMatches(base, kTouchesMovedSelectorRva, "touchesMoved:currentNumTouches:") ||
        !selectorMatches(base, kGetPowerSelectorRva, "getPower") ||
        !selectorMatches(base, kLocalGetAngleSelectorRva, "getAngle")) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "build signature mismatch base=%p",
                            reinterpret_cast<void *>(base));
        gStatus.store(-2, std::memory_order_release);
        gInstalling.store(false, std::memory_order_release);
        return -2;
    }

    gLocalGetPower = reinterpret_cast<LocalDoubleGetterFn>(base + kLocalGetPowerRva);
    gLocalGetAngle = reinterpret_cast<LocalDoubleGetterFn>(base + kLocalGetAngleRva);
    gGetPowerSelector = reinterpret_cast<void *>(base + kGetPowerSelectorRva);
    gLocalGetAngleSelector = reinterpret_cast<void *>(base + kLocalGetAngleSelectorRva);

    int angleHook = DobbyHook(reinterpret_cast<void *>(base + kGetAngleRva),
                              reinterpret_cast<void *>(hookGetAngle),
                              reinterpret_cast<void **>(&gOriginalGetAngle));
    int touchHook = DobbyHook(reinterpret_cast<void *>(base + kTouchesMovedRva),
                              reinterpret_cast<void *>(hookTouchesMoved),
                              reinterpret_cast<void **>(&gOriginalTouchesMoved));

    if (angleHook != 0 || touchHook != 0 || !gOriginalGetAngle || !gOriginalTouchesMoved) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "DobbyHook failed angle=%d touch=%d", angleHook, touchHook);
        gStatus.store(-3, std::memory_order_release);
        gInstalling.store(false, std::memory_order_release);
        return -3;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "live aim hooks installed base=%p",
                        reinterpret_cast<void *>(base));
    gStatus.store(2, std::memory_order_release);
    gInstalling.store(false, std::memory_order_release);
    return 2;
#endif
}

double ageMs(int64_t timestampNs) {
    if (timestampNs <= 0) return -1.0;
    int64_t delta = monotonicNs() - timestampNs;
    if (delta < 0) delta = 0;
    return static_cast<double>(delta) / 1000000.0;
}

jdoubleArray makeSnapshot(JNIEnv *env) {
    jdouble values[5];
    values[0] = static_cast<jdouble>(gStatus.load(std::memory_order_acquire));
    values[1] = static_cast<jdouble>(gAngle.load(std::memory_order_relaxed));
    values[2] = static_cast<jdouble>(gPower.load(std::memory_order_relaxed));
    values[3] = static_cast<jdouble>(ageMs(gAngleNs.load(std::memory_order_acquire)));
    values[4] = static_cast<jdouble>(ageMs(gPowerNs.load(std::memory_order_acquire)));
    jdoubleArray result = env->NewDoubleArray(5);
    if (!result) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 5, values);
    return result;
}

void installJavaHost(JNIEnv *env, const char *className) {
    jclass host = env->FindClass(className);
    if (!host) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    jmethodID install = env->GetStaticMethodID(host, "installFromNative", "()V");
    if (install) env->CallStaticVoidMethod(host, install);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(host);
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_jaowzin_universalloader_CarromCtfHost_nativeStart(JNIEnv *, jclass) {
    return static_cast<jint>(installHooks());
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_jaowzin_universalloader_CarromCtfHost_nativeSnapshot(JNIEnv *env, jclass) {
    return makeSnapshot(env);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jaowzin_universalloader_CarromCtfAutoPilot_nativeStart(JNIEnv *, jclass) {
    return static_cast<jint>(installHooks());
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_jaowzin_universalloader_CarromCtfAutoPilot_nativeSnapshot(JNIEnv *env, jclass) {
    return makeSnapshot(env);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    if (!vm) return JNI_ERR;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }
    installJavaHost(env, "dev/jaowzin/universalloader/CarromCtfHost");
    installJavaHost(env, "dev/jaowzin/universalloader/CarromCtfAutoPilot");
    return JNI_VERSION_1_6;
}

#include <jni.h>

// UniversalLoader floating-menu sample plugin.
// The loader exposes FloatingMenuHost only while System.load() is executing for a virtual app.
// This plugin opts in from JNI_OnLoad and asks the host to attach a harmless floating UI panel.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (vm == nullptr) return JNI_ERR;

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass host = env->FindClass("dev/jaowzin/universalloader/FloatingMenuHost");
    if (host == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_VERSION_1_6;
    }

    jmethodID install = env->GetStaticMethodID(host, "installFromNative", "()V");
    if (install != nullptr) {
        env->CallStaticVoidMethod(host, install);
    }

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(host);
    return JNI_VERSION_1_6;
}

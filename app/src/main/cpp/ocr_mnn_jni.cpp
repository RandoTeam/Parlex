#include <jni.h>

// Capability-only bridge. The real MNN implementation is compiled in a
// future ABI-specific build when the restored MNN checkout is available.
// Returning explicit unavailability is safer than misreporting CPU as GPU.

extern "C" JNIEXPORT jboolean JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeIsAvailable(
        JNIEnv *, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeBackendName(
        JNIEnv *env, jobject) {
    return env->NewStringUTF("unavailable");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeDiagnostics(
        JNIEnv *env, jobject) {
    return env->NewStringUTF("MNN OCR bridge is a capability stub in this APK");
}

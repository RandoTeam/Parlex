/**
 * Stub JNI bridge — used when llama.cpp is not yet cloned.
 * All methods return placeholder values so the app compiles.
 */

#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "TransLive-Stub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeLoadModel(
    JNIEnv*, jobject, jstring, jint) {
    LOGW("Stub: nativeLoadModel called. llama.cpp not integrated yet.");
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeTranslate(
    JNIEnv* env, jobject, jlong, jstring, jint) {
    LOGW("Stub: nativeTranslate called. llama.cpp not integrated yet.");
    return env->NewStringUTF("[Stub: model not loaded]");
}

JNIEXPORT void JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeUnloadModel(
    JNIEnv*, jobject, jlong) {
    LOGW("Stub: nativeUnloadModel called.");
}

JNIEXPORT jboolean JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeIsLoaded(
    JNIEnv*, jobject, jlong) {
    return JNI_FALSE;
}

} // extern "C"

/**
 * Stub JNI bridge — used when llama.cpp is not yet cloned.
 * All methods return placeholder values so the app compiles and runs.
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
    // Return non-zero so the engine thinks a model is loaded
    return 1;
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
    // Return true so streaming and translate() don't throw require() failures
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeTranslateStreaming(
    JNIEnv* env, jobject, jlong, jstring, jint, jobject callback) {
    LOGW("Stub: nativeTranslateStreaming called. llama.cpp not integrated yet.");

    // Send one stub token through the callback so the UI shows something
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    if (onToken != nullptr) {
        jstring stubToken = env->NewStringUTF("[Stub: model not loaded]");
        env->CallBooleanMethod(callback, onToken, stubToken);
        env->DeleteLocalRef(stubToken);
    }

    jint counts[2] = {0, 1};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, counts);
    return arr;
}

} // extern "C"

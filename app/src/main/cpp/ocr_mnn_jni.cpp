#include <jni.h>
#include <string>

#ifdef PARLEX_MNN_LINKED
#include <MNN/Interpreter.hpp>

struct OcrMnnSession {
    MNN::Interpreter* interpreter = nullptr;
    MNN::Session* session = nullptr;
};
#endif

// Capability-only bridge. The real MNN implementation is compiled in a
// future ABI-specific build when the restored MNN checkout is available.
// Returning explicit unavailability is safer than misreporting CPU as GPU.

extern "C" JNIEXPORT jboolean JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeIsAvailable(
        JNIEnv *, jobject) {
#ifdef PARLEX_MNN_LINKED
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeBackendName(
        JNIEnv *env, jobject) {
#ifdef PARLEX_MNN_LINKED
    return env->NewStringUTF("MNN linked; backend selected per model");
#else
    return env->NewStringUTF("unavailable");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeDiagnostics(
        JNIEnv *env, jobject) {
#ifdef PARLEX_MNN_LINKED
    return env->NewStringUTF("MNN 3.6.1 linked; detector/recognizer model required");
#else
    return env->NewStringUTF("MNN OCR bridge is a capability stub in this APK");
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeLoadModel(
        JNIEnv *env, jobject, jstring modelPath, jint backend) {
#ifdef PARLEX_MNN_LINKED
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    auto* holder = new OcrMnnSession();
    holder->interpreter = MNN::Interpreter::createFromFile(path);
    env->ReleaseStringUTFChars(modelPath, path);
    if (holder->interpreter == nullptr) {
        delete holder;
        return 0;
    }
    MNN::ScheduleConfig config;
    config.numThread = 4;
    config.type = backend == 2 ? MNN_FORWARD_VULKAN
        : backend == 1 ? MNN_FORWARD_OPENCL
        : MNN_FORWARD_CPU;
    holder->session = holder->interpreter->createSession(config);
    if (holder->session == nullptr) {
        delete holder->interpreter;
        delete holder;
        return 0;
    }
    return reinterpret_cast<jlong>(holder);
#else
    (void) env; (void) modelPath; (void) backend;
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_translive_app_engine_OcrMnnRuntime_nativeReleaseModel(
        JNIEnv *, jobject, jlong handle) {
#ifdef PARLEX_MNN_LINKED
    auto* holder = reinterpret_cast<OcrMnnSession*>(handle);
    if (holder == nullptr) return;
    if (holder->interpreter != nullptr && holder->session != nullptr) {
        holder->interpreter->releaseSession(holder->session);
    }
    delete holder->interpreter;
    delete holder;
#else
    (void) handle;
#endif
}

/**
 * TransLive JNI Bridge — connects Kotlin TranslationEngine to llama.cpp.
 *
 * Requirements & Specifications:
 * - Strict Material / Technical zero-emoji policy across all logs and errors.
 * - Iterative prefill chunking: tokens chunked to <= n_batch during llama_decode.
 * - Memory & KV cache clearing: llama_memory_clear between inference turns.
 * - Robust null checks on model, ctx, vocab, sampler, and JNI callback objects.
 * - Full protection against empty inputs and context window overflow.
 *
 * Upstream Engine: llama.cpp b9464 (commit 5dcb71166686799f0d873eab7386234302d05ecf).
 */

#include <jni.h>
#include <algorithm>
#include <dlfcn.h>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <mutex>
#include <sys/sysinfo.h>
#include <unistd.h>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"

#define TAG "TransLive-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// OpenCL function pointer types for runtime dynamic probe without hard link dependency
typedef int cl_int;
typedef unsigned int cl_uint;
typedef unsigned long long cl_ulong;
typedef void* cl_platform_id;
typedef void* cl_device_id;

#define CL_SUCCESS 0
#define CL_PLATFORM_NAME 0x0902
#define CL_PLATFORM_VENDOR 0x0903
#define CL_PLATFORM_VERSION 0x0901
#define CL_DEVICE_TYPE_GPU (1 << 2)
#define CL_DEVICE_NAME 0x102B
#define CL_DEVICE_VENDOR 0x102C
#define CL_DRIVER_VERSION 0x102D
#define CL_DEVICE_VERSION 0x102F
#define CL_DEVICE_MAX_COMPUTE_UNITS 0x1002
#define CL_DEVICE_MAX_WORK_GROUP_SIZE 0x1004
#define CL_DEVICE_MAX_MEM_ALLOC_SIZE 0x1010
#define CL_DEVICE_GLOBAL_MEM_SIZE 0x101F
#define CL_DEVICE_EXTENSIONS 0x1030

typedef cl_int (*pfn_clGetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*);
typedef cl_int (*pfn_clGetPlatformInfo)(cl_platform_id, cl_uint, size_t, void*, size_t*);
typedef cl_int (*pfn_clGetDeviceIDs)(cl_platform_id, cl_ulong, cl_uint, cl_device_id*, cl_uint*);
typedef cl_int (*pfn_clGetDeviceInfo)(cl_device_id, cl_uint, size_t, void*, size_t*);

static bool runtime_library_available(const char * name) {
    void * handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    if (!handle) return false;
    dlclose(handle);
    return true;
}

static std::string read_first_line(const char * path) {
    std::ifstream file(path);
    std::string line;
    return std::getline(file, line) ? line : "unavailable";
}

struct TransLiveContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_threads = 4;
    bool gpu_requested = false;
    int n_gpu_layers = 0;
    int n_batch = 512;
    int n_ubatch = 128;
    int n_ctx = 1024;
    std::string gpu_device;
};

static void translive_log_callback(enum ggml_log_level level, const char* text, void* /*user_data*/) {
    if (!text || text[0] == '\0') return;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO; break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO; break;
    }
    __android_log_print(prio, "TransLive-LLAMA", "%s", text);
}

static void ensure_backend_initialized() {
    static std::once_flag s_init_flag;
    std::call_once(s_init_flag, []() {
        llama_log_set(translive_log_callback, nullptr);
        llama_backend_init();
        LOGI("llama backend initialized with Android logcat bridge");
    });
}

static ggml_backend_dev_t find_gpu_device() {
    ensure_backend_initialized();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto * device = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            return device;
        }
    }
    return nullptr;
}

// ─── Helpers ──────────────────────────────────────────────────────────

static int tokenize_prompt(
    TransLiveContext* tlctx,
    const std::string& prompt,
    std::vector<llama_token>& out_tokens,
    bool useChatTemplate
) {
    if (!tlctx || !tlctx->model || !tlctx->vocab || prompt.empty()) {
        out_tokens.clear();
        return 0;
    }

    std::string finalPrompt;
    if (useChatTemplate) {
        const char* tmpl = llama_model_chat_template(tlctx->model, nullptr);
        if (tmpl != nullptr) {
            std::vector<llama_chat_message> messages = {
                {"user", prompt.c_str()}
            };

            std::vector<char> formatted(prompt.size() * 2 + 256);
            int len = llama_chat_apply_template(
                tmpl,
                messages.data(), messages.size(),
                true, formatted.data(), formatted.size()
            );
            if (len < 0 || static_cast<size_t>(len) >= formatted.size()) {
                formatted.resize(std::max(0, len) + 1);
                len = llama_chat_apply_template(
                    tmpl,
                    messages.data(), messages.size(),
                    true, formatted.data(), formatted.size()
                );
            }
            if (len > 0) {
                finalPrompt = std::string(formatted.data(), len);
            } else {
                finalPrompt = prompt;
            }
        } else {
            finalPrompt = prompt;
        }
    } else {
        finalPrompt = prompt;
    }

    out_tokens.resize(finalPrompt.size() + 64);
    int n_tokens = llama_tokenize(
        tlctx->vocab, finalPrompt.c_str(), static_cast<int32_t>(finalPrompt.size()),
        out_tokens.data(), static_cast<int32_t>(out_tokens.size()), true, true
    );
    if (n_tokens < 0) {
        out_tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(
            tlctx->vocab, finalPrompt.c_str(), static_cast<int32_t>(finalPrompt.size()),
            out_tokens.data(), static_cast<int32_t>(out_tokens.size()), true, true
        );
    }

    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt (code %d)", n_tokens);
        out_tokens.clear();
        return 0;
    }

    out_tokens.resize(static_cast<size_t>(n_tokens));
    return n_tokens;
}

static int prefill_prompt(TransLiveContext* tlctx, const std::vector<llama_token>& tokens) {
    if (!tlctx || !tlctx->ctx || tokens.empty()) {
        return -1;
    }

    // Reset KV cache memory contents for sequence 0
    llama_memory_clear(llama_get_memory(tlctx->ctx), true);

    const int32_t n_batch = tlctx->n_batch > 0 ? tlctx->n_batch : 512;
    const int32_t total_tokens = static_cast<int32_t>(tokens.size());

    // Iteratively chunk tokens into batches <= n_batch
    for (int32_t i = 0; i < total_tokens; i += n_batch) {
        const int32_t cur_batch = std::min(n_batch, total_tokens - i);
        llama_batch batch = llama_batch_get_one(
            const_cast<llama_token*>(tokens.data() + i),
            cur_batch
        );

        const int decode_res = llama_decode(tlctx->ctx, batch);
        if (decode_res != 0) {
            LOGE("llama_decode failed during prefill at offset %d / %d (status %d)", i, total_tokens, decode_res);
            llama_memory_clear(llama_get_memory(tlctx->ctx), true);
            return -1;
        }
    }
    return 0;
}

static int bounded_generation_tokens(
    TransLiveContext* tlctx,
    size_t prompt_token_count,
    int requested_tokens
) {
    if (!tlctx || !tlctx->ctx) return 0;
    const int context_tokens = llama_n_ctx(tlctx->ctx);
    const int reserve_tokens = 8;
    const int available_tokens = context_tokens - static_cast<int>(prompt_token_count) - reserve_tokens;
    return std::max(0, std::min(requested_tokens, available_tokens));
}

static llama_sampler* create_translation_sampler(
    const llama_vocab* vocab,
    float temperature,
    int top_k,
    float top_p,
    float repetition_penalty
) {
    if (!vocab) return nullptr;
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) return nullptr;
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(vocab), 64, repetition_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return sampler;
}

static int token_to_string(const llama_vocab* vocab, llama_token token,
                           char* buf, int buf_size) {
    if (!vocab || !buf || buf_size <= 0) return 0;
    return llama_token_to_piece(vocab, token, buf, buf_size, 0, true);
}

// ─── JNI Methods ──────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("translive native library loaded (JNI 1.6)");
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeLoadModel(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelPath,
    jint nThreads,
    jboolean useGpu,
    jint nGpuLayers,
    jint nBatch,
    jint nUbatch,
    jint nCtx) {

    ensure_backend_initialized();

    if (!modelPath) {
        LOGE("Model path is null");
        return 0;
    }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to extract UTF chars for model path");
        return 0;
    }

    const int effectiveBatch = nBatch > 0 ? nBatch : 512;
    const int effectiveUbatch = nUbatch > 0 ? std::min(nUbatch, effectiveBatch) : 128;
    const int effectiveCtx = nCtx > 0 ? nCtx : 1024;

    LOGI("Loading model: %s (threads=%d, gpu=%d, layers=%d, batch=%d, ubatch=%d, ctx=%d)",
         path, nThreads, useGpu, nGpuLayers, effectiveBatch, effectiveUbatch, effectiveCtx);

    llama_model_params model_params = llama_model_default_params();
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    if (useGpu) {
        auto * gpu = find_gpu_device();
        if (!gpu) {
            LOGE("GPU was requested, but no compiled GGUF GPU backend is available");
            env->ReleaseStringUTFChars(modelPath, path);
            return 0;
        }
        model_params.n_gpu_layers = nGpuLayers;
        LOGI("GGUF GPU offload enabled: device=%s, layers=%d", ggml_backend_dev_name(gpu), nGpuLayers);
    } else {
        model_params.n_gpu_layers = 0;
    }

    llama_model* model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load model from path");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(effectiveCtx);
    ctx_params.n_batch = static_cast<uint32_t>(effectiveBatch);
    ctx_params.n_ubatch = static_cast<uint32_t>(effectiveUbatch);
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama context from model");
        llama_model_free(model);
        return 0;
    }

    auto* tlctx = new TransLiveContext();
    tlctx->model = model;
    tlctx->ctx = ctx;
    tlctx->vocab = llama_model_get_vocab(model);
    tlctx->n_threads = nThreads;
    tlctx->gpu_requested = useGpu;
    tlctx->n_gpu_layers = useGpu ? nGpuLayers : 0;
    tlctx->n_batch = effectiveBatch;
    tlctx->n_ubatch = effectiveUbatch;
    tlctx->n_ctx = effectiveCtx;

    if (useGpu) {
        auto * gpu = find_gpu_device();
        tlctx->gpu_device = gpu ? ggml_backend_dev_name(gpu) : "unavailable";
    }

    LOGI("Model successfully loaded (mmap=1, flash_attn=1, ctx=%d, batch=%d, ubatch=%d, layers=%d)",
         effectiveCtx, effectiveBatch, effectiveUbatch, tlctx->n_gpu_layers);
    return reinterpret_cast<jlong>(tlctx);
}

JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeTranslate(
    JNIEnv* env, jobject /*thiz*/, jlong contextPtr, jstring prompt,
    jint maxTokens, jboolean useChatTemplate, jfloat temperature, jint topK,
    jfloat topP, jfloat repetitionPenalty) {

    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);
    if (!tlctx || !tlctx->ctx || !tlctx->model || !tlctx->vocab) {
        return env->NewStringUTF("[Error: context not initialized]");
    }

    if (!prompt) {
        return env->NewStringUTF("");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (!promptStr) {
        return env->NewStringUTF("");
    }
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    if (promptCpp.empty()) {
        return env->NewStringUTF("");
    }

    std::vector<llama_token> tokens;
    tokenize_prompt(tlctx, promptCpp, tokens, useChatTemplate);
    if (tokens.empty()) {
        return env->NewStringUTF("");
    }

    const int generation_limit = bounded_generation_tokens(tlctx, tokens.size(), maxTokens);
    if (generation_limit <= 0) {
        return env->NewStringUTF("[Error: input exceeds model context]");
    }

    if (prefill_prompt(tlctx, tokens) != 0) {
        return env->NewStringUTF("[Error: decode failed]");
    }

    llama_sampler* sampler = create_translation_sampler(
        tlctx->vocab, temperature, topK, topP, repetitionPenalty);
    if (!sampler) {
        llama_memory_clear(llama_get_memory(tlctx->ctx), true);
        return env->NewStringUTF("[Error: sampler allocation failed]");
    }

    std::string result;
    llama_token eos = llama_vocab_eos(tlctx->vocab);

    for (int i = 0; i < generation_limit; i++) {
        llama_token token = llama_sampler_sample(sampler, tlctx->ctx, -1);

        if (llama_vocab_is_eog(tlctx->vocab, token) || token == eos) break;

        char buf[256];
        int n = token_to_string(tlctx->vocab, token, buf, sizeof(buf));
        if (n > 0) result.append(buf, n);

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(tlctx->ctx, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_memory_clear(llama_get_memory(tlctx->ctx), true);

    LOGI("Translation complete: %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jintArray JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeTranslateStreaming(
    JNIEnv* env, jobject /*thiz*/, jlong contextPtr, jstring prompt,
    jint maxTokens, jboolean useChatTemplate, jfloat temperature, jint topK,
    jfloat topP, jfloat repetitionPenalty, jobject callback) {

    jint counts[2] = {0, 0};
    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);

    if (!tlctx || !tlctx->ctx || !tlctx->model || !tlctx->vocab || !callback) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    jclass cbClass = env->GetObjectClass(callback);
    if (!cbClass) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    jmethodID onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    if (!onTokenMethod) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    if (!prompt) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (!promptStr) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    if (promptCpp.empty()) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    std::vector<llama_token> tokens;
    counts[0] = tokenize_prompt(tlctx, promptCpp, tokens, useChatTemplate);
    if (tokens.empty()) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    const int generation_limit = bounded_generation_tokens(tlctx, tokens.size(), maxTokens);
    if (generation_limit <= 0) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    if (prefill_prompt(tlctx, tokens) != 0) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    llama_sampler* sampler = create_translation_sampler(
        tlctx->vocab, temperature, topK, topP, repetitionPenalty);
    if (!sampler) {
        llama_memory_clear(llama_get_memory(tlctx->ctx), true);
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    llama_token eos = llama_vocab_eos(tlctx->vocab);

    for (int i = 0; i < generation_limit; i++) {
        llama_token token = llama_sampler_sample(sampler, tlctx->ctx, -1);

        if (llama_vocab_is_eog(tlctx->vocab, token) || token == eos) break;

        char buf[256];
        int n = token_to_string(tlctx->vocab, token, buf, sizeof(buf));
        if (n > 0) {
            jstring tokenStr = env->NewStringUTF(std::string(buf, n).c_str());
            jboolean keepGoing = env->CallBooleanMethod(callback, onTokenMethod, tokenStr);
            env->DeleteLocalRef(tokenStr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            counts[1]++;
            if (!keepGoing) break;
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(tlctx->ctx, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_memory_clear(llama_get_memory(tlctx->ctx), true);

    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, counts);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeUnloadModel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong contextPtr) {

    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);
    if (tlctx) {
        if (tlctx->ctx) llama_free(tlctx->ctx);
        if (tlctx->model) llama_model_free(tlctx->model);
        delete tlctx;
        LOGI("Model unloaded");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeIsLoaded(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong contextPtr) {

    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);
    return (tlctx && tlctx->model && tlctx->ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeRuntimeDiagnostics(
    JNIEnv * env, jobject /*thiz*/) {

    struct sysinfo memory {};
    sysinfo(&memory);
    const unsigned long long totalMiB =
        (static_cast<unsigned long long>(memory.totalram) * memory.mem_unit) / (1024ULL * 1024ULL);
    const unsigned long long freeMiB =
        (static_cast<unsigned long long>(memory.freeram) * memory.mem_unit) / (1024ULL * 1024ULL);

    std::ostringstream out;
    out << "=== CPU & Host Memory ===\n";
    out << "CPU logical cores: " << sysconf(_SC_NPROCESSORS_ONLN) << "\n";
    out << "System RAM: " << freeMiB << " MiB free / " << totalMiB << " MiB total\n";

    out << "\n=== Kernel GPU Subsystem ===\n";
    out << "GPU (KGSL): " << read_first_line("/sys/class/kgsl/kgsl-3d0/gpu_model") << "\n";
    out << "Vulkan loader: " << (runtime_library_available("libvulkan.so") ? "available" : "unavailable") << "\n";

    out << "\n=== OpenCL Hardware Probe ===\n";
    void* clHandle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!clHandle) {
        out << "OpenCL loader: unavailable (dlopen libOpenCL.so failed)\n";
    } else {
        out << "OpenCL loader: available (libOpenCL.so loaded)\n";

        auto clGetPlatformIDs_ptr = (pfn_clGetPlatformIDs)dlsym(clHandle, "clGetPlatformIDs");
        auto clGetPlatformInfo_ptr = (pfn_clGetPlatformInfo)dlsym(clHandle, "clGetPlatformInfo");
        auto clGetDeviceIDs_ptr = (pfn_clGetDeviceIDs)dlsym(clHandle, "clGetDeviceIDs");
        auto clGetDeviceInfo_ptr = (pfn_clGetDeviceInfo)dlsym(clHandle, "clGetDeviceInfo");

        if (!clGetPlatformIDs_ptr || !clGetPlatformInfo_ptr || !clGetDeviceIDs_ptr || !clGetDeviceInfo_ptr) {
            out << "OpenCL symbols: incomplete symbol table in libOpenCL.so\n";
        } else {
            cl_uint numPlatforms = 0;
            if (clGetPlatformIDs_ptr(0, nullptr, &numPlatforms) == CL_SUCCESS && numPlatforms > 0) {
                std::vector<cl_platform_id> platforms(numPlatforms);
                clGetPlatformIDs_ptr(numPlatforms, platforms.data(), nullptr);

                for (cl_uint p = 0; p < numPlatforms; ++p) {
                    char pName[256] = {0};
                    char pVersion[256] = {0};
                    char pVendor[256] = {0};
                    clGetPlatformInfo_ptr(platforms[p], CL_PLATFORM_NAME, sizeof(pName), pName, nullptr);
                    clGetPlatformInfo_ptr(platforms[p], CL_PLATFORM_VERSION, sizeof(pVersion), pVersion, nullptr);
                    clGetPlatformInfo_ptr(platforms[p], CL_PLATFORM_VENDOR, sizeof(pVendor), pVendor, nullptr);

                    out << "Platform [" << p << "]: " << pName << " (" << pVendor << ") " << pVersion << "\n";

                    cl_uint numDevices = 0;
                    if (clGetDeviceIDs_ptr(platforms[p], CL_DEVICE_TYPE_GPU, 0, nullptr, &numDevices) == CL_SUCCESS && numDevices > 0) {
                        std::vector<cl_device_id> devices(numDevices);
                        clGetDeviceIDs_ptr(platforms[p], CL_DEVICE_TYPE_GPU, numDevices, devices.data(), nullptr);

                        for (cl_uint d = 0; d < numDevices; ++d) {
                            char dName[256] = {0};
                            char dDriverVer[256] = {0};
                            char dDeviceVer[256] = {0};
                            cl_uint computeUnits = 0;
                            cl_ulong maxAllocSize = 0;
                            cl_ulong globalMemSize = 0;
                            size_t maxWorkGroupSize = 0;

                            clGetDeviceInfo_ptr(devices[d], CL_DEVICE_NAME, sizeof(dName), dName, nullptr);
                            clGetDeviceInfo_ptr(devices[d], CL_DRIVER_VERSION, sizeof(dDriverVer), dDriverVer, nullptr);
                            clGetDeviceInfo_ptr(devices[d], CL_DEVICE_VERSION, sizeof(dDeviceVer), dDeviceVer, nullptr);
                            clGetDeviceInfo_ptr(devices[d], CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(computeUnits), &computeUnits, nullptr);
                            clGetDeviceInfo_ptr(devices[d], CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeof(maxAllocSize), &maxAllocSize, nullptr);
                            clGetDeviceInfo_ptr(devices[d], CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(globalMemSize), &globalMemSize, nullptr);
                            clGetDeviceInfo_ptr(devices[d], CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(maxWorkGroupSize), &maxWorkGroupSize, nullptr);

                            out << "  GPU Device [" << d << "]: " << dName << "\n";
                            out << "  Driver version: " << dDriverVer << "\n";
                            out << "  OpenCL version: " << dDeviceVer << "\n";
                            out << "  Compute units: " << computeUnits << "\n";
                            out << "  Max workgroup size: " << maxWorkGroupSize << "\n";
                            out << "  Max alloc size: " << (maxAllocSize / (1024 * 1024)) << " MiB (" << maxAllocSize << " bytes)\n";
                            out << "  Global memory: " << (globalMemSize / (1024 * 1024)) << " MiB (" << globalMemSize << " bytes)\n";

                            size_t extSize = 0;
                            clGetDeviceInfo_ptr(devices[d], CL_DEVICE_EXTENSIONS, 0, nullptr, &extSize);
                            if (extSize > 0) {
                                std::vector<char> extBuf(extSize);
                                clGetDeviceInfo_ptr(devices[d], CL_DEVICE_EXTENSIONS, extSize, extBuf.data(), nullptr);
                                std::string extensions(extBuf.data());

                                out << "  Key Qualcomm extensions:\n";
                                auto checkExt = [&](const char* name) {
                                    out << "    - " << name << ": " << (extensions.find(name) != std::string::npos ? "SUPPORTED" : "no") << "\n";
                                };
                                checkExt("cl_qcom_android_native_buffer_host_ptr");
                                checkExt("cl_qcom_ext_host_ptr");
                                checkExt("cl_qcom_ion_host_ptr");
                                checkExt("cl_khr_fp16");
                                checkExt("cl_khr_subgroups");
                                checkExt("cl_qcom_perf_hint");
                                checkExt("cl_qcom_recordable_queues");
                            }
                        }
                    } else {
                        out << "  No CL_DEVICE_TYPE_GPU found for platform " << p << "\n";
                    }
                }
            } else {
                out << "No OpenCL platforms found on system\n";
            }
        }
        dlclose(clHandle);
    }

    out << "\n=== GGUF Backend Probe ===\n";
    auto * gpu = find_gpu_device();
    out << "GGUF GPU backend compiled: " << (gpu ? "OpenCL" : "no") << "\n";
    if (gpu) {
        out << "GGUF GPU device: " << ggml_backend_dev_name(gpu) << "\n";
        out << "GGUF GPU offload: available (dynamic Adreno device profile configured)";
    } else {
        out << "GGUF GPU offload: unavailable";
    }

    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"

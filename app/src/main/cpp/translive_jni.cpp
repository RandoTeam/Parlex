/**
 * TransLive JNI Bridge — connects Kotlin TranslationEngine to llama.cpp.
 *
 * This file provides native methods for:
 * - Loading GGUF model files (mmap, Flash Attention)
 * - Running translation inference (blocking and streaming)
 * - Releasing model resources
 *
 * Sampling: official HY-MT 1.5 parameters (temp=0.7, top_k=20, top_p=0.6, rep_penalty=1.05)
 * Source: https://huggingface.co/tencent/HY-MT1.5-1.8B
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "TransLive-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct TransLiveContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_threads = 4;
};

// ─── Helpers ──────────────────────────────────────────────────────────

/**
 * Tokenize prompt into token vector.
 * If useChatTemplate is true, wraps prompt in model's chat template first.
 * If false, tokenizes the raw prompt directly (for models like TranslateGemma
 * whose prompt format already contains structured output markers).
 * Returns number of tokens, or -1 on failure.
 */
static int tokenize_prompt(TransLiveContext* tlctx, const std::string& prompt,
                           std::vector<llama_token>& out_tokens, bool useChatTemplate) {
    std::string finalPrompt;

    if (useChatTemplate) {
        // Wrap prompt in chat template (HY-MT, etc.)
        std::vector<llama_chat_message> messages = {
            {"user", prompt.c_str()}
        };

        std::vector<char> formatted(prompt.size() * 2 + 256);
        int len = llama_chat_apply_template(
            llama_model_chat_template(tlctx->model, nullptr),
            messages.data(), messages.size(),
            true, formatted.data(), formatted.size()
        );
        if (len < 0 || (size_t)len >= formatted.size()) {
            formatted.resize(len + 1);
            len = llama_chat_apply_template(
                llama_model_chat_template(tlctx->model, nullptr),
                messages.data(), messages.size(),
                true, formatted.data(), formatted.size()
            );
        }
        finalPrompt = std::string(formatted.data(), len);
    } else {
        // Raw prompt — no chat template wrapping
        finalPrompt = prompt;
    }

    // Tokenize
    out_tokens.resize(finalPrompt.size() + 64);
    int n_tokens = llama_tokenize(
        tlctx->vocab, finalPrompt.c_str(), finalPrompt.size(),
        out_tokens.data(), out_tokens.size(), true, true
    );
    if (n_tokens < 0) {
        out_tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            tlctx->vocab, finalPrompt.c_str(), finalPrompt.size(),
            out_tokens.data(), out_tokens.size(), true, true
        );
    }
    out_tokens.resize(n_tokens);
    return n_tokens;
}

/**
 * Prepare context for inference: clear KV cache, encode prompt tokens.
 * Returns 0 on success, non-zero on failure.
 */
static int prefill_prompt(TransLiveContext* tlctx, const std::vector<llama_token>& tokens) {
    llama_memory_clear(llama_get_memory(tlctx->ctx), true);
    llama_batch batch = llama_batch_get_one(
        const_cast<llama_token*>(tokens.data()), tokens.size()
    );
    return llama_decode(tlctx->ctx, batch);
}

/**
 * Create translation sampler with official HY-MT 1.5 recommended parameters.
 * Source: https://huggingface.co/tencent/HY-MT1.5-1.8B
 * { "top_k": 20, "top_p": 0.6, "repetition_penalty": 1.05, "temperature": 0.7 }
 *
 * Chain order follows llama.cpp convention: penalties → top_k → top_p → temp → dist
 * Penalties must see full logit distribution before filtering.
 * Caller must free with llama_sampler_free().
 */
static llama_sampler* create_translation_sampler() {
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return sampler;
}

/**
 * Decode a single token to UTF-8 string piece.
 * Returns number of bytes written, or 0 if token cannot be decoded.
 */
static int token_to_string(const llama_vocab* vocab, llama_token token,
                           char* buf, int buf_size) {
    return llama_token_to_piece(vocab, token, buf, buf_size, 0, true);
}

// ─── JNI Methods ──────────────────────────────────────────────────────

extern "C" {

/** Initialize llama backend once when the .so is loaded */
JNIEXPORT jint JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    llama_backend_init();
    LOGI("llama backend initialized");
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeLoadModel(
    JNIEnv* env, jobject /*thiz*/, jstring modelPath, jint nThreads) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s (threads=%d)", path, nThreads);

    // Load model with mmap (default, explicit for clarity)
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    llama_model* model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1024;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto* tlctx = new TransLiveContext();
    tlctx->model = model;
    tlctx->ctx = ctx;
    tlctx->vocab = llama_model_get_vocab(model);
    tlctx->n_threads = nThreads;

    LOGI("Model loaded (mmap=1, flash_attn=1, n_ctx=1024)");
    return reinterpret_cast<jlong>(tlctx);
}

JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeTranslate(
    JNIEnv* env, jobject /*thiz*/, jlong contextPtr, jstring prompt,
    jint maxTokens, jboolean useChatTemplate) {

    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);
    if (!tlctx || !tlctx->ctx) {
        return env->NewStringUTF("[Error: context not initialized]");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Tokenize + prefill
    std::vector<llama_token> tokens;
    tokenize_prompt(tlctx, promptCpp, tokens, useChatTemplate);

    if (prefill_prompt(tlctx, tokens) != 0) {
        return env->NewStringUTF("[Error: decode failed]");
    }

    // Generate
    llama_sampler* sampler = create_translation_sampler();
    std::string result;
    llama_token eos = llama_vocab_eos(tlctx->vocab);

    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(sampler, tlctx->ctx, -1);

        if (llama_vocab_is_eog(tlctx->vocab, token) || token == eos) break;

        char buf[256];
        int n = token_to_string(tlctx->vocab, token, buf, sizeof(buf));
        if (n > 0) result.append(buf, n);

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(tlctx->ctx, batch) != 0) break;
    }

    llama_sampler_free(sampler);

    LOGI("Translation complete: %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

/**
 * Streaming translation: calls callback.onToken(String) for each generated token.
 * Returns int array: [promptTokenCount, generatedTokenCount].
 */
JNIEXPORT jintArray JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeTranslateStreaming(
    JNIEnv* env, jobject /*thiz*/, jlong contextPtr, jstring prompt,
    jint maxTokens, jboolean useChatTemplate, jobject callback) {

    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");

    jint counts[2] = {0, 0};

    if (!tlctx || !tlctx->ctx || !onTokenMethod) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Tokenize + prefill
    std::vector<llama_token> tokens;
    counts[0] = tokenize_prompt(tlctx, promptCpp, tokens, useChatTemplate);

    if (prefill_prompt(tlctx, tokens) != 0) {
        jintArray arr = env->NewIntArray(2);
        env->SetIntArrayRegion(arr, 0, 2, counts);
        return arr;
    }

    // Generate with per-token callback
    llama_sampler* sampler = create_translation_sampler();
    llama_token eos = llama_vocab_eos(tlctx->vocab);
    int generated = 0;

    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(sampler, tlctx->ctx, -1);

        if (llama_vocab_is_eog(tlctx->vocab, token) || token == eos) break;

        char buf[256];
        int n = token_to_string(tlctx->vocab, token, buf, sizeof(buf));
        if (n > 0) {
            jstring tokenStr = env->NewStringUTF(std::string(buf, n).c_str());
            jboolean cont = env->CallBooleanMethod(callback, onTokenMethod, tokenStr);
            env->DeleteLocalRef(tokenStr);
            if (!cont) {
                LOGI("Streaming cancelled at token %d", i);
                break;
            }
            generated++;
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(tlctx->ctx, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    counts[1] = generated;

    LOGI("Streaming: %d prompt, %d generated tokens", counts[0], counts[1]);
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

} // extern "C"

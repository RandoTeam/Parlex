/**
 * TransLive JNI Bridge — connects Kotlin TranslationEngine to llama.cpp.
 *
 * This file provides native methods for:
 * - Loading GGUF model files
 * - Running translation inference
 * - Releasing model resources
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

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeLoadModel(
    JNIEnv* env, jobject /*thiz*/, jstring modelPath, jint nThreads) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s (threads=%d)", path, nThreads);

    // Initialize llama backend
    llama_backend_init();

    // Load model
    llama_model_params model_params = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

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

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(tlctx);
}

JNIEXPORT jstring JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeTranslate(
    JNIEnv* env, jobject /*thiz*/, jlong contextPtr, jstring prompt, jint maxTokens) {

    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);
    if (!tlctx || !tlctx->ctx) {
        return env->NewStringUTF("[Error: context not initialized]");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Tokenize using chat template
    std::vector<llama_chat_message> messages = {
        {"user", promptCpp.c_str()}
    };

    // Apply chat template
    std::vector<char> formatted(promptCpp.size() * 2 + 256);
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
    std::string formattedPrompt(formatted.data(), len);

    // Tokenize
    std::vector<llama_token> tokens(formattedPrompt.size() + 64);
    int n_tokens = llama_tokenize(
        tlctx->vocab, formattedPrompt.c_str(), formattedPrompt.size(),
        tokens.data(), tokens.size(), true, true
    );
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            tlctx->vocab, formattedPrompt.c_str(), formattedPrompt.size(),
            tokens.data(), tokens.size(), true, true
        );
    }
    tokens.resize(n_tokens);

    // Clear memory (KV cache)
    llama_memory_clear(llama_get_memory(tlctx->ctx), true);

    // Create batch and process prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(tlctx->ctx, batch) != 0) {
        return env->NewStringUTF("[Error: decode failed]");
    }

    // Sampling setup
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    // Generate tokens
    std::string result;
    llama_token eos = llama_vocab_eos(tlctx->vocab);

    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(sampler, tlctx->ctx, -1);

        if (llama_vocab_is_eog(tlctx->vocab, token) || token == eos) {
            break;
        }

        // Decode token to string
        char buf[256];
        int n = llama_token_to_piece(tlctx->vocab, token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }

        // Prepare next batch
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(tlctx->ctx, batch) != 0) {
            break;
        }
    }

    llama_sampler_free(sampler);

    LOGI("Translation complete: %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
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
    llama_backend_free();
}

JNIEXPORT jboolean JNICALL
Java_com_translive_app_engine_TranslationEngine_nativeIsLoaded(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong contextPtr) {

    auto* tlctx = reinterpret_cast<TransLiveContext*>(contextPtr);
    return (tlctx && tlctx->model && tlctx->ctx) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

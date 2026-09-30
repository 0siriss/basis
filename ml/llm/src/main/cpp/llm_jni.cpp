// Thin JNI layer over the llama.cpp C API (no llama "common" library).
// One LlmHandle = one model + one context; calls on a handle must be serialized by the caller.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <sched.h>
#include <chrono>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "BasisLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct LlmHandle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    // Stats of the last generate(): prompt tokens, prompt ms, generated tokens, generation ms,
    // stall events, threads at the end.
    int64_t stats[6] = {0, 0, 0, 0, 0, 0};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java strings via GetStringUTFChars are "modified UTF-8"; take text as UTF-8 bytes instead.
std::string bytes_to_std(JNIEnv *env, jbyteArray b) {
    if (b == nullptr) return {};
    jsize n = env->GetArrayLength(b);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n);
    int got = llama_tokenize(vocab, text.data(), (int32_t) text.size(), tokens.data(), n, add_special, true);
    if (got < 0) tokens.clear(); else tokens.resize(got);
    return tokens;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeInit(JNIEnv *env, jobject, jstring libDir) {
    llama_log_set(log_callback, nullptr);
    std::string dir = to_std(env, libDir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    std::string info = llama_print_system_info();
    std::string backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        if (!backends.empty()) backends += ",";
        backends += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    info = "backends=[" + backends + "] " + info;
    LOGI("%s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeLoad(JNIEnv *env, jobject, jstring path, jint nCtx, jint nThreads, jboolean repack) {
    std::string p = to_std(env, path);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // Weights are repacked for the fast ARM kernels (KleidiAI/i8mm). With mmap the whole file also stays
    // resident as file pages after repacking, so RSS ≈ 2× model size (measured on Nubia: 5.6 GB for a 4B
    // Q4 → killed by the vendor's per-app limit). Reading the file instead keeps a single copy in RAM.
    mp.use_extra_bufts = repack;
    mp.load_mode = repack ? LLAMA_LOAD_MODE_NONE : LLAMA_LOAD_MODE_MMAP;
    llama_model *model = llama_model_load_from_file(p.c_str(), mp);
    if (!model) {
        LOGE("failed to load model %s", p.c_str());
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }
    auto *h = new LlmHandle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));
    LOGI("loaded %s, ctx=%u, threads=%d, repack=%d", desc, llama_n_ctx(ctx), nThreads, (int) repack);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeDescribe(JNIEnv *env, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    char desc[256];
    llama_model_desc(h->model, desc, sizeof(desc));
    return env->NewStringUTF(desc);
}

/** CPUs this thread may run on (Android moves background processes to a cpuset with fewer cores). */
extern "C" JNIEXPORT jint JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeAllowedCpus(JNIEnv *, jobject) {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return -1;
    return CPU_COUNT(&set);
}

extern "C" JNIEXPORT void JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeSetThreads(JNIEnv *, jobject, jlong handle, jint n) {
    llama_set_n_threads(reinterpret_cast<LlmHandle *>(handle)->ctx, n, n);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    return (jint) llama_n_ctx(reinterpret_cast<LlmHandle *>(handle)->ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeCountTokens(JNIEnv *env, jobject, jlong handle, jbyteArray text) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    return (jint) tokenize(h->vocab, bytes_to_std(env, text), false).size();
}

/**
 * Generates a completion for a fully formatted prompt (UTF-8 bytes). Streams UTF-8 byte pieces to
 * listener.onBytes(byte[]) which returns false to stop. Returns all generated bytes, or null on error.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeGenerate(JNIEnv *env, jobject, jlong handle, jbyteArray promptBytes,
                                                  jstring grammar, jint maxTokens, jfloat temperature,
                                                  jint seed, jobject listener) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    for (auto &s : h->stats) s = 0;
    jclass lc = env->GetObjectClass(listener);
    jmethodID onBytes = env->GetMethodID(lc, "onBytes", "([B)Z");

    llama_memory_clear(llama_get_memory(h->ctx), true);

    std::string prompt = bytes_to_std(env, promptBytes);
    std::vector<llama_token> tokens = tokenize(h->vocab, prompt, true);
    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (tokens.empty() || (int) tokens.size() + maxTokens > n_ctx) {
        LOGE("prompt too long: %zu tokens + %d > ctx %d", tokens.size(), maxTokens, n_ctx);
        return nullptr;
    }

    // Prompt processing in n_batch chunks.
    int64_t t0 = now_ms();
    const int n_batch = (int) llama_n_batch(h->ctx);
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        int n = (int) std::min<size_t>(n_batch, tokens.size() - i);
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            LOGE("decode failed on prompt");
            return nullptr;
        }
    }
    h->stats[0] = (int64_t) tokens.size();
    h->stats[1] = now_ms() - t0;

    // Sampler chain: [grammar] → penalties → top-k → top-p → temperature → dist (or greedy).
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    std::string g = to_std(env, grammar);
    if (!g.empty()) {
        llama_sampler *gs = llama_sampler_init_grammar(h->vocab, g.c_str(), "root");
        if (!gs) {
            LOGE("grammar parse failed");
            llama_sampler_free(smpl);
            return nullptr;
        }
        llama_sampler_chain_add(smpl, gs);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(h->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));
    }

    std::string out;
    int64_t t1 = now_ms();
    int generated = 0;
    char piece[256];
    bool ok = true;
    // Watchdog: on a phone some thread counts intermittently collapse to ~0.2 tok/s (measured on
    // Snapdragon 8 Elite / Nubia). Three consecutive tokens slower than 1 s → halve the threads.
    int slow_run = 0;
    int64_t t_tok = now_ms();
    for (; generated < maxTokens; generated++) {
        int64_t t_now = now_ms();
        if (generated > 0) {
            if (t_now - t_tok > 1000) slow_run++; else slow_run = 0;
            int nt = llama_n_threads(h->ctx);
            if (slow_run >= 3 && nt > 2) {
                int nn = std::max(2, nt / 2);
                llama_set_n_threads(h->ctx, nn, nn);
                h->stats[4]++;
                LOGE("generation stalled (%lld ms/token): threads %d -> %d", (long long) (t_now - t_tok), nt, nn);
                slow_run = 0;
            }
        }
        t_tok = t_now;
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);  // also accepts the token
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int n = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            out.append(piece, n);
            jbyteArray arr = env->NewByteArray(n);
            env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(piece));
            jboolean cont = env->CallBooleanMethod(listener, onBytes, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck() || !cont) { generated++; break; }
        }
        if (llama_decode(h->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            LOGE("decode failed during generation");
            ok = false;
            break;
        }
    }
    h->stats[2] = generated;
    h->stats[3] = now_ms() - t1;
    h->stats[5] = llama_n_threads(h->ctx);
    llama_sampler_free(smpl);
    if (!ok && out.empty()) return nullptr;

    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeLastStats(JNIEnv *env, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    jlongArray arr = env->NewLongArray(6);
    env->SetLongArrayRegion(arr, 0, 6, reinterpret_cast<const jlong *>(h->stats));
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_basis_ml_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "FridayLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct EngineHolder {
    std::mutex mutex;
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int32_t context_size = 0;
    int32_t threads = 4;
    std::atomic<bool> abort_flag{false};
    bool backend_initialized = false;
};

EngineHolder g_engine;

void free_loaded_locked() {
    if (g_engine.ctx != nullptr) {
        llama_free(g_engine.ctx);
        g_engine.ctx = nullptr;
    }
    if (g_engine.model != nullptr) {
        llama_model_free(g_engine.model);
        g_engine.model = nullptr;
    }
    g_engine.context_size = 0;
}

// Convert UTF-8 bytes in `pending` into UTF-16 `jchar` vector while keeping
// any incomplete trailing multi-byte UTF-8 sequence inside `pending` for next token.
std::vector<jchar> utf8_flush_complete_utf16(std::string & pending) {
    std::vector<jchar> out;
    size_t i = 0;
    const size_t n = pending.size();

    while (i < n) {
        unsigned char c = static_cast<unsigned char>(pending[i]);
        uint32_t cp = 0;
        size_t len = 0;

        if ((c & 0x80) == 0) {
            cp = c;
            len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
            if (i + len > n) break;
            cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(pending[i + 1]) & 0x3F);
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
            if (i + len > n) break;
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<unsigned char>(pending[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(pending[i + 2]) & 0x3F);
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
            if (i + len > n) break;
            cp = ((c & 0x07) << 18) |
                 ((static_cast<unsigned char>(pending[i + 1]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(pending[i + 2]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(pending[i + 3]) & 0x3F);
        } else {
            // Invalid byte, skip safely with replacement char
            out.push_back(0xFFFD);
            i += 1;
            continue;
        }

        if (cp <= 0xFFFF) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += len;
    }

    pending.erase(0, i);
    return out;
}

bool emit_utf16_piece(JNIEnv * env, jobject callback, jmethodID on_token_mid, std::string & pending) {
    auto utf16 = utf8_flush_complete_utf16(pending);
    if (utf16.empty()) return true;
    jstring j_piece = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
    if (j_piece == nullptr) return false;
    jboolean cont = env->CallBooleanMethod(callback, on_token_mid, j_piece);
    env->DeleteLocalRef(j_piece);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return cont == JNI_TRUE;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_ai_AndroidLlamaNativeBridge_nativeLoadModel(
        JNIEnv * env,
        jobject /* thiz */,
        jstring model_path_jstr,
        jint context_size,
        jint threads) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);

    if (!g_engine.backend_initialized) {
        llama_backend_init();
        g_engine.backend_initialized = true;
    }

    free_loaded_locked();
    g_engine.abort_flag.store(false);

    const char * model_path = env->GetStringUTFChars(model_path_jstr, nullptr);
    if (model_path == nullptr) return -1;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-safe mobile baseline
    mparams.use_mmap = true;

    llama_model * loaded_model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(model_path_jstr, model_path);

    if (loaded_model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return -1;
    }

    int32_t safe_ctx = context_size < 512 ? 512 : (context_size > 8192 ? 8192 : context_size);
    int32_t safe_threads = threads < 1 ? 2 : (threads > 8 ? 8 : threads);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(safe_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = safe_threads;
    cparams.n_threads_batch = safe_threads;

    llama_context * ctx = llama_init_from_model(loaded_model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed for n_ctx=%d", safe_ctx);
        llama_model_free(loaded_model);
        return -2;
    }

    g_engine.model = loaded_model;
    g_engine.ctx = ctx;
    g_engine.context_size = safe_ctx;
    g_engine.threads = safe_threads;

    char desc_buf[256];
    llama_model_desc(loaded_model, desc_buf, sizeof(desc_buf));
    LOGI("Loaded Dhīr model: %s (n_ctx=%d, threads=%d)", desc_buf, safe_ctx, safe_threads);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_ai_AndroidLlamaNativeBridge_nativeGetModelDesc(
        JNIEnv * env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr) {
        return env->NewStringUTF("unloaded");
    }
    char desc_buf[256];
    llama_model_desc(g_engine.model, desc_buf, sizeof(desc_buf));
    return env->NewStringUTF(desc_buf);
}

JNIEXPORT jint JNICALL
Java_com_example_ai_AndroidLlamaNativeBridge_nativeGenerate(
        JNIEnv * env,
        jobject /* thiz */,
        jstring prompt_jstr,
        jint max_tokens,
        jfloat temperature,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr || g_engine.ctx == nullptr) {
        return -1;
    }

    g_engine.abort_flag.store(false);
    llama_memory_clear(llama_get_memory(g_engine.ctx), true);

    jclass cb_cls = env->GetObjectClass(callback);
    if (cb_cls == nullptr) return -2;
    jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cb_cls);
    if (on_token_mid == nullptr) return -2;

    const char * prompt_c = env->GetStringUTFChars(prompt_jstr, nullptr);
    if (prompt_c == nullptr) return -3;
    std::string prompt(prompt_c);
    env->ReleaseStringUTFChars(prompt_jstr, prompt_c);

    const llama_vocab * vocab = llama_model_get_vocab(g_engine.model);
    if (vocab == nullptr) return -4;

    // Tokenize formatted Dhīr prompt (special tokens already embedded, parse_special=true, add_special=false)
    int32_t n_prompt_est = -llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        nullptr, 0, false, true
    );
    if (n_prompt_est <= 0) {
        n_prompt_est = static_cast<int32_t>(prompt.size()) + 16;
    }

    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt_est));
    int32_t n_prompt = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), false, true
    );
    if (n_prompt < 0) {
        tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), false, true
        );
    }
    if (n_prompt <= 0) {
        return -5;
    }
    tokens.resize(static_cast<size_t>(n_prompt));

    // Reserve head room inside context
    const int32_t max_prompt_tokens = g_engine.context_size - 64;
    if (n_prompt > max_prompt_tokens && max_prompt_tokens > 0) {
        int32_t drop = n_prompt - max_prompt_tokens;
        tokens.erase(tokens.begin(), tokens.begin() + drop);
        n_prompt = static_cast<int32_t>(tokens.size());
    }

    const int32_t chunk_size = 256;
    llama_batch batch = llama_batch_init(chunk_size, 0, 1);

    int32_t cur_pos = 0;
    for (int32_t offset = 0; offset < n_prompt; offset += chunk_size) {
        if (g_engine.abort_flag.load()) {
            llama_batch_free(batch);
            return 0;
        }
        int32_t count = std::min(chunk_size, n_prompt - offset);
        batch.n_tokens = count;
        for (int32_t i = 0; i < count; ++i) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = cur_pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (offset + i == n_prompt - 1) ? 1 : 0;
        }
        if (llama_decode(g_engine.ctx, batch) != 0) {
            LOGE("llama_decode prompt failed at offset=%d", offset);
            llama_batch_free(batch);
            return -6;
        }
        cur_pos += count;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature <= 0.05f ? 0.0f : temperature));
    if (temperature <= 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending_utf8;
    const int32_t safe_max_gen = std::min(static_cast<int32_t>(max_tokens), g_engine.context_size - cur_pos - 1);

    for (int32_t step = 0; step < safe_max_gen; ++step) {
        if (g_engine.abort_flag.load()) break;

        llama_token new_token = llama_sampler_sample(smpl, g_engine.ctx, -1);
        llama_sampler_accept(smpl, new_token);

        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char piece_buf[256];
        int32_t piece_len = llama_token_to_piece(vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (piece_len < 0) {
            std::vector<char> dyn_buf(static_cast<size_t>(-piece_len));
            int32_t got = llama_token_to_piece(vocab, new_token, dyn_buf.data(), static_cast<int32_t>(dyn_buf.size()), 0, true);
            if (got > 0) {
                pending_utf8.append(dyn_buf.data(), static_cast<size_t>(got));
            }
        } else if (piece_len > 0) {
            pending_utf8.append(piece_buf, static_cast<size_t>(piece_len));
        }

        if (!emit_utf16_piece(env, callback, on_token_mid, pending_utf8)) {
            break;
        }

        batch.n_tokens = 1;
        batch.token[0] = new_token;
        batch.pos[0] = cur_pos;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(g_engine.ctx, batch) != 0) {
            LOGE("llama_decode generation failed at pos=%d", cur_pos);
            break;
        }
        cur_pos++;
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_example_ai_AndroidLlamaNativeBridge_nativeAbort(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    g_engine.abort_flag.store(true);
}

JNIEXPORT void JNICALL
Java_com_example_ai_AndroidLlamaNativeBridge_nativeUnloadModel(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    g_engine.abort_flag.store(true);
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    free_loaded_locked();
}

} // extern "C"

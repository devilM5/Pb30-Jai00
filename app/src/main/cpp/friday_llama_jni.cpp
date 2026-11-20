#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>
#include <algorithm>
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

constexpr int RC_OK = 0;
constexpr int RC_LOAD_FAILED = -1;
constexpr int RC_CONTEXT_OOM = -2;
constexpr int RC_INVALID_INPUT = -3;
constexpr int RC_VOCAB_MISSING = -4;
constexpr int RC_TOKENIZE_FAILED = -5;
constexpr int RC_DECODE_PROMPT_FAILED = -6;
constexpr int RC_DECODE_GEN_FAILED = -7;
constexpr int RC_ABORTED = -8;
constexpr int RC_CONTEXT_EXHAUSTED = -9;
constexpr int RC_CALLBACK_ERROR = -10;

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

bool tokenize_text(
        const llama_vocab * vocab,
        const std::string & text,
        std::vector<llama_token> & out_tokens) {
    out_tokens.clear();
    if (text.empty()) {
        return true;
    }
    int32_t n_est = -llama_tokenize(
            vocab, text.c_str(), static_cast<int32_t>(text.size()),
            nullptr, 0, false, true
    );
    if (n_est <= 0) {
        n_est = static_cast<int32_t>(text.size()) + 16;
    }
    out_tokens.resize(static_cast<size_t>(n_est));
    int32_t n_tok = llama_tokenize(
            vocab, text.c_str(), static_cast<int32_t>(text.size()),
            out_tokens.data(), static_cast<int32_t>(out_tokens.size()), false, true
    );
    if (n_tok < 0) {
        out_tokens.resize(static_cast<size_t>(-n_tok));
        n_tok = llama_tokenize(
                vocab, text.c_str(), static_cast<int32_t>(text.size()),
                out_tokens.data(), static_cast<int32_t>(out_tokens.size()), false, true
        );
    }
    if (n_tok < 0) {
        out_tokens.clear();
        return false;
    }
    out_tokens.resize(static_cast<size_t>(n_tok));
    return true;
}

// Preserve `<bos><|system|>...<|end_of_turn|>\n` prefix separately from conversation body.
void split_system_prefix(
        const std::string & prompt,
        std::string & out_system_prefix,
        std::string & out_dialog_body) {
    const std::string sys_tag = "<|system|>";
    const std::string eot_tag = "<|end_of_turn|>";
    size_t sys_pos = prompt.find(sys_tag);
    if (sys_pos != std::string::npos) {
        size_t eot_pos = prompt.find(eot_tag, sys_pos);
        if (eot_pos != std::string::npos) {
            size_t split_at = eot_pos + eot_tag.size();
            if (split_at < prompt.size() && prompt[split_at] == '\n') {
                split_at += 1;
            }
            out_system_prefix = prompt.substr(0, split_at);
            out_dialog_body = prompt.substr(split_at);
            return;
        }
    }
    out_system_prefix.clear();
    out_dialog_body = prompt;
}

// Convert UTF-8 bytes in `pending` into UTF-16 `jchar` vector while keeping
// any incomplete trailing multi-byte UTF-8 sequence inside `pending` for next token.
std::vector<jchar> utf8_flush_complete_utf16(std::string & pending, bool flush_remaining = false) {
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
            if (i + len > n) {
                if (flush_remaining) { out.push_back(0xFFFD); i = n; }
                break;
            }
            cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(pending[i + 1]) & 0x3F);
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
            if (i + len > n) {
                if (flush_remaining) { out.push_back(0xFFFD); i = n; }
                break;
            }
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<unsigned char>(pending[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(pending[i + 2]) & 0x3F);
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
            if (i + len > n) {
                if (flush_remaining) { out.push_back(0xFFFD); i = n; }
                break;
            }
            cp = ((c & 0x07) << 18) |
                 ((static_cast<unsigned char>(pending[i + 1]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(pending[i + 2]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(pending[i + 3]) & 0x3F);
        } else {
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

enum class EmitResult {
    CONTINUE = 0,
    STOP_REQUESTED = 1,
    JNI_EXCEPTION = 2
};

EmitResult emit_utf16_piece(
        JNIEnv * env,
        jobject callback,
        jmethodID on_token_mid,
        std::string & pending,
        bool flush_remaining = false) {
    auto utf16 = utf8_flush_complete_utf16(pending, flush_remaining);
    if (utf16.empty()) return EmitResult::CONTINUE;
    jstring j_piece = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
    if (j_piece == nullptr) return EmitResult::JNI_EXCEPTION;
    jboolean cont = env->CallBooleanMethod(callback, on_token_mid, j_piece);
    env->DeleteLocalRef(j_piece);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return EmitResult::JNI_EXCEPTION;
    }
    return cont == JNI_TRUE ? EmitResult::CONTINUE : EmitResult::STOP_REQUESTED;
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

    if (model_path_jstr == nullptr) {
        return RC_LOAD_FAILED;
    }

    if (!g_engine.backend_initialized) {
        llama_backend_init();
        g_engine.backend_initialized = true;
    }

    free_loaded_locked();
    g_engine.abort_flag.store(false);

    const char * model_path = env->GetStringUTFChars(model_path_jstr, nullptr);
    if (model_path == nullptr) return RC_LOAD_FAILED;

    struct stat st{};
    if (model_path[0] == '\0' || stat(model_path, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
        LOGE("Invalid or missing GGUF file path");
        env->ReleaseStringUTFChars(model_path_jstr, model_path);
        return RC_LOAD_FAILED;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-safe mobile baseline
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * loaded_model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(model_path_jstr, model_path);

    if (loaded_model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return RC_LOAD_FAILED;
    }

    const int32_t safe_ctx = std::clamp(static_cast<int32_t>(context_size), 512, 8192);
    const int32_t safe_threads = std::clamp(static_cast<int32_t>(threads), 1, 8);
    const uint32_t safe_batch = static_cast<uint32_t>(std::min(512, safe_ctx));

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(safe_ctx);
    cparams.n_batch = safe_batch;
    cparams.n_ubatch = std::min(static_cast<uint32_t>(256), safe_batch);
    cparams.n_threads = safe_threads;
    cparams.n_threads_batch = safe_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    llama_context * ctx = llama_init_from_model(loaded_model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed for n_ctx=%d", safe_ctx);
        llama_model_free(loaded_model);
        return RC_CONTEXT_OOM;
    }

    g_engine.model = loaded_model;
    g_engine.ctx = ctx;
    g_engine.context_size = safe_ctx;
    g_engine.threads = safe_threads;

    char desc_buf[256];
    llama_model_desc(loaded_model, desc_buf, sizeof(desc_buf));
    LOGI("Loaded Dhīr model: %s (n_ctx=%d, threads=%d)", desc_buf, safe_ctx, safe_threads);
    return RC_OK;
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
        return RC_LOAD_FAILED;
    }
    if (prompt_jstr == nullptr || callback == nullptr) {
        return RC_INVALID_INPUT;
    }

    g_engine.abort_flag.store(false);
    llama_memory_clear(llama_get_memory(g_engine.ctx), true);

    jclass cb_cls = env->GetObjectClass(callback);
    if (cb_cls == nullptr) return RC_CALLBACK_ERROR;
    jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cb_cls);
    if (on_token_mid == nullptr) return RC_CALLBACK_ERROR;

    const char * prompt_c = env->GetStringUTFChars(prompt_jstr, nullptr);
    if (prompt_c == nullptr) return RC_INVALID_INPUT;
    std::string prompt(prompt_c);
    env->ReleaseStringUTFChars(prompt_jstr, prompt_c);

    if (prompt.empty()) {
        return RC_INVALID_INPUT;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_engine.model);
    if (vocab == nullptr) return RC_VOCAB_MISSING;

    // Tokenize system prefix and conversation body separately so system instructions are NEVER truncated.
    std::string sys_prefix;
    std::string dialog_body;
    split_system_prefix(prompt, sys_prefix, dialog_body);

    std::vector<llama_token> sys_tokens;
    std::vector<llama_token> body_tokens;
    if (!tokenize_text(vocab, sys_prefix, sys_tokens) ||
        !tokenize_text(vocab, dialog_body, body_tokens)) {
        return RC_TOKENIZE_FAILED;
    }

    const int32_t min_gen_headroom = std::clamp(g_engine.context_size / 8, 64, 256);
    const int32_t max_prompt_tokens = g_engine.context_size - min_gen_headroom;
    if (max_prompt_tokens <= 0 || static_cast<int32_t>(sys_tokens.size()) >= max_prompt_tokens) {
        LOGE("System prompt tokens (%zu) exceed safe context budget (%d)", sys_tokens.size(), max_prompt_tokens);
        return RC_CONTEXT_EXHAUSTED;
    }

    const size_t body_budget = static_cast<size_t>(max_prompt_tokens) - sys_tokens.size();
    if (body_tokens.size() > body_budget) {
        const size_t drop = body_tokens.size() - body_budget;
        LOGI("Trimming %zu oldest dialog tokens while preserving %zu system tokens", drop, sys_tokens.size());
        body_tokens.erase(body_tokens.begin(), body_tokens.begin() + static_cast<ptrdiff_t>(drop));
    }

    std::vector<llama_token> tokens;
    tokens.reserve(sys_tokens.size() + body_tokens.size());
    tokens.insert(tokens.end(), sys_tokens.begin(), sys_tokens.end());
    tokens.insert(tokens.end(), body_tokens.begin(), body_tokens.end());

    const int32_t n_prompt = static_cast<int32_t>(tokens.size());
    if (n_prompt <= 0) {
        return RC_TOKENIZE_FAILED;
    }

    const int32_t chunk_size = std::min(256, g_engine.context_size);
    llama_batch batch = llama_batch_init(chunk_size, 0, 1);

    int32_t cur_pos = 0;
    for (int32_t offset = 0; offset < n_prompt; offset += chunk_size) {
        if (g_engine.abort_flag.load()) {
            llama_batch_free(batch);
            return RC_ABORTED;
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
            return RC_DECODE_PROMPT_FAILED;
        }
        cur_pos += count;
    }

    const int32_t available_gen = g_engine.context_size - cur_pos - 1;
    const int32_t safe_max_gen = std::min(static_cast<int32_t>(max_tokens), available_gen);
    if (safe_max_gen <= 0) {
        LOGE("No KV headroom left for generation (cur_pos=%d, n_ctx=%d)", cur_pos, g_engine.context_size);
        llama_batch_free(batch);
        return RC_CONTEXT_EXHAUSTED;
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
    int32_t final_rc = RC_OK;

    for (int32_t step = 0; step < safe_max_gen; ++step) {
        if (g_engine.abort_flag.load()) {
            final_rc = RC_ABORTED;
            break;
        }

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

        EmitResult emit_res = emit_utf16_piece(env, callback, on_token_mid, pending_utf8, false);
        if (emit_res == EmitResult::STOP_REQUESTED) {
            if (g_engine.abort_flag.load()) {
                final_rc = RC_ABORTED;
            }
            break;
        }
        if (emit_res == EmitResult::JNI_EXCEPTION) {
            final_rc = RC_CALLBACK_ERROR;
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
            final_rc = RC_DECODE_GEN_FAILED;
            break;
        }
        cur_pos++;
    }

    if (final_rc == RC_OK && !pending_utf8.empty() && !g_engine.abort_flag.load()) {
        EmitResult flush_res = emit_utf16_piece(env, callback, on_token_mid, pending_utf8, true);
        if (flush_res == EmitResult::JNI_EXCEPTION) {
            final_rc = RC_CALLBACK_ERROR;
        }
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    return g_engine.abort_flag.load() ? RC_ABORTED : final_rc;
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

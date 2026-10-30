#include <jni.h>
#include <string>
#include <cstring>
#include "whisper.h"

namespace {

JavaVM *g_vm = nullptr;

// Escapes a UTF-8 string for embedding inside a JSON document.
std::string jsonEscape(const std::string &input) {
    std::string out;
    out.reserve(input.size() + 16);
    for (unsigned char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (c < 0x20) {
                    char buffer[8];
                    std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                    out += buffer;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

// Holds the JVM-side callback so whisper's C callback can reach Kotlin code.
struct SegmentCallbackHolder {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;      // global reference
    jmethodID method = nullptr;
    whisper_context *context = nullptr;
};

void newSegmentCallback(whisper_context *, whisper_state *, int n_new, void *user_data) {
    auto *holder = static_cast<SegmentCallbackHolder *>(user_data);
    if (holder == nullptr || holder->callback == nullptr || holder->method == nullptr ||
        holder->vm == nullptr || holder->context == nullptr || n_new <= 0) {
        return;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    if (holder->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (holder->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }

    const int total = whisper_full_n_segments(holder->context);
    for (int index = total - n_new; index < total; ++index) {
        if (index < 0) continue;
        // whisper.cpp exposes t0/t1 in centiseconds (10 ms units).
        const int64_t t0Ms = whisper_full_get_segment_t0(holder->context, index) * 10;
        const int64_t t1Ms = whisper_full_get_segment_t1(holder->context, index) * 10;
        const char *text = whisper_full_get_segment_text(holder->context, index);
        if (text == nullptr) continue;

        jstring jtext = env->NewStringUTF(text);
        if (jtext == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue;
        }
        env->CallVoidMethod(holder->callback, holder->method, jtext,
                            static_cast<jlong>(t0Ms), static_cast<jlong>(t1Ms));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(jtext);
    }

    if (attached) {
        holder->vm->DetachCurrentThread();
    }
}

} // namespace

extern "C" {

jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_kidasniger_learn_WhisperLib_initContext(JNIEnv *env, jobject, jstring path) {
    return Java_com_kidasniger_learn_WhisperLib_initContextEx(env, nullptr, path, JNI_FALSE);
}

JNIEXPORT jlong JNICALL
Java_com_kidasniger_learn_WhisperLib_initContextEx(JNIEnv *env, jobject, jstring path, jboolean useGpu) {
    if (path == nullptr) {
        return 0;
    }

    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) {
        return 0;
    }

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = useGpu == JNI_TRUE;
    whisper_context *context = whisper_init_from_file_with_params(p, params);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_kidasniger_learn_WhisperLib_freeContext(JNIEnv *, jobject, jlong pointer) {
    if (pointer != 0) {
        whisper_free(reinterpret_cast<whisper_context *>(pointer));
    }
}

JNIEXPORT jstring JNICALL
Java_com_kidasniger_learn_WhisperLib_transcribe(
    JNIEnv *env,
    jobject,
    jlong pointer,
    jfloatArray audio,
    jstring language,
    jint threads
) {
    // Legacy plain-text transcription kept for compatibility.
    auto *context = reinterpret_cast<whisper_context *>(pointer);
    if (context == nullptr || audio == nullptr || language == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize sampleCount = env->GetArrayLength(audio);
    if (sampleCount <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF("");
    }

    const char *languageCode = env->GetStringUTFChars(language, nullptr);
    if (languageCode == nullptr) {
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        return env->NewStringUTF("");
    }

    std::string output;
    try {
        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.language = languageCode;
        params.n_threads = threads > 0 ? threads : 1;
        params.no_context = true;

        whisper_reset_timings(context);
        const int result = whisper_full(context, params, samples, sampleCount);
        if (result != 0) {
            env->ReleaseStringUTFChars(language, languageCode);
            env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
            jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
            if (exceptionClass != nullptr) {
                env->ThrowNew(exceptionClass, "Whisper n'a pas pu traiter cet audio.");
            }
            return nullptr;
        }

        const int segments = whisper_full_n_segments(context);
        for (int index = 0; index < segments; ++index) {
            const char *segment = whisper_full_get_segment_text(context, index);
            if (segment != nullptr) {
                output += segment;
            }
        }
    } catch (...) {
        env->ReleaseStringUTFChars(language, languageCode);
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "Erreur interne pendant la transcription.");
        }
        return nullptr;
    }

    env->ReleaseStringUTFChars(language, languageCode);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_kidasniger_learn_WhisperLib_transcribeDetailed(
    JNIEnv *env,
    jobject,
    jlong pointer,
    jfloatArray audio,
    jstring language,
    jint threads,
    jint beamSize,
    jfloat temperature,
    jstring initialPrompt,
    jobject callback
) {
    auto *context = reinterpret_cast<whisper_context *>(pointer);
    if (context == nullptr || audio == nullptr || language == nullptr) {
        return env->NewStringUTF("{\"segments\":[]}");
    }

    const jsize sampleCount = env->GetArrayLength(audio);
    if (sampleCount <= 0) {
        return env->NewStringUTF("{\"segments\":[]}");
    }

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF("{\"segments\":[]}");
    }

    const char *languageCode = env->GetStringUTFChars(language, nullptr);
    if (languageCode == nullptr) {
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        return env->NewStringUTF("{\"segments\":[]}");
    }

    const char *prompt = nullptr;
    if (initialPrompt != nullptr) {
        prompt = env->GetStringUTFChars(initialPrompt, nullptr);
    }

    SegmentCallbackHolder holder;
    holder.vm = g_vm;
    holder.context = context;
    if (callback != nullptr && g_vm != nullptr) {
        jobject ref = env->NewGlobalRef(callback);
        if (ref != nullptr) {
            holder.callback = ref;
            jclass callbackClass = env->GetObjectClass(callback);
            if (callbackClass != nullptr) {
                holder.method = env->GetMethodID(callbackClass, "onNewSegment",
                                                 "(Ljava/lang/String;JJ)V");
            }
            if (holder.method == nullptr && env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    jstring result = nullptr;
    try {
        whisper_full_params params = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.language = languageCode;
        params.n_threads = threads > 0 ? threads : 1;
        params.no_context = true;
        params.temperature = temperature;
        if (beamSize > 1) {
            params.beam_search.beam_size = beamSize;
        }
        if (prompt != nullptr && prompt[0] != '\0') {
            params.initial_prompt = prompt;
        }
        if (holder.callback != nullptr && holder.method != nullptr) {
            params.on_new_segment = newSegmentCallback;
            params.on_new_segment_user_data = &holder;
        }

        whisper_reset_timings(context);
        const int code = whisper_full(context, params, samples, sampleCount);
        if (code != 0) {
            jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
            if (exceptionClass != nullptr) {
                env->ThrowNew(exceptionClass, "Whisper n'a pas pu traiter cet audio.");
            }
            result = nullptr;
        } else {
            std::string json = "{\"segments\":[";
            const int segments = whisper_full_n_segments(context);
            for (int index = 0; index < segments; ++index) {
                const char *text = whisper_full_get_segment_text(context, index);
                const int64_t t0Ms = whisper_full_get_segment_t0(context, index) * 10;
                const int64_t t1Ms = whisper_full_get_segment_t1(context, index) * 10;
                if (index > 0) json += ',';
                json += "{\"t0\":";
                json += std::to_string(t0Ms);
                json += ",\"t1\":";
                json += std::to_string(t1Ms);
                json += ",\"text\":\"";
                json += jsonEscape(text != nullptr ? text : "");
                json += "\"}";
            }
            json += "]}";
            result = env->NewStringUTF(json.c_str());
        }
    } catch (...) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "Erreur interne pendant la transcription.");
        }
        result = nullptr;
    }

    if (holder.callback != nullptr) {
        env->DeleteGlobalRef(holder.callback);
        holder.callback = nullptr;
    }
    if (prompt != nullptr) {
        env->ReleaseStringUTFChars(initialPrompt, prompt);
    }
    env->ReleaseStringUTFChars(language, languageCode);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return result;
}

} // extern "C"

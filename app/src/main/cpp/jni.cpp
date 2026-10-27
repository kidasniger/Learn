#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kidasniger_learn_WhisperLib_initContext(JNIEnv *env, jobject, jstring path) {
    if (path == nullptr) {
        return 0;
    }

    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) {
        return 0;
    }

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
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

    env->ReleaseStringUTFChars(language, languageCode);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT jobjectArray JNICALL
Java_com_kidasniger_learn_WhisperLib_transcribeSegments(
    JNIEnv *env,
    jobject,
    jlong pointer,
    jfloatArray audio,
    jstring language,
    jint threads,
    jboolean highAccuracy
) {
    auto *context = reinterpret_cast<whisper_context *>(pointer);
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    if (context == nullptr || audio == nullptr || language == nullptr) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    const jsize sampleCount = env->GetArrayLength(audio);
    if (sampleCount <= 0) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (samples == nullptr) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }
    const char *languageCode = env->GetStringUTFChars(language, nullptr);
    if (languageCode == nullptr) {
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    whisper_sampling_strategy strategy = highAccuracy == JNI_TRUE
        ? WHISPER_SAMPLING_BEAM_SEARCH
        : WHISPER_SAMPLING_GREEDY;
    whisper_full_params params = whisper_full_default_params(strategy);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = languageCode;
    params.n_threads = threads > 0 ? threads : 1;
    params.no_context = true;
    if (highAccuracy == JNI_TRUE) {
        params.beam_search.beam_size = 5;
    }

    whisper_reset_timings(context);
    const int status = whisper_full(context, params, samples, sampleCount);
    env->ReleaseStringUTFChars(language, languageCode);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (status != 0) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "Whisper n'a pas pu traiter ce segment audio.");
        }
        return nullptr;
    }

    const int segmentCount = whisper_full_n_segments(context);
    jobjectArray result = env->NewObjectArray(segmentCount, stringClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    for (int index = 0; index < segmentCount; ++index) {
        const char *text = whisper_full_get_segment_text(context, index);
        if (text == nullptr) text = "";
        const int64_t startMs = whisper_full_get_segment_t0(context, index) * 10;
        const int64_t endMs = whisper_full_get_segment_t1(context, index) * 10;
        std::string row = std::to_string(startMs) + "\x1f" +
                          std::to_string(endMs) + "\x1f" + text;
        jstring value = env->NewStringUTF(row.c_str());
        if (value == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

}

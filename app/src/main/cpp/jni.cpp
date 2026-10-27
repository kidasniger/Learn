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

}

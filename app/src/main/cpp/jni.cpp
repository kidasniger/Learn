#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kidasniger_learn_WhisperLib_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_kidasniger_learn_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr) whisper_free((whisper_context *) ptr);
}

JNIEXPORT jstring JNICALL
Java_com_kidasniger_learn_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ptr,
                                                jfloatArray audio, jstring lang, jint threads) {
    auto *ctx = (whisper_context *) ptr;
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    jsize n = env->GetArrayLength(audio);
    const char *l = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.language = l;
    p.n_threads = threads;
    p.no_context = true;

    std::string out;
    if (whisper_full(ctx, p, data, n) == 0) {
        int ns = whisper_full_n_segments(ctx);
        for (int i = 0; i < ns; i++) out += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}

}

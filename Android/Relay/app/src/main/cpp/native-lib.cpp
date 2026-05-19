#include <jni.h>
#include <android/log.h>
#include <opus.h>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OPUS", __VA_ARGS__)

static OpusDecoder* get_decoder(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativePtr", "J");
    return (OpusDecoder*)env->GetLongField(thiz, fid);
}

static void set_decoder(JNIEnv* env, jobject thiz, OpusDecoder* dec) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativePtr", "J");
    env->SetLongField(thiz, fid, (jlong)dec);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_relay_OpusDecoder_init(JNIEnv* env, jobject thiz, jint sampleRate, jint channels) {
    int error;
    OpusDecoder* decoder = opus_decoder_create(sampleRate, channels, &error);
    if (error != OPUS_OK) {
        LOGE("Failed to create Opus decoder: %d", error);
        return JNI_FALSE;
    }
    set_decoder(env, thiz, decoder);

    // Store channels in the Kotlin object for use in decode
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "channels", "I");
    env->SetIntField(thiz, fid, channels);

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_relay_OpusDecoder_decode(JNIEnv* env, jobject thiz, jbyteArray input) {
    OpusDecoder* decoder = get_decoder(env, thiz);
    if (!decoder) return nullptr;

    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "channels", "I");
    int channels = env->GetIntField(thiz, fid);

    jsize packetLen = env->GetArrayLength(input);

    // The server sends a PacketHeader (12 bytes: 4 seq + 8 timestamp)
    // We need to skip this header to get to the actual Opus data.
    const int HEADER_SIZE = 12;
    if (packetLen <= HEADER_SIZE) {
        return nullptr;
    }

    jbyte* packetData = env->GetByteArrayElements(input, nullptr);
    const unsigned char* opusData = (const unsigned char*)(packetData + HEADER_SIZE);
    int opusDataLen = packetLen - HEADER_SIZE;

    // Use a maximum frame size of 120ms (5760 samples at 48kHz).
    const int MAX_SAMPLES = 5760;
    std::vector<opus_int16> outData(MAX_SAMPLES * channels);

    int samples = opus_decode(decoder, opusData, opusDataLen, outData.data(), MAX_SAMPLES, 0);
    env->ReleaseByteArrayElements(input, packetData, JNI_ABORT);

    if (samples < 0) {
        LOGE("Opus decode error: %d (packet size: %d)", samples, packetLen);
        return nullptr;
    }

    jsize outBytes = (jsize)(samples * channels * sizeof(short));
    jbyteArray output = env->NewByteArray(outBytes);
    env->SetByteArrayRegion(output, 0, outBytes, (jbyte*)outData.data());

    return output;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_relay_OpusDecoder_release(JNIEnv* env, jobject thiz) {
    OpusDecoder* decoder = get_decoder(env, thiz);
    if (decoder) {
        opus_decoder_destroy(decoder);
        set_decoder(env, thiz, nullptr);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_relay_OpusTest_isLoaded(JNIEnv*, jobject) {
    LOGE("Opus build test running...");
    OpusDecoder* dec;
    int err;
    dec = opus_decoder_create(48000, 2, &err);
    if (err != OPUS_OK) {
        LOGE("Opus init FAILED");
        return false;
    }
    opus_decoder_destroy(dec);
    LOGE("Opus loaded SUCCESSFULLY");
    return true;
}

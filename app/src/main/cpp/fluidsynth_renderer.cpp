#include <jni.h>

#include <string>
#include <vector>

#include <android/log.h>
#include <fluidsynth.h>

namespace {

constexpr const char *kLogTag = "MLDPlayerSF2";

struct SynthHandle {
    fluid_settings_t *settings = nullptr;
    fluid_synth_t *synth = nullptr;
};

std::string JStringToUtf8(JNIEnv *env, jstring value) {
    const char *chars;
    std::string result;

    if (value == nullptr) {
        return result;
    }
    chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return result;
    }
    result.assign(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring MakeError(JNIEnv *env, const std::string &message) {
    return env->NewStringUTF(message.c_str());
}

SynthHandle *FromHandle(jlong handle) {
    return reinterpret_cast<SynthHandle *>(handle);
}

void ReleaseHandle(SynthHandle *handle) {
    if (handle == nullptr) {
        return;
    }
    if (handle->synth != nullptr) {
        delete_fluid_synth(handle->synth);
        handle->synth = nullptr;
    }
    if (handle->settings != nullptr) {
        delete_fluid_settings(handle->settings);
        handle->settings = nullptr;
    }
    delete handle;
}

std::string SendEventToSynth(
        fluid_synth_t *synth,
        int status,
        int channel,
        int data1,
        int data2) {
    int command = status & 0xF0;
    int result = FLUID_OK;

    if (synth == nullptr) {
        return "SF2 synth unavailable";
    }

    switch (command) {
        case 0x80:
            result = fluid_synth_noteoff(synth, channel & 0x0F, data1 & 0x7F);
            break;
        case 0x90:
            if ((data2 & 0x7F) == 0) {
                result = fluid_synth_noteoff(synth, channel & 0x0F, data1 & 0x7F);
            } else {
                result = fluid_synth_noteon(synth, channel & 0x0F, data1 & 0x7F, data2 & 0x7F);
            }
            break;
        case 0xB0:
            result = fluid_synth_cc(synth, channel & 0x0F, data1 & 0x7F, data2 & 0x7F);
            break;
        case 0xC0:
            result = fluid_synth_program_change(synth, channel & 0x0F, data1 & 0x7F);
            if (result != FLUID_OK) {
                fluid_synth_bank_select(synth, channel & 0x0F, 0);
                result = fluid_synth_program_change(synth, channel & 0x0F, data1 & 0x7F);
            }
            if (result != FLUID_OK) {
                result = fluid_synth_program_change(synth, channel & 0x0F, 0);
            }
            break;
        case 0xE0: {
            int bend = ((data2 & 0x7F) << 7) | (data1 & 0x7F);
            result = fluid_synth_pitch_bend(synth, channel & 0x0F, bend);
            break;
        }
        default:
            return "";
    }

    if (result != FLUID_OK) {
        __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Ignoring FluidSynth event failure: status=0x%02X channel=%d data1=%d data2=%d",
                status & 0xFF,
                channel & 0x0F,
                data1 & 0x7F,
                data2 & 0x7F);
        return "";
    }
    return "";
}

}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_magstic_mldplayer_NativeSf2Synth_nativeCreate(
        JNIEnv *env,
        jclass,
        jstring soundFontPath,
        jint sampleRate) {
    std::string sf2Path = JStringToUtf8(env, soundFontPath);
    SynthHandle *handle = nullptr;

    if (sf2Path.empty()) {
        return 0L;
    }

    handle = new SynthHandle();
    handle->settings = new_fluid_settings();
    if (handle->settings == nullptr) {
        ReleaseHandle(handle);
        return 0L;
    }
    fluid_settings_setnum(handle->settings, "synth.sample-rate", static_cast<double>(sampleRate));
    fluid_settings_setint(handle->settings, "synth.audio-channels", 1);
    fluid_settings_setint(handle->settings, "synth.audio-groups", 1);

    handle->synth = new_fluid_synth(handle->settings);
    if (handle->synth == nullptr) {
        ReleaseHandle(handle);
        return 0L;
    }
    if (fluid_synth_sfload(handle->synth, sf2Path.c_str(), 1) == FLUID_FAILED) {
        ReleaseHandle(handle);
        return 0L;
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_magstic_mldplayer_NativeSf2Synth_nativeSendEvent(
        JNIEnv *env,
        jclass,
        jlong handleValue,
        jint status,
        jint channel,
        jint data1,
        jint data2) {
    SynthHandle *handle = FromHandle(handleValue);
    std::string error = SendEventToSynth(handle == nullptr ? nullptr : handle->synth, status, channel, data1, data2);

    if (error.empty()) {
        return nullptr;
    }
    return MakeError(env, error);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_magstic_mldplayer_NativeSf2Synth_nativeRender(
        JNIEnv *env,
        jclass,
        jlong handleValue,
        jshortArray buffer,
        jint offsetShorts,
        jint frameCount) {
    SynthHandle *handle = FromHandle(handleValue);
    jshort *rawBuffer;
    int totalShorts;

    if (handle == nullptr || handle->synth == nullptr) {
        return MakeError(env, "SF2 synth unavailable");
    }
    if (buffer == nullptr || frameCount <= 0) {
        return nullptr;
    }
    totalShorts = env->GetArrayLength(buffer);
    if (offsetShorts < 0 || offsetShorts + (frameCount * 2) > totalShorts) {
        return MakeError(env, "SF2 render buffer too small");
    }

    rawBuffer = env->GetShortArrayElements(buffer, nullptr);
    if (rawBuffer == nullptr) {
        return MakeError(env, "Cannot lock SF2 render buffer");
    }

    if (fluid_synth_write_s16(
            handle->synth,
            frameCount,
            rawBuffer + offsetShorts,
            0,
            2,
            rawBuffer + offsetShorts + 1,
            0,
            2) != FLUID_OK) {
        env->ReleaseShortArrayElements(buffer, rawBuffer, 0);
        return MakeError(env, "Cannot render SF2 audio");
    }

    env->ReleaseShortArrayElements(buffer, rawBuffer, 0);
    return nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_magstic_mldplayer_NativeSf2Synth_nativeRelease(
        JNIEnv *,
        jclass,
        jlong handleValue) {
    ReleaseHandle(FromHandle(handleValue));
}

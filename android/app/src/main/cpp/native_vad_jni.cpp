#include <jni.h>
#include <stdint.h>
#include <cmath>
#include <vector>

#include "rnnoise.h"
#include "webrtc/common_audio/vad/include/webrtc_vad.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_bbreplace_WebRtcVadNative_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jint mode) {
  VadInst* handle = WebRtcVad_Create();
  if (handle == nullptr) {
    return 0;
  }
  if (WebRtcVad_Init(handle) != 0) {
    WebRtcVad_Free(handle);
    return 0;
  }
  if (WebRtcVad_set_mode(handle, mode) != 0) {
    WebRtcVad_Free(handle);
    return 0;
  }
  return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_example_bbreplace_WebRtcVadNative_nativeRelease(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle_ptr) {
  if (handle_ptr == 0) {
    return;
  }
  WebRtcVad_Free(reinterpret_cast<VadInst*>(handle_ptr));
}

JNIEXPORT jint JNICALL
Java_com_example_bbreplace_WebRtcVadNative_nativeProcessPcm16(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle_ptr,
    jint sample_rate,
    jbyteArray frame_bytes,
    jint frame_samples) {
  if (handle_ptr == 0 || frame_bytes == nullptr || frame_samples <= 0) {
    return -1;
  }

  const jsize byte_count = env->GetArrayLength(frame_bytes);
  if (byte_count < frame_samples * 2) {
    return -1;
  }

  std::vector<jbyte> bytes(byte_count);
  env->GetByteArrayRegion(frame_bytes, 0, byte_count, bytes.data());

  std::vector<int16_t> samples(frame_samples);
  for (int i = 0; i < frame_samples; ++i) {
    const uint8_t low = static_cast<uint8_t>(bytes[i * 2]);
    const int8_t high = static_cast<int8_t>(bytes[i * 2 + 1]);
    samples[i] = static_cast<int16_t>((static_cast<int16_t>(high) << 8) | low);
  }

  return WebRtcVad_Process(
      reinterpret_cast<VadInst*>(handle_ptr),
      sample_rate,
      samples.data(),
      static_cast<size_t>(frame_samples));
}

JNIEXPORT jlong JNICALL
Java_com_example_bbreplace_RnNoiseNative_nativeCreate(
    JNIEnv* /* env */,
    jobject /* thiz */) {
  DenoiseState* state = rnnoise_create(nullptr);
  return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_com_example_bbreplace_RnNoiseNative_nativeRelease(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle_ptr) {
  if (handle_ptr == 0) {
    return;
  }
  rnnoise_destroy(reinterpret_cast<DenoiseState*>(handle_ptr));
}

static inline float pcm16_to_float(int16_t sample) {
  return static_cast<float>(sample);
}

static inline int16_t float_to_pcm16(float sample) {
  if (sample > 32767.0f) {
    return 32767;
  }
  if (sample < -32768.0f) {
    return -32768;
  }
  return static_cast<int16_t>(std::lrint(sample));
}

static void decode_pcm16(const jbyte* bytes, int16_t* samples, int sample_count) {
  for (int i = 0; i < sample_count; ++i) {
    const uint8_t low = static_cast<uint8_t>(bytes[i * 2]);
    const int8_t high = static_cast<int8_t>(bytes[i * 2 + 1]);
    samples[i] = static_cast<int16_t>((static_cast<int16_t>(high) << 8) | low);
  }
}

static void encode_pcm16(const int16_t* samples, jbyte* bytes, int sample_count) {
  for (int i = 0; i < sample_count; ++i) {
    const uint16_t value = static_cast<uint16_t>(samples[i]);
    bytes[i * 2] = static_cast<jbyte>(value & 0xFF);
    bytes[i * 2 + 1] = static_cast<jbyte>((value >> 8) & 0xFF);
  }
}

static void upsample_160_to_480(const int16_t* in, float* out) {
  for (int i = 0; i < 160; ++i) {
    const float current = pcm16_to_float(in[i]);
    const float next = pcm16_to_float(i + 1 < 160 ? in[i + 1] : in[i]);
    out[i * 3] = current;
    out[i * 3 + 1] = current + (next - current) / 3.0f;
    out[i * 3 + 2] = current + (next - current) * 2.0f / 3.0f;
  }
}

static void downsample_480_to_160(const float* in, int16_t* out) {
  for (int i = 0; i < 160; ++i) {
    const float averaged = (in[i * 3] + in[i * 3 + 1] + in[i * 3 + 2]) / 3.0f;
    out[i] = float_to_pcm16(averaged);
  }
}

JNIEXPORT jboolean JNICALL
Java_com_example_bbreplace_RnNoiseNative_nativeProcess16kPcm16InPlace(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle_ptr,
    jbyteArray frame_bytes) {
  if (handle_ptr == 0 || frame_bytes == nullptr) {
    return JNI_FALSE;
  }

  const jsize byte_count = env->GetArrayLength(frame_bytes);
  constexpr int kInputSampleCount = 320;
  constexpr int kInputByteCount = kInputSampleCount * 2;
  constexpr int kHalfFrameSampleCount = 160;
  constexpr int kRnNoiseFrameSize = 480;
  if (byte_count != kInputByteCount) {
    return JNI_FALSE;
  }

  std::vector<jbyte> bytes(kInputByteCount);
  env->GetByteArrayRegion(frame_bytes, 0, byte_count, bytes.data());

  int16_t pcm_samples[kInputSampleCount];
  int16_t denoised_samples[kInputSampleCount];
  float rnnoise_input[kRnNoiseFrameSize];
  float rnnoise_output[kRnNoiseFrameSize];
  decode_pcm16(bytes.data(), pcm_samples, kInputSampleCount);

  DenoiseState* state = reinterpret_cast<DenoiseState*>(handle_ptr);
  for (int chunk = 0; chunk < 2; ++chunk) {
    const int16_t* chunk_in = &pcm_samples[chunk * kHalfFrameSampleCount];
    int16_t* chunk_out = &denoised_samples[chunk * kHalfFrameSampleCount];
    upsample_160_to_480(chunk_in, rnnoise_input);
    rnnoise_process_frame(state, rnnoise_output, rnnoise_input);
    downsample_480_to_160(rnnoise_output, chunk_out);
  }

  encode_pcm16(denoised_samples, bytes.data(), kInputSampleCount);
  env->SetByteArrayRegion(frame_bytes, 0, byte_count, bytes.data());
  return JNI_TRUE;
}

}

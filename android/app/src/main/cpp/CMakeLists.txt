cmake_minimum_required(VERSION 3.22.1)

project("speech_repeater_native")

file(GLOB WEBRTC_VAD_SOURCES
    webrtc/common_audio/vad/*.c
)

file(GLOB WEBRTC_SP_SOURCES
    webrtc/common_audio/signal_processing/*.c
)

list(FILTER WEBRTC_SP_SOURCES EXCLUDE REGEX ".*(_mips|_neon)\\.c$")

file(GLOB WEBRTC_THIRD_PARTY_SOURCES
    webrtc/common_audio/third_party/spl_sqrt_floor/*.c
)

list(FILTER WEBRTC_THIRD_PARTY_SOURCES EXCLUDE REGEX ".*_mips\\.c$")

set(RNNOISE_SOURCES
    rnnoise/src/denoise.c
    rnnoise/src/rnn.c
    rnnoise/src/pitch.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/nnet.c
    rnnoise/src/nnet_default.c
    rnnoise/src/parse_lpcnet_weights.c
    rnnoise/src/rnnoise_data.c
    rnnoise/src/rnnoise_tables.c
)

add_library(
    speech_repeater_native
    SHARED
    native_vad_jni.cpp
    ${WEBRTC_VAD_SOURCES}
    ${WEBRTC_SP_SOURCES}
    ${WEBRTC_THIRD_PARTY_SOURCES}
    ${RNNOISE_SOURCES}
)

target_include_directories(
    speech_repeater_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/webrtc
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src
)

target_compile_definitions(
    speech_repeater_native
    PRIVATE
    RNNOISE_BUILD
)

find_library(log-lib log)
find_library(math-lib m)

target_link_libraries(
    speech_repeater_native
    ${log-lib}
    ${math-lib}
)

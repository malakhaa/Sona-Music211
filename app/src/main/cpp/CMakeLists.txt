cmake_minimum_required(VERSION 3.22.1)
project("sona_audio_engine" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Locate system NDK libraries
find_library(log-lib log)
find_library(aaudio-lib aaudio)

add_library(
    sona_audio_engine
    SHARED
    native-audio-engine.cpp
)

# Optional third-party prebuilts (Oboe, SoX Resampler, FFmpeg, TagLib)
# Drop prebuilt headers & static libs into app/src/main/cpp/third_party/<abi>/ or enable Prefab
set(THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

if(EXISTS "${THIRD_PARTY_DIR}/oboe/include")
    target_include_directories(sona_audio_engine PRIVATE "${THIRD_PARTY_DIR}/oboe/include")
    target_compile_definitions(sona_audio_engine PRIVATE HAS_OBOE=1)
endif()

if(EXISTS "${THIRD_PARTY_DIR}/soxr/include")
    target_include_directories(sona_audio_engine PRIVATE "${THIRD_PARTY_DIR}/soxr/include")
    target_compile_definitions(sona_audio_engine PRIVATE HAS_SOXR=1)
endif()

if(EXISTS "${THIRD_PARTY_DIR}/ffmpeg/include")
    target_include_directories(sona_audio_engine PRIVATE "${THIRD_PARTY_DIR}/ffmpeg/include")
    target_compile_definitions(sona_audio_engine PRIVATE HAS_FFMPEG=1)
endif()

if(EXISTS "${THIRD_PARTY_DIR}/taglib/include")
    target_include_directories(sona_audio_engine PRIVATE "${THIRD_PARTY_DIR}/taglib/include")
    target_compile_definitions(sona_audio_engine PRIVATE HAS_TAGLIB=1)
endif()

target_compile_options(
    sona_audio_engine
    PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -Wall
    -Wextra
)

target_link_libraries(
    sona_audio_engine
    ${log-lib}
    ${aaudio-lib}
    android
    m
)

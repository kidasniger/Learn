cmake_minimum_required(VERSION 3.18)
project(learn_whisper)
set(CMAKE_CXX_STANDARD 17)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.zip
)
FetchContent_MakeAvailable(whisper)

add_library(whisper_jni SHARED jni.cpp)
target_link_libraries(whisper_jni whisper log android)
target_link_options(whisper_jni PRIVATE -Wl,-z,max-page-size=16384)

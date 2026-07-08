cmake_minimum_required(VERSION 3.22.1)

project(live2d_jni LANGUAGES CXX)

add_library(live2d_jni SHARED
    live2d_jni.cpp
)

target_compile_features(live2d_jni PRIVATE cxx_std_17)
target_compile_options(live2d_jni PRIVATE -fexceptions -frtti)

target_link_libraries(live2d_jni
    android
    log
    GLESv2
    dl
)

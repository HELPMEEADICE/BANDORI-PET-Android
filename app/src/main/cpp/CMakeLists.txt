cmake_minimum_required(VERSION 3.22.1)

project(bandoripet LANGUAGES CXX)

add_library(bandoripet SHARED live2d_jni.cpp)

find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)
find_library(log-lib log)

target_link_libraries(
    bandoripet
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
    ${log-lib}
)

target_compile_options(bandoripet PRIVATE $<$<CONFIG:Release>:-O3 -flto -DNDEBUG>)
target_link_options(bandoripet PRIVATE -Wl,-z,max-page-size=0x4000 $<$<CONFIG:Release>:-flto -Wl,--gc-sections -Wl,-s>)

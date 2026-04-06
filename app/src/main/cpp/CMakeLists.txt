cmake_minimum_required(VERSION 3.22.1)
project(mldplayer_native)

find_package(fluidsynth-android REQUIRED CONFIG)
find_library(log-lib log)

add_library(mldplayer_native SHARED
        fluidsynth_renderer.cpp)

target_link_libraries(mldplayer_native
        PRIVATE
        fluidsynth-android::fluidsynth
        ${log-lib})

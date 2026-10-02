cmake_minimum_required(VERSION 3.22.1)
project(applefiles CXX)

add_library(applefiles SHARED af_fs.cpp)

target_compile_features(applefiles PRIVATE cxx_std_17)
target_compile_options(applefiles PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -O2
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections)

# 16 KB page alignment: required from API 35 on, harmless before that.
target_link_options(applefiles PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384)
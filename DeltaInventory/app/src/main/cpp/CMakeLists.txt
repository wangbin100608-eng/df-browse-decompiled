cmake_minimum_required(VERSION 3.22)
project(di_vision)
set(CMAKE_CXX_STANDARD 17)

set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../../opencv_sdk/jni")
find_package(OpenCV REQUIRED)

add_library(di_vision SHARED vision_jni.cpp)
# OpenCV SDK 的 OpenCV_INCLUDE_DIRS 依赖标准 SDK 目录布局，
# 本项目镜像布局不同，显式指定实际头文件路径。
target_include_directories(di_vision PRIVATE "${CMAKE_SOURCE_DIR}/../../../opencv_sdk/jni/include")
target_link_libraries(di_vision ${OpenCV_LIBS} android log)

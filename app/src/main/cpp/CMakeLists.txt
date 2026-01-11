cmake_minimum_required(VERSION 3.10.2)
project("paddleocr_native")

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV 路径 (使用 Android NDK 自带的)
set(OpenCV_DIR "${ANDROID_NDK}/sources/third_party/opencv")

# 找到 OpenCV
find_package(OpenCV REQUIRED)
if(OpenCV_FOUND)
    message(STATUS "OpenCV found: ${OpenCV_VERSION}")
    include_directories(${OpenCV_INCLUDE_DIRS})
endif()

# 添加 Paddle-Lite 头文件路径
include_directories(
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}
    ${CMAKE_SOURCE_DIR}
)

# 添加源文件
add_library(
    Native
    SHARED
    native.cpp
    ocr_ppredictor.cpp
    ocr_crnn_process.cpp
    ocr_db_post_process.cpp
    ocr_cls_process.cpp
    ppredictor.cpp
    preprocess.cpp
)

# 链接库
target_link_libraries(
    Native
    ${OpenCV_LIBS}
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpaddle_light_api_shared.so
    log
    jnigraphics
    android
)

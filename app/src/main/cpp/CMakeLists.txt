cmake_minimum_required(VERSION 3.10.2)
project("paddleocr_native")

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV 路径 - 使用下载的 OpenCV SDK
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../OpenCV/sdk/native/jni")
message(STATUS "OpenCV directory: ${OpenCV_DIR}")

# 找到 OpenCV
find_package(OpenCV REQUIRED)
if(OpenCV_FOUND)
    message(STATUS "OpenCV found: ${OpenCV_VERSION}")
    message(STATUS "OpenCV libraries: ${OpenCV_LIBS}")
    include_directories(${OpenCV_INCLUDE_DIRS})
endif()

# 添加 Paddle-Lite 头文件路径 - 本地编译版本（兼容 NDK 29）
set(PaddleLite_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../PaddleLite")
include_directories(
    ${PaddleLite_DIR}/cxx/include
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/ocr
)

# 添加源文件 - 使用官方 demo 的完整 OCR 实现
add_library(
    ocr_native
    SHARED
    # 主 JNI 接口
    ocr/ocr_native.cpp
    # OCR 核心实现
    ocr/ocr_ppredictor.cpp
    ocr/ocr_crnn_process.cpp
    ocr/ocr_db_post_process.cpp
    ocr/ocr_cls_process.cpp
    # Paddle-Lite 预测器封装
    ocr/ppredictor.cpp
    ocr/predictor_input.cpp
    ocr/predictor_output.cpp
    ocr/preprocess.cpp
    # Clipper 库（用于多边形处理）
    ocr/ocr_clipper.cpp
)

# 链接本地编译的 PaddleLite v2.14 库（兼容 NDK 29）
add_library(
    paddle_lite_jni
    SHARED
    IMPORTED
)

set_target_properties(
    paddle_lite_jni
    PROPERTIES
    IMPORTED_LOCATION
    ${PaddleLite_DIR}/java/so/libpaddle_lite_jni.so
)

# 链接库
target_link_libraries(
    ocr_native
    paddle_lite_jni
    ${OpenCV_LIBS}
    log
    jnigraphics
    android
    GLESv2
    EGL
)

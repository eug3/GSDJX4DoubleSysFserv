cmake_minimum_required(VERSION 3.10.2)
project("native_lib")

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 链接库
target_link_libraries(
    native_lib
    log
    android
)

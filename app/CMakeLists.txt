cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C CXX)

add_subdirectory(../third_party/sevenzip ${CMAKE_CURRENT_BINARY_DIR}/sevenzip)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})

add_library(archive_jbinding SHARED
        src/main/jni/archive_jbinding.cpp)
set_target_properties(archive_jbinding PROPERTIES
        CXX_STANDARD 11
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF
        LINK_FLAGS "-Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384")
target_compile_options(archive_jbinding
        PRIVATE
        -Wall
        -Wextra
        -Werror
        -fexceptions
        -fno-rtti
        -fvisibility=hidden)
target_link_libraries(archive_jbinding PRIVATE sevenzip_headers)

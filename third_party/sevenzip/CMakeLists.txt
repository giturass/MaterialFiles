cmake_minimum_required(VERSION 3.6)

# The original, unmodified official release is kept as a source archive so clean/offline
# Android builds use the same engine without a network dependency or prebuilt native code.
set(SEVEN_ZIP_ARCHIVE "${CMAKE_CURRENT_SOURCE_DIR}/7z2604-src.tar.xz")
file(SHA256 "${SEVEN_ZIP_ARCHIVE}" SEVEN_ZIP_SHA256)
if(NOT SEVEN_ZIP_SHA256 STREQUAL
        "9691944c0fe0d01bb49373a704fb983fd33bc98b1738695179dfbf99ac1734f6")
    message(FATAL_ERROR "The official 7-Zip 26.04 source archive checksum does not match")
endif()
set(SEVEN_ZIP_SRC "${CMAKE_CURRENT_BINARY_DIR}/7zip-26.04")
if(NOT EXISTS "${SEVEN_ZIP_SRC}/.extracted")
    file(MAKE_DIRECTORY "${SEVEN_ZIP_SRC}")
    execute_process(COMMAND "${CMAKE_COMMAND}" -E tar xJf "${SEVEN_ZIP_ARCHIVE}"
            WORKING_DIRECTORY "${SEVEN_ZIP_SRC}" RESULT_VARIABLE SEVEN_ZIP_EXTRACT_RESULT)
    if(NOT SEVEN_ZIP_EXTRACT_RESULT EQUAL 0)
        message(FATAL_ERROR "Cannot extract the official 7-Zip source archive")
    endif()
    file(WRITE "${SEVEN_ZIP_SRC}/.extracted" "${SEVEN_ZIP_SHA256}\n")
endif()
set(JBINDING_SRC "${CMAKE_CURRENT_SOURCE_DIR}/jbinding-cpp")
include(sources.cmake)

# The property adapter and engine share the actual official COM declarations. In particular,
# neither uses the virtual IUnknown destructor from the old Android/p7zip ABI.
add_library(sevenzip_headers INTERFACE)
target_include_directories(sevenzip_headers INTERFACE
        "${SEVEN_ZIP_SRC}" "${SEVEN_ZIP_SRC}/CPP" "${SEVEN_ZIP_SRC}/C"
        "${SEVEN_ZIP_SRC}/CPP/Windows")
target_compile_definitions(sevenzip_headers INTERFACE
        _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE _REENTRANT ENV_UNIX
        BREAK_HANDLER UNICODE _UNICODE UNIX_USE_WIN_FILE)

add_library(7-Zip-JBinding SHARED ${SEVEN_ZIP_SOURCE_FILES} ${JBINDING_CPP_FILES})
target_include_directories(7-Zip-JBinding PRIVATE "${JBINDING_SRC}")
target_link_libraries(7-Zip-JBinding PRIVATE sevenzip_headers)
# Disable upstream's diagnostic/JNI test instrumentation in every application build type.
target_compile_definitions(7-Zip-JBinding PRIVATE NDEBUG)
target_compile_options(7-Zip-JBinding PRIVATE
        -O2 "$<$<COMPILE_LANGUAGE:CXX>:-fexceptions;-fno-rtti>" -fvisibility=hidden
        -ffunction-sections -fdata-sections -fstack-protector-strong)
set_target_properties(7-Zip-JBinding PROPERTIES
        CXX_STANDARD 11 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF
        LINK_FLAGS "-Wl,--gc-sections,--exclude-libs,ALL,--no-undefined,-z,relro,-z,now,-z,max-page-size=16384")

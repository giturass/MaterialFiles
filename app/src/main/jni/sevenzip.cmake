# The official 7z handler and codecs are compiled directly into the JNI library. No CLI,
# external codec loading, or handlers for formats already owned by libarchive are linked.
file(STRINGS "${CMAKE_CURRENT_LIST_DIR}/sevenzip_sources.list" SEVENZIP_SOURCE_NAMES)
set(SEVENZIP_SOURCES)
foreach(SEVENZIP_SOURCE ${SEVENZIP_SOURCE_NAMES})
    list(APPEND SEVENZIP_SOURCES "${CMAKE_CURRENT_LIST_DIR}/${SEVENZIP_SOURCE}")
endforeach()
add_library(sevenzip SHARED ${SEVENZIP_SOURCES})
set_target_properties(sevenzip PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES C_EXTENSIONS YES
        CXX_STANDARD 17 CXX_STANDARD_REQUIRED YES CXX_EXTENSIONS YES)
target_compile_definitions(sevenzip PRIVATE Z7_COM_USE_ATOMIC _FILE_OFFSET_BITS=64)
target_compile_options(sevenzip PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden
        -fstack-protector-strong -ffunction-sections -fdata-sections)
set_property(TARGET sevenzip APPEND_STRING PROPERTY LINK_FLAGS
        " -Wl,--no-undefined,--gc-sections,--exclude-libs,ALL,--wrap=pthread_create -Wl,-z,max-page-size=16384 -Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/sevenzip.map")
set_property(TARGET sevenzip APPEND PROPERTY LINK_DEPENDS
        "${CMAKE_CURRENT_LIST_DIR}/sevenzip.map")

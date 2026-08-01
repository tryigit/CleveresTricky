project(cleverestricky_external LANGUAGES CXX)

set(LSPLT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/LSPlt/lsplt/src/main/jni")
add_library(
    lsplt
    STATIC
    "${LSPLT_ROOT}/lsplt.cc"
    "${LSPLT_ROOT}/elf_util.cc"
)
target_include_directories(lsplt PUBLIC "${LSPLT_ROOT}/include")
target_include_directories(lsplt PRIVATE "${LSPLT_ROOT}")
target_link_libraries(lsplt PUBLIC my_logging log dl)

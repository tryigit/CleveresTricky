cmake_minimum_required(VERSION 3.22...3.31)
project(cleverestricky LANGUAGES C CXX)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_definitions(DEBUG_BUILD)
    add_compile_options(-O0 -g3 -fstack-protector-strong)
else()
    add_compile_options(
        -Oz
        -flto
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -fstack-protector-strong
        -D_FORTIFY_SOURCE=2
    )
    add_link_options(-flto -s -Wl,--exclude-libs,ALL)
endif()

add_compile_options(
    -Wall
    -Wextra
    -Werror
    -Wno-unused-parameter
    -fno-exceptions
    -fno-rtti
    -ffunction-sections
    -fdata-sections
)
add_link_options(
    -Wl,--gc-sections
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,noexecstack
)

add_library(my_logging STATIC logging/logging.cpp)
target_include_directories(my_logging PUBLIC logging/include)
target_link_libraries(my_logging PUBLIC log)

add_subdirectory(external)

# These small ABI stubs provide compile-time types. They are deliberately not
# packaged; the injected library resolves Android's platform libbinder/libutils.
add_library(utils SHARED binder/stub_utils.cpp)
target_include_directories(utils PUBLIC binder/include)

add_library(binder SHARED binder/stub_binder.cpp)
target_include_directories(binder PUBLIC binder/include)
target_link_libraries(binder PRIVATE utils)

add_executable(inject inject/main.cpp inject/utils.cpp)
target_link_libraries(inject PRIVATE lsplt my_logging dl)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET_TRIPLE "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET_TRIPLE "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupported Rust target ABI: ${ANDROID_ABI}")
endif()

add_library(rust_core STATIC IMPORTED)
set_target_properties(rust_core PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../rust/target/${RUST_TARGET_TRIPLE}/release/libcleverestricky_cbor_cose.a
)

add_library(${MODULE_NAME} SHARED binder_interceptor.cpp)
target_include_directories(${MODULE_NAME} PRIVATE ../../../../rust/cbor-cose/include)
target_link_libraries(${MODULE_NAME} PRIVATE log dl binder utils lsplt my_logging rust_core)

set_target_properties(binder PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")
set_target_properties(${MODULE_NAME} PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")

cmake_minimum_required(VERSION 3.22...3.31)
project(cleverestricky_injector LANGUAGES C CXX)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_definitions(DEBUG_BUILD)
    add_compile_options(-O0 -g)
else()
    add_compile_options(-Os -flto -ffunction-sections -fdata-sections)
    add_link_options(-flto -s -Wl,--gc-sections)
endif()

add_compile_options(
    -Wall
    -Wextra
    -Werror
    -Wno-unused-parameter
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

add_library(my_logging STATIC logging/logging.cpp)
target_include_directories(my_logging PUBLIC logging/include)
target_link_libraries(my_logging PUBLIC log)

add_subdirectory(external)

add_executable(inject inject/main.cpp inject/utils.cpp)
target_include_directories(inject PRIVATE inject logging/include)
target_link_libraries(inject PRIVATE lsplt my_logging log dl)

cmake_minimum_required(VERSION 3.22.1)

add_subdirectory(opus)

add_library(opusjni SHARED native-lib.cpp)

target_include_directories(opusjni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/opus/include
)

target_link_libraries(opusjni opus log)
cmake_minimum_required(VERSION 3.22.1)

project(steadyvault_camera_probe)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    steadyvault_camera_probe
    SHARED
    camera_metadata_probe.cpp
)

find_library(log-lib log)

target_link_libraries(
    steadyvault_camera_probe
    ${log-lib}
    dl
)

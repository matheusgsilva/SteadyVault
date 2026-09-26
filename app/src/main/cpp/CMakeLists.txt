cmake_minimum_required(VERSION 3.22.1)

project(steadyvault_camera_probe)

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

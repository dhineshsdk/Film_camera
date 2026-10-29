cmake_minimum_required(VERSION 3.22.1)

project("s24film_vulkan")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(vulkan-lib vulkan)
find_library(android-lib android)
find_library(log-lib log)
find_library(camera2-lib camera2ndk)
find_library(mediandk-lib mediandk)

add_library(s24film_vulkan SHARED
    vulkan_raw_pipeline.cpp
    gpu_merge.cpp
)

target_include_directories(s24film_vulkan PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(s24film_vulkan
    ${vulkan-lib}
    ${android-lib}
    ${log-lib}
    ${camera2-lib}
    ${mediandk-lib}
)

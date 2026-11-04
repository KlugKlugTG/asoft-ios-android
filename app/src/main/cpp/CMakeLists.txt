cmake_minimum_required(VERSION 3.22)
project(asoft_render C)

add_library(render SHARED render.c)
target_compile_options(render PRIVATE -O2 -Wall)
target_link_libraries(render android log EGL GLESv3)

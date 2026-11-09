cmake_minimum_required(VERSION 3.22)
project(asoft_render C)

add_library(render SHARED render.c)
target_compile_options(render PRIVATE -O2 -Wall)
target_link_libraries(render android log EGL GLESv3)

# the GPU check (GpuCheck.java, its own process): loads a Vulkan loader with dlopen, links none
add_library(gpucheck SHARED gpucheck.c)
target_compile_options(gpucheck PRIVATE -O2 -Wall)
target_link_libraries(gpucheck dl)

# the setup from Apple's native help (Unpack.java): Apple's LZFSE decoder (lzfse/, BSD-3-Clause) for AEA
# segments and IM4P payloads, and hole punching behind a decrypt
add_library(unpack SHARED unpack.c lzfse/lzfse_decode.c lzfse/lzfse_decode_base.c lzfse/lzfse_fse.c lzfse/lzvn_decode_base.c)
target_compile_options(unpack PRIVATE -O3 -Wall -Wno-unused-variable -Wno-unused-function)

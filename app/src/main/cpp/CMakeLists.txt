cmake_minimum_required(VERSION 3.22.1)
project("translive" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp source directory (to be cloned/placed here)
set(LLAMA_CPP_DIR "${CMAKE_SOURCE_DIR}/llama.cpp")

# Check if llama.cpp exists
if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    # Build llama.cpp as a static library
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)

    # Our JNI bridge library
    add_library(translive SHARED
        translive_jni.cpp
    )

    target_include_directories(translive PRIVATE
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
    )

    target_link_libraries(translive
        llama
        ggml
        common
        android
        log
    )
else()
    # Stub library when llama.cpp is not yet cloned
    message(WARNING "llama.cpp not found at ${LLAMA_CPP_DIR}. Building stub.")
    add_library(translive SHARED
        translive_jni_stub.cpp
    )
    target_link_libraries(translive android log)
endif()

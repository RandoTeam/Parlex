cmake_minimum_required(VERSION 3.14)
project("translive" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp source directory (to be cloned/placed here)
set(LLAMA_CPP_DIR "${CMAKE_SOURCE_DIR}/llama.cpp")
set(MNN_DIR "${CMAKE_SOURCE_DIR}/MNN")
# Reconfigure when the separately restored llama.cpp checkout appears or changes.
# Without this dependency, a clean clone can keep packaging the JNI stub after
# llama.cpp is restored because the previous CMake cache selected the stub path.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${LLAMA_CPP_DIR}/CMakeLists.txt")

# Check if llama.cpp exists
if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    # Build llama.cpp as a static library
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)  # pthreads > OpenMP on Android
    # GGUF GPU path for Qualcomm devices. This uses the upstream ggml OpenCL
    # backend and its Adreno kernels; it is not a separate inference engine.
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(OPENCL_HEADERS_DIR "${CMAKE_SOURCE_DIR}/third_party/OpenCL-Headers")
    set(OPENCL_LINK_STUB "${CMAKE_SOURCE_DIR}/opencl-linker/${ANDROID_ABI}/libOpenCL.so")
    if (NOT EXISTS "${OPENCL_HEADERS_DIR}/CL/cl.h" OR NOT EXISTS "${OPENCL_LINK_STUB}")
        message(FATAL_ERROR
            "OpenCL build prerequisites are missing. Initialise third_party/OpenCL-Headers "
            "and generate opencl-linker/${ANDROID_ABI}/libOpenCL.so with the documented setup script.")
    endif()
    set(OpenCL_INCLUDE_DIR "${OPENCL_HEADERS_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY "${OPENCL_LINK_STUB}" CACHE FILEPATH "" FORCE)
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)

    # Our JNI bridge library
    add_library(translive SHARED
        translive_jni.cpp
        ocr_mnn_jni.cpp
    )

    target_include_directories(translive PRIVATE
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
    )

    target_link_libraries(translive
        llama
        ggml
        android
        log
    )
else()
    # Stub library when llama.cpp is not yet cloned
    message(WARNING "llama.cpp not found at ${LLAMA_CPP_DIR}. Building stub.")
    add_library(translive SHARED
        translive_jni_stub.cpp
        ocr_mnn_jni.cpp
    )
    target_link_libraries(translive android log)
endif()

# MNN is restored separately for native builds, just like llama.cpp. Keep the
# capability bridge in every APK; the real implementation can only be enabled
# when the exact pinned checkout is present for this ABI.
if(EXISTS "${MNN_DIR}/CMakeLists.txt")
    # OCR-only MNN profile. Keep the runtime static inside libtranslive so the
    # APK has one native loading point and no second Java-facing .so.
    set(MNN_BUILD_MINI ON CACHE BOOL "" FORCE)
    set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
    set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
    set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
    set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
    set(MNN_BUILD_LLM OFF CACHE BOOL "" FORCE)
    set(MNN_OPENCL ON CACHE BOOL "" FORCE)
    set(MNN_VULKAN ON CACHE BOOL "" FORCE)
    set(MNN_ARM82 ON CACHE BOOL "" FORCE)
    set(MNN_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(MNN_USE_SYSTEM_LIB OFF CACHE BOOL "" FORCE)
    add_subdirectory(${MNN_DIR} mnn-build EXCLUDE_FROM_ALL)
    target_include_directories(translive PRIVATE ${MNN_DIR}/include)
    target_link_libraries(translive MNN)
    target_compile_definitions(translive PRIVATE PARLEX_MNN_LINKED=1)
    message(STATUS "MNN checkout detected and linked for OCR native integration.")
else()
    message(STATUS "MNN checkout not present; OCR bridge remains capability-only.")
endif()

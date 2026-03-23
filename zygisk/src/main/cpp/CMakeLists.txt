cmake_minimum_required(VERSION 3.10)

project(zygisk)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# String defines that need quoting on Windows
if(DEFINED INJECTED_PACKAGE_NAME_VAL)
    add_compile_definitions(INJECTED_PACKAGE_NAME="${INJECTED_PACKAGE_NAME_VAL}")
endif()
if(DEFINED MANAGER_PACKAGE_NAME_VAL)
    add_compile_definitions(MANAGER_PACKAGE_NAME="${MANAGER_PACKAGE_NAME_VAL}")
endif()

add_subdirectory(${VECTOR_ROOT}/native native)

add_library(${PROJECT_NAME} SHARED module.cpp ipc_bridge.cpp)
target_include_directories(${PROJECT_NAME} PUBLIC include)

target_link_libraries(${PROJECT_NAME} native log)

if (DEFINED DEBUG_SYMBOLS_PATH)
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()

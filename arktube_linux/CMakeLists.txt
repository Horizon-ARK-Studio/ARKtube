cmake_minimum_required(VERSION 3.16)
project(arktube_linux C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(GTK3 REQUIRED gtk+-3.0)
pkg_check_modules(WEBKIT2GTK REQUIRED webkit2gtk-4.1)

add_executable(arktube_linux src/main.c)

target_include_directories(arktube_linux PRIVATE
    ${GTK3_INCLUDE_DIRS}
    ${WEBKIT2GTK_INCLUDE_DIRS}
)
target_compile_options(arktube_linux PRIVATE
    -Wall -Wextra
    ${GTK3_CFLAGS_OTHER}
    ${WEBKIT2GTK_CFLAGS_OTHER}
)
target_link_libraries(arktube_linux PRIVATE
    ${GTK3_LIBRARIES}
    ${WEBKIT2GTK_LIBRARIES}
)
target_link_directories(arktube_linux PRIVATE
    ${GTK3_LIBRARY_DIRS}
    ${WEBKIT2GTK_LIBRARY_DIRS}
)

# Ship resources/ (icon + injected user script) right next to the binary,
# matching the build-tree lookup arktube_find_resource() in src/main.c
# tries first -- so `cmake --build .` alone, with no install step, is
# already runnable straight out of the build directory.
add_custom_command(TARGET arktube_linux POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
            ${CMAKE_CURRENT_SOURCE_DIR}/resources
            $<TARGET_FILE_DIR:arktube_linux>/resources
)

# `cmake --install .` layout: /usr/bin/arktube_linux (or --prefix
# equivalent) + /usr/share/arktube_linux/resources/..., matching the
# installed-prefix lookup in arktube_find_resource().
install(TARGETS arktube_linux RUNTIME DESTINATION bin)
install(DIRECTORY resources/ DESTINATION share/arktube_linux/resources)
install(FILES packaging/arktube-linux.desktop DESTINATION share/applications)
install(FILES resources/icons/appIcon.png
        DESTINATION share/icons/hicolor/256x256/apps
        RENAME arktube-linux.png)

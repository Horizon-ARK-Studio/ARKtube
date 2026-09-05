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
    m
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

# Both caches are what actually make Icon=arktube-linux (in the .desktop
# file above) and the freshly-installed hicolor PNG resolvable to
# anything that looks them up by name -- GNOME's dock included -- without
# a logout/re-login. Neither tool is guaranteed present on every build
# host (e.g. a minimal container image used only to compile this), so
# both are looked up and silently skipped if missing rather than failing
# `cmake --install`; a real desktop install normally has them, and most
# packaging formats (.deb postinst, etc.) already run their own
# equivalent of this regardless.
find_program(GTK_UPDATE_ICON_CACHE_EXE gtk-update-icon-cache)
if(GTK_UPDATE_ICON_CACHE_EXE)
    install(CODE "execute_process(
        COMMAND ${GTK_UPDATE_ICON_CACHE_EXE} -f -t
                \"\${CMAKE_INSTALL_PREFIX}/share/icons/hicolor\"
        ERROR_QUIET)")
endif()

find_program(UPDATE_DESKTOP_DATABASE_EXE update-desktop-database)
if(UPDATE_DESKTOP_DATABASE_EXE)
    install(CODE "execute_process(
        COMMAND ${UPDATE_DESKTOP_DATABASE_EXE}
                \"\${CMAKE_INSTALL_PREFIX}/share/applications\"
        ERROR_QUIET)")
endif()

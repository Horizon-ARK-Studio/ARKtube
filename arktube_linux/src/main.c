/*
    ARKtube Linux -- native port entry point.

    This replaces the Neutralino + hybrid embedded-Chrome shell (see
    ../ARKtube and its docs/bugs-caught) with a single native GTK3 +
    WebKit2GTK process:

      - One process, one window, one WebView. No second Chrome process
        to spawn, reparent (via xdotool/wmctrl/xbindkeys) and keep in
        sync -- see ../docs/bugs-caught for the class of bugs that
        reparenting trick caused. That also means this works unchanged
        under Wayland, where reparenting another client's window isn't
        possible at all.
      - No Node.js/npm/neu CLI build step and no prebuilt Neutralino
        server binary to ship -- just a small C program linked against
        GTK3 and WebKit2GTK, both of which are already on virtually
        every Linux desktop (WebKitGTK is what GNOME Web / Epiphany
        uses).
      - WebKitGTK's user agent setting is a full replacement, not an
        append -- see webkit_settings_set_user_agent() below -- so this
        can reliably ask for YouTube's TV/Leanback interface the same
        way the old "chrome" mode's --user-agent flag did, without the
        window-mode limitation the old shell's README documented
        (Neutralino's own webview could only *extend* its user agent).

    Scope of this first port (see ../docs/PORTING-NOTES.md for the full
    file-by-file mapping): window creation, loading youtube.com/tv,
    fullscreen (F11) and Escape-to-unfullscreen, and the injected
    resources/js/user-script.js carrying over the parts of the old
    app-init.js that are pure page-side JS with no Neutralino API
    dependency (Home-key SPA navigation, cursor auto-hide, and the
    gamepad/remote-to-keyboard remap). The tray icon, the on-screen
    Immersive Mode lockdown, and persisted settings are intentionally
    not ported yet -- they depended on Neutralino.storage/os.setTray
    and need a native replacement (GKeyFile + AppIndicator or similar),
    tracked as follow-up work rather than carried over as-is.
*/

#include <gdk/gdkkeysyms.h>
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

#include <limits.h>
#include <stdlib.h>
#include <unistd.h>

#define ARKTUBE_APP_ID "com.arktube.linux"
#define ARKTUBE_TITLE "ARKtube"
#define ARKTUBE_URL "https://www.youtube.com/tv#/"

/* Full replacement, matching the old shell's "chrome mode" user agent
   (see ../ARKtube/neutralino.config.json's "chrome".args and
   README.md's Chrome-Mode-vs-Window-Mode section) so YouTube's
   server-side device detection serves the same TV/Leanback interface
   this app is built around. */
#define ARKTUBE_USER_AGENT \
    "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa (compatible)"

#define ARKTUBE_DEFAULT_WIDTH 1280
#define ARKTUBE_DEFAULT_HEIGHT 720

/* Locate a file this app ships (icons, the injected JS) at runtime.
   Checked in order:
     1. next to the executable, under resources/<relative>       (build tree)
     2. ../share/arktube_linux/resources/<relative>               (installed,
        e.g. /usr/bin/arktube_linux + /usr/share/arktube_linux/...)
     3. resources/<relative> under the current working directory  (running
        straight out of the source tree)
   Returns a newly allocated path the caller must g_free(), or NULL if
   none of the above exist. */
static gchar *arktube_find_resource(const char *relative) {
    char exe_path[PATH_MAX];
    ssize_t len = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);

    if (len > 0) {
        exe_path[len] = '\0';
        gchar *exe_dir = g_path_get_dirname(exe_path);

        gchar *candidate = g_build_filename(exe_dir, "resources", relative, NULL);
        if (g_file_test(candidate, G_FILE_TEST_EXISTS)) {
            g_free(exe_dir);
            return candidate;
        }
        g_free(candidate);

        candidate = g_build_filename(
            exe_dir, "..", "share", "arktube_linux", "resources", relative, NULL);
        if (g_file_test(candidate, G_FILE_TEST_EXISTS)) {
            g_free(exe_dir);
            return candidate;
        }
        g_free(candidate);
        g_free(exe_dir);
    }

    gchar *cwd_candidate = g_build_filename("resources", relative, NULL);
    if (g_file_test(cwd_candidate, G_FILE_TEST_EXISTS)) {
        return cwd_candidate;
    }
    g_free(cwd_candidate);

    return NULL;
}

static gboolean arktube_window_is_fullscreen(GtkWidget *window) {
    GdkWindow *gdk_window = gtk_widget_get_window(window);
    if (!gdk_window) {
        return FALSE;
    }
    return (gdk_window_get_state(gdk_window) & GDK_WINDOW_STATE_FULLSCREEN) != 0;
}

/* F11 toggles fullscreen; Escape only ever backs out of it, mirroring
   the old app-init.js onKeyDown()/exitFullScreenIfActive() -- Escape
   never quits the app outright. Connected on the GtkWindow itself
   (rather than the WebView) so it fires ahead of youtube.com/tv's own
   keydown handling, the same way the old shell's global xbindkeys grab
   or its local-shell-page fallback did. */
static gboolean on_window_key_press(GtkWidget *widget, GdkEventKey *event,
                                     gpointer user_data) {
    (void)user_data;

    switch (event->keyval) {
        case GDK_KEY_F11:
            if (arktube_window_is_fullscreen(widget)) {
                gtk_window_unfullscreen(GTK_WINDOW(widget));
            } else {
                gtk_window_fullscreen(GTK_WINDOW(widget));
            }
            return TRUE;

        case GDK_KEY_Escape:
            if (arktube_window_is_fullscreen(widget)) {
                gtk_window_unfullscreen(GTK_WINDOW(widget));
                return TRUE;
            }
            return FALSE;

        default:
            return FALSE;
    }
}

/* Single-process shutdown: no second (Chrome) process to also signal,
   and no exitProcessOnClose flag to fight with -- closing the window
   just quits, the way a normal native application does. */
static gboolean on_window_delete(GtkWidget *widget, GdkEvent *event,
                                  gpointer user_data) {
    (void)widget;
    (void)event;
    (void)user_data;
    gtk_main_quit();
    return FALSE;
}

static void inject_user_script(WebKitUserContentManager *ucm, const char *path) {
    gchar *contents = NULL;
    gsize length = 0;
    GError *error = NULL;

    if (!g_file_get_contents(path, &contents, &length, &error)) {
        g_warning("ARKtube: could not read user script '%s': %s", path,
                   error ? error->message : "unknown error");
        g_clear_error(&error);
        return;
    }

    WebKitUserScript *script = webkit_user_script_new(
        contents,
        WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
        WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
        NULL, NULL);
    webkit_user_content_manager_add_script(ucm, script);
    webkit_user_script_unref(script);
    g_free(contents);
}

int main(int argc, char **argv) {
    gtk_init(&argc, &argv);

    g_set_application_name(ARKTUBE_TITLE);

    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(window), ARKTUBE_TITLE);
    gtk_window_set_default_size(GTK_WINDOW(window), ARKTUBE_DEFAULT_WIDTH,
                                 ARKTUBE_DEFAULT_HEIGHT);
    /* Boots maximized, not fullscreen -- same reasoning as the old
       shell's neutralino.config.json ("maximize": true, "fullScreen":
       false): the window manager's chrome stays visible until F11 is
       pressed explicitly. */
    gtk_window_maximize(GTK_WINDOW(window));

    gchar *icon_path = arktube_find_resource("icons/appIcon.png");
    if (icon_path) {
        GError *error = NULL;
        if (!gtk_window_set_icon_from_file(GTK_WINDOW(window), icon_path, &error)) {
            g_warning("ARKtube: could not load window icon '%s': %s", icon_path,
                       error ? error->message : "unknown error");
            g_clear_error(&error);
        }
        g_free(icon_path);
    } else {
        g_warning("ARKtube: icons/appIcon.png not found next to the executable "
                   "or under a share/arktube_linux install prefix; running "
                   "without a window icon.");
    }

    WebKitUserContentManager *ucm = webkit_user_content_manager_new();
    gchar *script_path = arktube_find_resource("js/user-script.js");
    if (script_path) {
        inject_user_script(ucm, script_path);
        g_free(script_path);
    } else {
        g_warning("ARKtube: js/user-script.js not found; Home-key navigation, "
                   "cursor auto-hide, and gamepad/remote input will be "
                   "unavailable this run.");
    }

    GtkWidget *webview = webkit_web_view_new_with_user_content_manager(ucm);
    WebKitSettings *settings = webkit_web_view_get_settings(WEBKIT_WEB_VIEW(webview));
    webkit_settings_set_user_agent(settings, ARKTUBE_USER_AGENT);
    webkit_settings_set_enable_developer_extras(settings, TRUE);
    webkit_settings_set_enable_media_stream(settings, TRUE);
    webkit_settings_set_enable_webaudio(settings, TRUE);

    gtk_container_add(GTK_CONTAINER(window), webview);

    g_signal_connect(window, "key-press-event", G_CALLBACK(on_window_key_press), NULL);
    g_signal_connect(window, "delete-event", G_CALLBACK(on_window_delete), NULL);

    gtk_widget_show_all(window);
    webkit_web_view_load_uri(WEBKIT_WEB_VIEW(webview), ARKTUBE_URL);

    gtk_main();
    return EXIT_SUCCESS;
}

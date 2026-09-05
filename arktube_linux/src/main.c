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
    gamepad/remote-to-keyboard remap). The tray icon and the on-screen
    Immersive Mode lockdown are intentionally not ported yet -- they
    depended on Neutralino.os.setTray and need a native replacement
    (AppIndicator or similar), tracked as follow-up work.

    Persisted settings (the other Neutralino.storage-dependent piece
    PORTING-NOTES.md flagged) are ported for the one setting that
    currently needs it: whether the window was fullscreen last run, so
    the F11 toggle survives a restart the same way it would have if the
    old shell's window state had been wired up to Neutralino.storage.
    It's a GKeyFile under g_get_user_config_dir(), the native fit that
    doc's "Persisted settings generally" note already called out.
*/

#include <gdk/gdkkeysyms.h>
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <math.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define ARKTUBE_APP_ID "com.arktube.linux"
#define ARKTUBE_TITLE "ARKtube"
#define ARKTUBE_URL "https://www.youtube.com/tv#/"

/* Must match packaging/arktube-linux.desktop's filename (its desktop-file
   id, "arktube-linux.desktop" minus the extension) and that file's Icon=
   key. GNOME Shell's dock/dash matches a *running window* back to an
   installed .desktop launcher (to know what to show/pin) by comparing
   WM_CLASS on X11, or the xdg_toplevel app-id on Wayland, against that
   id -- and GTK derives both from g_get_prgname() for a plain GtkWindow
   app like this one, not from the binary name. Left at the g_get_prgname()
   default, that comparison is "arktube_linux" (this binary's argv[0])
   vs. the desktop file's "arktube-linux" -- close enough to read as the
   same app to a person, but not an exact string match, so the window
   goes unmatched and GNOME falls back to a generic icon in the dock.
   Setting the prgname explicitly, here, is what keeps that comparison
   consistent. */
#define ARKTUBE_WM_CLASS "arktube-linux"

/* Full replacement, matching the old shell's "chrome mode" user agent
   (see ../ARKtube/neutralino.config.json's "chrome".args and
   README.md's Chrome-Mode-vs-Window-Mode section) so YouTube's
   server-side device detection serves the same TV/Leanback interface
   this app is built around. */
#define ARKTUBE_USER_AGENT \
    "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa (compatible)"

#define ARKTUBE_DEFAULT_WIDTH 1280
#define ARKTUBE_DEFAULT_HEIGHT 720

/* Splash overlay shown on startup while the main window's WebView loads
   youtube.com/tv underneath it. Drawn as a full-bleed layer *inside* the
   real app window (see the GtkOverlay wiring in main()) and dismissed
   the moment the WebView actually reports WEBKIT_LOAD_FINISHED, not
   after a fixed guess at how long that should take -- see
   on_webview_load_changed() below. ARKTUBE_SPLASH_MAX_WAIT_MS is only a
   defensive ceiling: if "load-changed" never reports FINISHED for some
   reason (a stalled resource, a WebKit quirk), the splash is dismissed
   anyway after this long rather than sitting on screen forever. */
#define ARKTUBE_SPLASH_MAX_WAIT_MS 20000

/* boot-logo.png and no_internet.png are both a fixed 1672x941 piece of
   artwork. Rather than scaling either down to some fixed width and
   letter/pillar-boxing it in the middle of whatever the real window's
   client area is, both are stretched -- non-uniformly if need be -- to
   exactly fill it, on every resize, regardless of the window's actual
   resolution or aspect ratio. See arktube_stretch_image_draw() below. */
#define ARKTUBE_SPLASH_LOGO_NATIVE_WIDTH 1672
#define ARKTUBE_SPLASH_LOGO_NATIVE_HEIGHT 941

/* Where the "Youtube tv app for desktop devices" tagline actually sits
   in boot-logo.png, measured directly off the artwork's pixels (left
   edge and right edge of the text) at native 1672x941 -- so the spinner
   can be pinned centered under its horizontal midpoint instead of
   guessed. Since the artwork is now stretched non-uniformly to fill the
   window, the spinner's position is recomputed from these on every
   resize (see arktube_splash_reposition_spinner() below) using
   independent horizontal and vertical scale factors, rather than a
   single shared scale the way a uniformly-scaled image would only need.

   The spinner is deliberately *not* pinned just under the tagline's
   bottom edge any more: that crowded the text instead of reading as its
   own element. Vertically it's anchored to the artwork's bottom edge
   instead, by ARKTUBE_SPLASH_SPINNER_BOTTOM_MARGIN_NATIVE -- chosen from
   the actual pixel content of boot-logo.png (see the color-sampling
   analysis that produced these numbers) so that, at this size, it sits
   in the empty dark space below the wordmark and clear of the diagonal
   red glow band, rather than overlapping it. That band's top edge
   doesn't reach this x-range at all (it only intrudes past roughly
   x=680 native, well right of the tagline's midpoint used below), so
   this margin has real clearance on every side, not just a close miss. */
#define ARKTUBE_SPLASH_TEXT_LEFT_NATIVE 172
#define ARKTUBE_SPLASH_TEXT_RIGHT_NATIVE 881
#define ARKTUBE_SPLASH_SPINNER_BOTTOM_MARGIN_NATIVE 160

/* Native pixel size (unscaled -- kept fixed regardless of window size,
   same as the spinner it replaces) of the branded spinner artwork
   below. Sized well above the old 30px default GtkSpinner so it reads
   as a deliberate piece of the composition instead of a stray dot. */
#define ARKTUBE_SPLASH_SPINNER_SIZE 90

/* How many degrees the spinner advances per animation tick, and how
   often -- together giving one full revolution every
   (360 / ARKTUBE_SPLASH_SPINNER_STEP_DEG) * ARKTUBE_SPLASH_SPINNER_INTERVAL_MS
   milliseconds. 12 degrees / 30ms is 30 steps per revolution at roughly
   33fps, about a 1-second-per-turn pace -- close to GtkSpinner's own
   default cadence. */
#define ARKTUBE_SPLASH_SPINNER_STEP_DEG 12.0
#define ARKTUBE_SPLASH_SPINNER_INTERVAL_MS 30

/* Connectivity check, done with plain BSD-socket syscalls (socket(2) /
   connect(2) / poll(2), no libcurl or libsoup dependency) rather than
   trying to load youtube.com/tv itself and hoping WebKit's own failure
   mode is easy to distinguish from a slow page. A fixed IP:port (Google
   Public DNS's TCP/53) is used instead of a hostname so a DNS
   resolution failure -- itself just another symptom of no internet --
   can't be confused with the connect() this is actually testing. */
#define ARKTUBE_CONNECTIVITY_HOST "8.8.8.8"
#define ARKTUBE_CONNECTIVITY_PORT 53
#define ARKTUBE_CONNECTIVITY_TIMEOUT_MS 2000
#define ARKTUBE_CONNECTIVITY_RETRY_INTERVAL_MS 3000

/* Persisted window state -- currently just "was the window fullscreen
   last time the app quit", read on startup and written whenever F11 or
   Escape changes it, so the toggle survives a restart. Native
   replacement for Neutralino.storage, per docs/PORTING-NOTES.md's
   "Persisted settings generally" note. */
#define ARKTUBE_CONFIG_DIR_NAME "arktube_linux"
#define ARKTUBE_CONFIG_FILE_NAME "config.ini"
#define ARKTUBE_CONFIG_GROUP "window"
#define ARKTUBE_CONFIG_KEY_FULLSCREEN "fullscreen"

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

/* Internet reachability check via a raw non-blocking connect(2) to
   ARKTUBE_CONNECTIVITY_HOST:ARKTUBE_CONNECTIVITY_PORT, bounded by
   poll(2) so a silently-dropping network can't hang this past
   ARKTUBE_CONNECTIVITY_TIMEOUT_MS. Deliberately just the raw BSD
   socket syscalls (socket/fcntl/connect/poll/getsockopt/close) instead
   of asking WebKit to load the real page and inferring the answer from
   its failure mode -- this is meant to run in a background thread,
   repeatedly, well before (and independently of) any WebView load.
   Always called off the main thread (see arktube_connectivity_thread
   below); nothing here touches GTK. */
static gboolean arktube_check_internet_now(void) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        return FALSE;
    }

    int flags = fcntl(sock, F_GETFL, 0);
    if (flags != -1) {
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(ARKTUBE_CONNECTIVITY_PORT);
    if (inet_pton(AF_INET, ARKTUBE_CONNECTIVITY_HOST, &addr.sin_addr) != 1) {
        close(sock);
        return FALSE;
    }

    gboolean online = FALSE;
    int rc = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
    if (rc == 0) {
        online = TRUE;
    } else if (errno == EINPROGRESS) {
        struct pollfd pfd = { .fd = sock, .events = POLLOUT, .revents = 0 };
        int pr = poll(&pfd, 1, ARKTUBE_CONNECTIVITY_TIMEOUT_MS);
        if (pr > 0 && (pfd.revents & POLLOUT)) {
            int so_error = 0;
            socklen_t len = sizeof(so_error);
            if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &so_error, &len) == 0 &&
                so_error == 0) {
                online = TRUE;
            }
        }
    }

    close(sock);
    return online;
}

/* Path to the persisted config file, creating its parent directory if
   needed (g_key_file_save_to_file() does not create directories).
   Returns a newly allocated path the caller must g_free(). */
static gchar *arktube_config_path(void) {
    gchar *dir = g_build_filename(g_get_user_config_dir(), ARKTUBE_CONFIG_DIR_NAME, NULL);
    g_mkdir_with_parents(dir, 0700);

    gchar *path = g_build_filename(dir, ARKTUBE_CONFIG_FILE_NAME, NULL);
    g_free(dir);
    return path;
}

/* Reads the remembered fullscreen state. Defaults to FALSE (matching
   the old shell's boot config, "maximize": true / "fullScreen": false)
   on first run, a missing file, or a corrupt one -- a persisted-setting
   read failing should fail open to today's existing behavior, not
   silently force a mode the user never asked for. */
static gboolean arktube_load_fullscreen_pref(void) {
    gchar *path = arktube_config_path();

    GKeyFile *keyfile = g_key_file_new();
    gboolean loaded = g_key_file_load_from_file(keyfile, path, G_KEY_FILE_NONE, NULL);

    gboolean fullscreen = FALSE;
    if (loaded) {
        GError *error = NULL;
        fullscreen = g_key_file_get_boolean(
            keyfile, ARKTUBE_CONFIG_GROUP, ARKTUBE_CONFIG_KEY_FULLSCREEN, &error);
        if (error) {
            fullscreen = FALSE;
            g_clear_error(&error);
        }
    }

    g_key_file_free(keyfile);
    g_free(path);
    return fullscreen;
}

/* Writes the current fullscreen state so the next launch can restore
   it. Re-reads the existing file first so unrelated keys/groups a
   future setting might add aren't clobbered by this one save. */
static void arktube_save_fullscreen_pref(gboolean fullscreen) {
    gchar *path = arktube_config_path();

    GKeyFile *keyfile = g_key_file_new();
    g_key_file_load_from_file(keyfile, path, G_KEY_FILE_NONE, NULL);
    g_key_file_set_boolean(
        keyfile, ARKTUBE_CONFIG_GROUP, ARKTUBE_CONFIG_KEY_FULLSCREEN, fullscreen);

    GError *error = NULL;
    if (!g_key_file_save_to_file(keyfile, path, &error)) {
        g_warning("ARKtube: could not save '%s': %s", path,
                  error ? error->message : "unknown error");
        g_clear_error(&error);
    }

    g_key_file_free(keyfile);
    g_free(path);
}

static gboolean arktube_window_is_fullscreen(GtkWidget *window) {
    GdkWindow *gdk_window = gtk_widget_get_window(window);
    if (!gdk_window) {
        return FALSE;
    }
    return (gdk_window_get_state(gdk_window) & GDK_WINDOW_STATE_FULLSCREEN) != 0;
}

/* Cheap Bluetooth/IR Fire TV Stick-style remotes (and similar smart-TV
   remotes) register as an ordinary Linux input "keyboard" device --
   `evtest` on one shows plain evdev KEY_* codes, not a HID gamepad --
   so none of resources/js/user-script.js's Gamepad-API remap applies
   to them; they arrive here as normal GDK key events instead. Most of
   the buttons already Just Work because their evdev codes land on
   keysyms youtube.com/tv's own JS already understands (KEY_UP/DOWN/
   LEFT/RIGHT -> GDK_KEY_Up/Down/Left/Right, KEY_KPENTER ->
   GDK_KEY_KP_Enter, which WebKitGTK's keyval-to-DOM-`key` table maps
   to "Enter" the same as a plain Return).

   Two buttons don't, and both are dedicated hardware keys with no
   video-game-controller equivalent, so the Gamepad-API path could
   never have covered them either: the remote's Home button reports
   XKB's XF86HomePage keysym, and its search/voice (Alexa/Google
   Assistant) button reports XF86Search. Checked against WebKitGTK's
   own GDK-keyval switch (Source/WebCore/platform/gtk/
   PlatformKeyboardEventGtk.cpp upstream, mirroring the older
   KeyEventGtk.cpp this project inherited its WebKitGTK from): neither
   XF86HomePage nor XF86Search is one of the cases handled there, so
   left alone they'd reach the page as an unrecognized/empty key
   the page's own JS has nothing to match against, the same failure
   mode Samsung/Logitech keyboard-on-smart-TV threads report for other
   XF86 media keys reaching a YouTube-flavored webview.

   The fix is done here rather than in user-script.js: this handler is
   connected on the GtkWindow (see the F11/Escape comment below) and
   so runs *before* the event ever reaches WebKitWebView, letting a
   keyval swap stand in for a page-side remap -- rewrite the keyval to
   one WebKitGTK's table *does* already translate correctly, then let
   it fall through unhandled so it continues on to the WebView exactly
   like a real keypress of that key would:

     - XF86HomePage -> Home, so it lands on user-script.js's existing
       onKeyDown()/goHome() (the same handler that already answers a
       real Home key), sending youtube.com/tv back to its `#/` root.
     - XF86Search -> the literal '/' character, "Go to the search box"
       on youtube.com's own documented global keyboard shortcuts and
       the closest evdev-remote equivalent of a search/Assistant
       button available without a matching native voice API to hook
       into. youtube.com/tv's YouTube Leanback predecessor similarly
       treated an ordinary keypress (any alphanumeric, or Up from the
       grid) as "start a search", so a remapped '/' degrades safely --
       at worst a no-op, never a stray character typed somewhere -- if
       this build of the page doesn't bind it. */
static void arktube_remap_remote_keyval(GdkEventKey *event) {
    switch (event->keyval) {
        /* GTK/GDK generates these two off whatever X11/XF86keysym.h the
           system had at GDK-build time, so the identifier isn't always
           the "GDK_KEY_XF86*" name docs/older code samples use --
           confirmed against this project's actual target (Ubuntu 24.04's
           libgtk-3-dev, gdk/gdkkeysyms.h) where the generated defines
           are plain GDK_KEY_HomePage / GDK_KEY_Search, no XF86 prefix,
           for the same 0x1008ff18 / 0x1008ff1b keysyms. */
        case GDK_KEY_HomePage:
            event->keyval = GDK_KEY_Home;
            break;

        case GDK_KEY_Search:
            event->keyval = GDK_KEY_slash;
            break;

        default:
            break;
    }

    /* Remote's PROGRAM/guide button: unlike Home/Search above, evdev
       KEY_PROGRAM has no keysym bound to it in the stock XKB tables, so
       it arrives as GDK_KEY_VoidSymbol -- catch it by hardware keycode
       (X keycode 370 = evdev code 362 + 8) and remap to F11 instead. */
    if (event->keyval == GDK_KEY_VoidSymbol && event->hardware_keycode == 370) {
        event->keyval = GDK_KEY_F11;
    }
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

    arktube_remap_remote_keyval(event);

    switch (event->keyval) {
        case GDK_KEY_F11:
            if (arktube_window_is_fullscreen(widget)) {
                gtk_window_unfullscreen(GTK_WINDOW(widget));
                arktube_save_fullscreen_pref(FALSE);
            } else {
                gtk_window_fullscreen(GTK_WINDOW(widget));
                arktube_save_fullscreen_pref(TRUE);
            }
            return TRUE;

        case GDK_KEY_Escape:
            if (arktube_window_is_fullscreen(widget)) {
                gtk_window_unfullscreen(GTK_WINDOW(widget));
                arktube_save_fullscreen_pref(FALSE);
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

/* Paints whatever GdkPixbuf was stashed on this GtkDrawingArea (see the
   "arktube-pixbuf" g_object_set_data_full() calls below) scaled to
   exactly the widget's current allocated size -- width and height
   independently, so it stretches to fill the window edge to edge on
   every resize regardless of the window's actual resolution or aspect
   ratio, rather than the artwork's own 1672:941 ratio being preserved
   and letter/pillar-boxed in the middle of it. Shared by both the boot
   splash and the no-internet screen below, since both want the same
   "cover the whole window, however big or oddly-shaped it is" artwork
   behavior. */
static gboolean arktube_stretch_image_draw(GtkWidget *widget, cairo_t *cr, gpointer user_data) {
    (void)user_data;
    GdkPixbuf *original = GDK_PIXBUF(g_object_get_data(G_OBJECT(widget), "arktube-pixbuf"));
    if (!original) {
        return FALSE;
    }

    gint width = gtk_widget_get_allocated_width(widget);
    gint height = gtk_widget_get_allocated_height(widget);
    if (width <= 0 || height <= 0) {
        return FALSE;
    }

    GdkPixbuf *scaled = gdk_pixbuf_scale_simple(original, width, height, GDK_INTERP_BILINEAR);
    if (scaled) {
        gdk_cairo_set_source_pixbuf(cr, scaled, 0, 0);
        cairo_paint(cr);
        g_object_unref(scaled);
    }
    return FALSE;
}

/* Loads a resource PNG at its full native resolution (no scaling here --
   arktube_stretch_image_draw() above rescales it to whatever size it's
   actually drawn at, on every resize) and wraps it in a hexpand/vexpand
   GtkDrawingArea that fills whatever container it's added to. Returns
   NULL (having already warned) if the resource is missing or fails to
   load. */
static GtkWidget *arktube_create_stretch_image_area(const char *relative_resource_path,
                                                     const char *missing_warning) {
    gchar *path = arktube_find_resource(relative_resource_path);
    if (!path) {
        g_warning("%s", missing_warning);
        return NULL;
    }

    GError *error = NULL;
    GdkPixbuf *pixbuf = gdk_pixbuf_new_from_file(path, &error);
    g_free(path);

    if (!pixbuf) {
        g_warning("ARKtube: could not load '%s': %s", relative_resource_path,
                  error ? error->message : "unknown error");
        g_clear_error(&error);
        return NULL;
    }

    GtkWidget *area = gtk_drawing_area_new();
    gtk_widget_set_hexpand(area, TRUE);
    gtk_widget_set_vexpand(area, TRUE);
    gtk_widget_set_halign(area, GTK_ALIGN_FILL);
    gtk_widget_set_valign(area, GTK_ALIGN_FILL);
    /* Ownership moves to the widget: the pixbuf is unref'd automatically
       when the drawing area is destroyed. */
    g_object_set_data_full(G_OBJECT(area), "arktube-pixbuf", pixbuf, g_object_unref);
    g_signal_connect(area, "draw", G_CALLBACK(arktube_stretch_image_draw), NULL);

    return area;
}

/* Recomputes the spinner's position every time the stretched boot-logo
   image is resized, using independent horizontal and vertical scale
   factors (allocation size / native size on each axis) rather than one
   shared scale -- because the image is no longer scaled uniformly, a
   single shared factor would drift off the tagline as soon as the
   window's aspect ratio stopped matching the artwork's own 1672:941.

   Horizontally: centered on the tagline's own midpoint, not its left
   edge -- subtracting half the spinner's fixed size is what actually
   centers it there, rather than just starting the spinner at that
   x-coordinate. Vertically: anchored to the *bottom* of the frame by
   ARKTUBE_SPLASH_SPINNER_BOTTOM_MARGIN_NATIVE rather than hung just
   under the text, so it reads as belonging to the composition as a
   whole instead of crowding the tagline. */
static void arktube_splash_reposition_spinner(GtkWidget *widget, GdkRectangle *allocation,
                                               gpointer user_data) {
    (void)widget;
    GtkWidget *spinner = GTK_WIDGET(user_data);

    if (allocation->width <= 0 || allocation->height <= 0) {
        return;
    }

    gdouble scale_x = (gdouble)allocation->width / (gdouble)ARKTUBE_SPLASH_LOGO_NATIVE_WIDTH;
    gdouble scale_y = (gdouble)allocation->height / (gdouble)ARKTUBE_SPLASH_LOGO_NATIVE_HEIGHT;
    gdouble text_center_native =
        (ARKTUBE_SPLASH_TEXT_LEFT_NATIVE + ARKTUBE_SPLASH_TEXT_RIGHT_NATIVE) / 2.0;

    gint margin_start = (gint)(text_center_native * scale_x) - (ARKTUBE_SPLASH_SPINNER_SIZE / 2);
    gint margin_top = allocation->height -
        (gint)(ARKTUBE_SPLASH_SPINNER_BOTTOM_MARGIN_NATIVE * scale_y) -
        ARKTUBE_SPLASH_SPINNER_SIZE;

    gtk_widget_set_margin_start(spinner, margin_start);
    gtk_widget_set_margin_top(spinner, margin_top);
}

/* Draws the current frame of the branded spinner: the pixbuf stashed on
   this widget (see arktube_create_spinner_widget() below) rotated about
   its own center by "arktube-angle" degrees. Rotating a pre-rasterized
   pixbuf here, rather than re-rendering the SVG every frame, is what
   keeps a ~33fps redraw cheap. */
static gboolean arktube_spinner_draw(GtkWidget *widget, cairo_t *cr, gpointer user_data) {
    (void)user_data;
    GdkPixbuf *pixbuf = GDK_PIXBUF(g_object_get_data(G_OBJECT(widget), "arktube-pixbuf"));
    if (!pixbuf) {
        return FALSE;
    }

    gdouble angle = *(gdouble *)g_object_get_data(G_OBJECT(widget), "arktube-angle");
    gint w = gdk_pixbuf_get_width(pixbuf);
    gint h = gdk_pixbuf_get_height(pixbuf);

    cairo_translate(cr, ARKTUBE_SPLASH_SPINNER_SIZE / 2.0, ARKTUBE_SPLASH_SPINNER_SIZE / 2.0);
    cairo_rotate(cr, angle * G_PI / 180.0);
    gdk_cairo_set_source_pixbuf(cr, pixbuf, -w / 2.0, -h / 2.0);
    cairo_paint(cr);
    return FALSE;
}

/* Timer callback (see g_timeout_add() in arktube_create_spinner_widget()
   below): advances the rotation angle and repaints. Stops itself
   (G_SOURCE_REMOVE) once the widget is gone -- the splash overlay is
   destroyed the moment youtube.com/tv finishes loading (see
   arktube_dismiss_splash()), and without this check the timer would
   otherwise keep firing against a freed widget. */
static gboolean arktube_spinner_tick(gpointer user_data) {
    GtkWidget *widget = GTK_WIDGET(user_data);
    if (!GTK_IS_WIDGET(widget)) {
        return G_SOURCE_REMOVE;
    }

    gdouble *angle = (gdouble *)g_object_get_data(G_OBJECT(widget), "arktube-angle");
    *angle = fmod(*angle + ARKTUBE_SPLASH_SPINNER_STEP_DEG, 360.0);
    gtk_widget_queue_draw(widget);
    return G_SOURCE_CONTINUE;
}

/* The branded loading spinner: an 8-blade pinwheel traced off an
   internal design reference (contour-extracted so its curved "sail"
   silhouette and 45-degree blade spacing are a faithful match, not a
   guess) and recolored into ARKtube's own red/orange/black instead of
   the reference's teal/blue/yellow, shipped as
   resources/icons/arktube-spinner.svg. Rasterized once at
   ARKTUBE_SPLASH_SPINNER_SIZE (so it stays crisp at the one size it's
   ever drawn at) and then just rotated per frame in
   arktube_spinner_draw() above, the same "pre-render once, transform
   per frame" trade GtkSpinner itself makes internally.
   Falls back to a plain (but re-tinted) GtkSpinner if the SVG can't be
   loaded -- e.g. a minimal system missing the gdk-pixbuf SVG loader
   (librsvg) -- so a missing icon loader degrades the *style* of the
   loading indicator rather than losing it outright. */
static GtkWidget *arktube_create_spinner_widget(void) {
    gchar *path = arktube_find_resource("icons/arktube-spinner.svg");
    GdkPixbuf *pixbuf = NULL;
    if (path) {
        GError *error = NULL;
        pixbuf = gdk_pixbuf_new_from_file_at_scale(
            path, ARKTUBE_SPLASH_SPINNER_SIZE, ARKTUBE_SPLASH_SPINNER_SIZE, TRUE, &error);
        if (!pixbuf) {
            g_warning("ARKtube: could not load 'icons/arktube-spinner.svg': %s",
                      error ? error->message : "unknown error");
            g_clear_error(&error);
        }
        g_free(path);
    }

    if (!pixbuf) {
        GtkWidget *fallback = gtk_spinner_new();
        gtk_widget_set_size_request(fallback, ARKTUBE_SPLASH_SPINNER_SIZE,
                                     ARKTUBE_SPLASH_SPINNER_SIZE);
        gtk_widget_set_halign(fallback, GTK_ALIGN_START);
        gtk_widget_set_valign(fallback, GTK_ALIGN_START);
        gtk_spinner_start(GTK_SPINNER(fallback));

        GtkCssProvider *css = gtk_css_provider_new();
        gtk_css_provider_load_from_data(
            css, "spinner { color: #E8221A; }", -1, NULL);
        gtk_style_context_add_provider(
            gtk_widget_get_style_context(fallback),
            GTK_STYLE_PROVIDER(css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
        g_object_unref(css);
        return fallback;
    }

    GtkWidget *area = gtk_drawing_area_new();
    gtk_widget_set_size_request(area, ARKTUBE_SPLASH_SPINNER_SIZE, ARKTUBE_SPLASH_SPINNER_SIZE);
    gtk_widget_set_halign(area, GTK_ALIGN_START);
    gtk_widget_set_valign(area, GTK_ALIGN_START);
    /* Background must stay transparent so the boot-logo shows through
       around the pinwheel's own silhouette. */
    gtk_widget_set_app_paintable(area, TRUE);

    g_object_set_data_full(G_OBJECT(area), "arktube-pixbuf", pixbuf, g_object_unref);
    gdouble *angle = g_new0(gdouble, 1);
    g_object_set_data_full(G_OBJECT(area), "arktube-angle", angle, g_free);

    g_signal_connect(area, "draw", G_CALLBACK(arktube_spinner_draw), NULL);
    guint tick_id = g_timeout_add(
        ARKTUBE_SPLASH_SPINNER_INTERVAL_MS, arktube_spinner_tick, area);
    /* g_timeout_add() itself already stops (via arktube_spinner_tick()'s
       GTK_IS_WIDGET() check) once "area" is destroyed, but removing the
       source explicitly on destroy avoids leaving it registered for the
       few-hundred-ms window between the widget dying and its next
       scheduled tick. */
    g_signal_connect_swapped(area, "destroy", G_CALLBACK(g_source_remove),
                              GUINT_TO_POINTER(tick_id));

    return area;
}

/* Splash overlay: boot-logo.png stretched to exactly fill the window
   (see arktube_stretch_image_draw() above) with a spinning "loading"
   indicator overlaid just under the tagline, drawn as a full-bleed
   layer *inside* the main window (layered on top of the WebView via the
   GtkOverlay set up in main(), not as a second, separate top-level
   window) so it always exactly fills and matches whatever state the
   real window is actually in this run -- maximized, fullscreen, or a
   plain resizable window -- instead of floating as its own small,
   fixed-size, always-centered-on-the-screen window regardless of where
   or how big the real window ends up.
   Returns NULL (and leaves nothing on screen) if the boot logo isn't
   shipped this run, so packaging without it still boots straight to the
   bare webview with no overlay at all. */
static GtkWidget *arktube_create_splash_overlay(void) {
    GtkWidget *backdrop = gtk_event_box_new();
    gtk_widget_set_hexpand(backdrop, TRUE);
    gtk_widget_set_vexpand(backdrop, TRUE);
    gtk_widget_set_halign(backdrop, GTK_ALIGN_FILL);
    gtk_widget_set_valign(backdrop, GTK_ALIGN_FILL);
    gtk_widget_set_name(backdrop, "arktube-splash-backdrop");

    GtkCssProvider *css = gtk_css_provider_new();
    gtk_css_provider_load_from_data(
        css, "#arktube-splash-backdrop { background-color: #0f0f0f; }", -1, NULL);
    gtk_style_context_add_provider(
        gtk_widget_get_style_context(backdrop),
        GTK_STYLE_PROVIDER(css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    g_object_unref(css);

    /* GtkOverlay so the spinner floats on top of the stretched image at
       an exact pixel position instead of sharing a box layout with it.
       Fills the backdrop completely (hexpand/vexpand + ALIGN_FILL)
       rather than shrink-wrapping to a fixed size and centering, so the
       image beneath it stretches to the real window's actual size. */
    GtkWidget *card = gtk_overlay_new();
    gtk_widget_set_hexpand(card, TRUE);
    gtk_widget_set_vexpand(card, TRUE);
    gtk_widget_set_halign(card, GTK_ALIGN_FILL);
    gtk_widget_set_valign(card, GTK_ALIGN_FILL);
    gtk_container_add(GTK_CONTAINER(backdrop), card);

    GtkWidget *image_area = arktube_create_stretch_image_area(
        "splash screen/boot-logo.png",
        "ARKtube: 'splash screen/boot-logo.png' not found; skipping the "
        "splash screen this run.");
    if (!image_area) {
        return NULL;
    }
    gtk_container_add(GTK_CONTAINER(card), image_area);

    /* The branded loading spinner (see arktube_create_spinner_widget()
       above): centered under the tagline's midpoint, anchored to the
       bottom of the frame rather than hung directly under the text.
       Repositioned on every resize of image_area (see
       arktube_splash_reposition_spinner() above), since the stretched
       image's scale changes with the window's own size. */
    GtkWidget *spinner = arktube_create_spinner_widget();
    gtk_overlay_add_overlay(GTK_OVERLAY(card), spinner);
    g_signal_connect(image_area, "size-allocate",
                      G_CALLBACK(arktube_splash_reposition_spinner), spinner);

    return backdrop;
}

/* No-internet overlay: no_internet.png stretched to exactly fill the
   window (same arktube_stretch_image_draw() approach as the boot splash
   above) in place of the browser. No spinner on this one: the retry
   loop runs silently in the background (see arktube_connectivity_thread
   below) and this overlay is simply replaced the moment that loop finds
   a connection, so there's nothing useful for a spinner to represent
   here. Returns NULL under the same "not shipped this run" condition as
   the boot splash. */
static GtkWidget *arktube_create_no_internet_overlay(void) {
    GtkWidget *backdrop = gtk_event_box_new();
    gtk_widget_set_hexpand(backdrop, TRUE);
    gtk_widget_set_vexpand(backdrop, TRUE);
    gtk_widget_set_halign(backdrop, GTK_ALIGN_FILL);
    gtk_widget_set_valign(backdrop, GTK_ALIGN_FILL);
    gtk_widget_set_name(backdrop, "arktube-no-internet-backdrop");

    GtkCssProvider *css = gtk_css_provider_new();
    gtk_css_provider_load_from_data(
        css, "#arktube-no-internet-backdrop { background-color: #0f0f0f; }", -1, NULL);
    gtk_style_context_add_provider(
        gtk_widget_get_style_context(backdrop),
        GTK_STYLE_PROVIDER(css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    g_object_unref(css);

    GtkWidget *image_area = arktube_create_stretch_image_area(
        "splash screen/no_internet.png",
        "ARKtube: 'splash screen/no_internet.png' not found; cannot show "
        "the no-internet screen this run.");
    if (!image_area) {
        return NULL;
    }
    gtk_container_add(GTK_CONTAINER(backdrop), image_area);

    return backdrop;
}

typedef struct {
    GtkWidget *splash;      /* NULL once dismissed (or if none was ever shown) */
    guint      fallback_id; /* the ARKTUBE_SPLASH_MAX_WAIT_MS safety timer's source id, or 0 */
} ArktubeSplashState;

/* Tears the splash overlay down, whichever of the two callbacks below
   gets there first -- guarded so the other one, firing afterwards, is
   just a no-op instead of a double-destroy. */
static void arktube_dismiss_splash(ArktubeSplashState *state) {
    if (!state->splash) {
        return;
    }
    gtk_widget_destroy(state->splash);
    state->splash = NULL;
    if (state->fallback_id) {
        g_source_remove(state->fallback_id);
        state->fallback_id = 0;
    }
}

/* Defensive ceiling (see ARKTUBE_SPLASH_MAX_WAIT_MS above): reveals
   whatever's underneath even if the WebView never reports
   WEBKIT_LOAD_FINISHED for some reason. */
static gboolean on_splash_fallback_timeout(gpointer user_data) {
    ArktubeSplashState *state = (ArktubeSplashState *)user_data;
    state->fallback_id = 0; /* this source is already being removed by returning below */
    arktube_dismiss_splash(state);
    return G_SOURCE_REMOVE;
}

/* The real trigger: dismiss the splash exactly when youtube.com/tv has
   actually finished loading, rather than after a fixed guess at how
   long that should take -- so a fast connection isn't held on the
   splash needlessly, and a slow one doesn't get dumped onto a
   half-loaded page just because a timer ran out. WEBKIT_LOAD_FINISHED
   fires once the main frame's own load has completed; youtube.com/tv's
   own client-side JS may still be settling its UI a moment after that,
   the same trade-off the old fixed-timer version had no visibility into
   at all. */
static void on_webview_load_changed(WebKitWebView *webview, WebKitLoadEvent load_event,
                                     gpointer user_data) {
    (void)webview;
    if (load_event != WEBKIT_LOAD_FINISHED) {
        return;
    }
    arktube_dismiss_splash((ArktubeSplashState *)user_data);
}

/* Gates the WebView ever being pointed at ARKTUBE_URL behind the
   connectivity check below, so a genuinely offline machine shows
   no_internet.png -- never an empty or perpetually-loading browser --
   and only starts the real load (WebView + boot splash, exactly the
   existing flow) once arktube_check_internet_now() actually succeeds. */
typedef struct {
    GtkWidget          *root_overlay;
    GtkWidget          *webview;
    ArktubeSplashState *splash_state;
    GtkWidget          *no_internet_overlay; /* main-thread-only; NULL when not shown */
    gboolean            webview_started;     /* main-thread-only; guards a single load_uri() */
} ArktubeConnectivityState;

/* Main-thread idle callback: starts the real app, the same WebView
   load + boot splash the app always showed before this offline gating
   existed. Tearing down a no-internet overlay first if the connection
   only came back after one was already shown. Idempotent via
   webview_started, in case this somehow ran more than once. */
static gboolean arktube_on_internet_ready(gpointer user_data) {
    ArktubeConnectivityState *cs = (ArktubeConnectivityState *)user_data;

    if (cs->no_internet_overlay) {
        gtk_widget_destroy(cs->no_internet_overlay);
        cs->no_internet_overlay = NULL;
    }

    if (!cs->webview_started) {
        cs->webview_started = TRUE;
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(cs->webview), ARKTUBE_URL);

        GtkWidget *splash = arktube_create_splash_overlay();
        if (splash) {
            gtk_overlay_add_overlay(GTK_OVERLAY(cs->root_overlay), splash);
            gtk_widget_show_all(splash);
            cs->splash_state->splash = splash;
            cs->splash_state->fallback_id = g_timeout_add(
                ARKTUBE_SPLASH_MAX_WAIT_MS, on_splash_fallback_timeout, cs->splash_state);
        }
    }

    return G_SOURCE_REMOVE;
}

/* Main-thread idle callback: shows the no-internet screen the first
   time a check fails. Guarded so repeated failures during the retry
   loop don't pile up duplicate overlays on top of each other. */
static gboolean arktube_on_internet_unavailable(gpointer user_data) {
    ArktubeConnectivityState *cs = (ArktubeConnectivityState *)user_data;

    if (!cs->no_internet_overlay && !cs->webview_started) {
        GtkWidget *overlay = arktube_create_no_internet_overlay();
        if (overlay) {
            gtk_overlay_add_overlay(GTK_OVERLAY(cs->root_overlay), overlay);
            gtk_widget_show_all(overlay);
            cs->no_internet_overlay = overlay;
        }
    }

    return G_SOURCE_REMOVE;
}

/* Runs entirely off the main thread: repeats arktube_check_internet_now()
   (itself bounded by ARKTUBE_CONNECTIVITY_TIMEOUT_MS) with an
   ARKTUBE_CONNECTIVITY_RETRY_INTERVAL_MS sleep between failed attempts,
   until one succeeds -- then hands off to the main thread via
   g_idle_add() and exits. Nothing in this function touches GTK directly;
   only the two callbacks above do, and only ever on the main thread,
   which is what makes it safe for this to run concurrently with the
   GTK main loop at all. */
static gpointer arktube_connectivity_thread(gpointer user_data) {
    ArktubeConnectivityState *cs = (ArktubeConnectivityState *)user_data;

    for (;;) {
        if (arktube_check_internet_now()) {
            g_idle_add(arktube_on_internet_ready, cs);
            return NULL;
        }
        g_idle_add(arktube_on_internet_unavailable, cs);
        g_usleep(ARKTUBE_CONNECTIVITY_RETRY_INTERVAL_MS * 1000);
    }
}

int main(int argc, char **argv) {
    /* Must happen before gtk_init(): GDK reads g_get_prgname() while
       setting up the X11 WM_CLASS hint (and the Wayland xdg_toplevel
       app-id) for every window this process creates, and that's the
       identity GNOME Shell's dock matches against installed .desktop
       files -- see the ARKTUBE_WM_CLASS comment above. Setting it here
       covers every window (splash included) consistently, rather than
       patching one window after the fact. */
    g_set_prgname(ARKTUBE_WM_CLASS);

    gtk_init(&argc, &argv);

    g_set_application_name(ARKTUBE_TITLE);

    /* Default icon for every window this process creates, looked up by
       name from the icon theme -- the same "arktube-linux" name the
       .desktop file's Icon= key resolves, and the same PNG that gets
       installed to share/icons/hicolor/256x256/apps/ (see
       CMakeLists.txt). Consistent with the WM_CLASS/app-id fix above:
       one name, matched in both places, rather than the window's own
       XWMHints icon (set below, from a raw file path) being the only
       thing that ever agreed with the .desktop file. */
    gtk_window_set_default_icon_name(ARKTUBE_WM_CLASS);

    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(window), ARKTUBE_TITLE);
    gtk_window_set_default_size(GTK_WINDOW(window), ARKTUBE_DEFAULT_WIDTH,
                                 ARKTUBE_DEFAULT_HEIGHT);
    /* Boots maximized by default -- same reasoning as the old shell's
       neutralino.config.json ("maximize": true, "fullScreen": false):
       the window manager's chrome stays visible until F11 is pressed
       explicitly. But if the window was fullscreen when the app last
       quit, honor that instead, the same way the on-screen fullscreen
       button's state would have round-tripped through
       Neutralino.storage in the old shell (see arktube_save_fullscreen_pref
       in on_window_key_press below). */
    if (arktube_load_fullscreen_pref()) {
        gtk_window_fullscreen(GTK_WINDOW(window));
    } else {
        gtk_window_maximize(GTK_WINDOW(window));
    }

    gchar *icon_path = arktube_find_resource("icons/appIcon.png");
    if (icon_path) {
        /* Belt-and-suspenders on top of gtk_window_set_default_icon_name()
           above: this covers running straight out of the build tree or an
           install whose icon cache hasn't been regenerated yet, where an
           icon-theme lookup by name would find nothing. Installed-and-
           cached runs get the same picture either way, since this is the
           same file the hicolor icon is a copy of. */
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
    /* ALWAYS is WebKitGTK's own default since 2.16, but state it explicitly
       so a future WebKit default change, or a distro/session compositing
       override, can't silently fall back to software rendering underneath
       us -- that's the single biggest source of the stutter this app is
       built to avoid. See docs/HARDWARE-ACCELERATION.md for the system
       packages (GStreamer VA-API) this still depends on for smooth video
       decode, and the WEBKIT_DISABLE_DMABUF_RENDERER escape hatch for the
       rarer case where accelerated compositing itself is the problem. */
    webkit_settings_set_hardware_acceleration_policy(
        settings, WEBKIT_HARDWARE_ACCELERATION_POLICY_ALWAYS);

    /* GtkOverlay owns both layers now: the WebView fills it as the base
       child, and (if boot-logo.png shipped) the splash backdrop sits on
       top of it as an overlay child -- both inside this one, real,
       already-maximized/fullscreen GtkWindow (set above), instead of the
       splash being a second, separate, fixed-size top-level window. See
       arktube_create_splash_overlay()'s comment for the bug that used to
       cause: the splash not actually filling/matching the app's own
       window, and appearing as a small box with the desktop visible
       around it. */
    GtkWidget *root_overlay = gtk_overlay_new();
    gtk_container_add(GTK_CONTAINER(window), root_overlay);
    gtk_container_add(GTK_CONTAINER(root_overlay), webview);

    g_signal_connect(window, "key-press-event", G_CALLBACK(on_window_key_press), NULL);
    g_signal_connect(window, "delete-event", G_CALLBACK(on_window_delete), NULL);

    /* Connected before load_uri() below so "load-changed" can't possibly
       fire WEBKIT_LOAD_FINISHED before anything is listening for it. */
    ArktubeSplashState *splash_state = g_new0(ArktubeSplashState, 1);
    g_signal_connect(webview, "load-changed",
                      G_CALLBACK(on_webview_load_changed), splash_state);

    /* The WebView is never pointed at ARKTUBE_URL directly here anymore
       -- arktube_on_internet_ready() (run from the background
       connectivity thread below, via g_idle_add) is what actually calls
       webkit_web_view_load_uri() and builds the boot splash, and only
       once arktube_check_internet_now() has confirmed there's a
       connection. Until then, arktube_on_internet_unavailable() shows
       no_internet.png in the same full-bleed overlay spot instead, so a
       genuinely offline machine never sits on an empty or endlessly
       "loading" browser. */
    ArktubeConnectivityState *connectivity_state = g_new0(ArktubeConnectivityState, 1);
    connectivity_state->root_overlay = root_overlay;
    connectivity_state->webview = webview;
    connectivity_state->splash_state = splash_state;
    g_thread_new("arktube-connectivity", arktube_connectivity_thread, connectivity_state);

    /* Show the real window immediately, already in its final
       maximized/fullscreen state (set above) -- whatever's on top
       (the no-internet screen or the boot splash, once the connectivity
       thread's first check reports back) is what actually becomes
       visible; nothing is deferred behind a second window anymore. */
    gtk_widget_show_all(window);

    gtk_main();

    g_free(connectivity_state);

    g_free(splash_state);
    return EXIT_SUCCESS;
}

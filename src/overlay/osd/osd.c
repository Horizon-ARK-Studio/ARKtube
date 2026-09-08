/* osd.c — standalone TV-style volume/brightness toast.
 *
 * Deliberately its OWN small process, entirely separate from overlay.py's
 * pywebview/WebKit stack. overlay.py's OSD toast (PANEL_GEOMETRY['osd'] +
 * static/app.js's showOsd()) has been unreliable in the field even after
 * fixing the __cgSetPanel bug that used to hide it on every push — this
 * exists to give volume/brightness a toast that does not depend on
 * pywebview, WebKit, or overlay.py's own health at all: it's a ~150-line
 * GTK3 + gtk-layer-shell C program with nothing else in its dependency
 * chain, launched directly by the Volume/Brightness bindsyms in
 * 20-arktube.conf via osd-notify.sh (same directory), independent of
 * whether overlay.py's own corner pill/panel is even running.
 *
 * Singleton via a PID file + SIGUSR1, same pattern as overlay.py's own
 * fix for the pkill-also-hits-systemd-inhibit bug (see overlay.py's
 * PID_PATH comment) — osd-notify.sh writes the new kind/level/muted into
 * osd.state and either signals an already-running instance (which
 * re-reads the state file and resets its own hide timer) or launches a
 * fresh one, so rapid repeated key presses reuse one window instead of
 * spawning/destroying a new one per press.
 */

#include <gtk/gtk.h>
#include <gtk-layer-shell.h>
#include <glib-unix.h>
#include <glib/gstdio.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define OSD_WIDTH 320
#define OSD_HEIGHT 140
#define HIDE_MS 1600
#define TOP_MARGIN 24

static GtkWidget *window;
static GtkWidget *title_label;
static GtkWidget *value_label;
static GtkWidget *level_bar;
static guint hide_timer_id = 0;
static char state_path[PATH_MAX];
static char pid_path[PATH_MAX];

static void cleanup_pid_file(void) {
    /* Only remove it if it's still ours -- mirrors overlay.py's own
     * _remove_pid_file() guard against a race with a newer instance. */
    gchar *contents = NULL;
    if (g_file_get_contents(pid_path, &contents, NULL, NULL)) {
        char mine[32];
        g_snprintf(mine, sizeof(mine), "%d", (int)getpid());
        if (g_strcmp0(g_strstrip(contents), mine) == 0) g_remove(pid_path);
        g_free(contents);
    }
}

static gboolean hide_and_quit(gpointer data) {
    (void)data;
    hide_timer_id = 0;
    cleanup_pid_file();
    gtk_main_quit();
    return G_SOURCE_REMOVE;
}

static void reset_hide_timer(void) {
    if (hide_timer_id) g_source_remove(hide_timer_id);
    hide_timer_id = g_timeout_add(HIDE_MS, hide_and_quit, NULL);
}

/* osd.state is a single line written by osd-notify.sh: "<kind> <level> <muted>"
 * e.g. "volume 42 0" or "brightness 70 0". Malformed/missing state is not
 * fatal -- just leaves the toast showing whatever it last showed. */
static void refresh_from_state(void) {
    FILE *f = fopen(state_path, "r");
    if (!f) return;
    char kind[32] = {0};
    int level = 0, muted = 0;
    int parsed = fscanf(f, "%31s %d %d", kind, &level, &muted);
    fclose(f);
    if (parsed != 3) return;

    if (level < 0) level = 0;
    if (level > 100) level = 100;

    gboolean is_volume = g_strcmp0(kind, "volume") == 0;
    gtk_label_set_text(GTK_LABEL(title_label), is_volume ? "VOLUME" : "BRIGHTNESS");

    char buf[16];
    if (is_volume && muted) {
        g_strlcpy(buf, "Muted", sizeof(buf));
        gtk_level_bar_set_value(GTK_LEVEL_BAR(level_bar), 0.0);
    } else {
        g_snprintf(buf, sizeof(buf), "%d%%", level);
        gtk_level_bar_set_value(GTK_LEVEL_BAR(level_bar), level / 100.0);
    }
    gtk_label_set_text(GTK_LABEL(value_label), buf);

    gtk_widget_show_all(window);
}

static gboolean on_usr1(gpointer data) {
    (void)data;
    refresh_from_state();
    reset_hide_timer();
    return G_SOURCE_CONTINUE; /* keep listening for the next press */
}

static gboolean write_pid_file(void) {
    char buf[32];
    g_snprintf(buf, sizeof(buf), "%d", (int)getpid());
    return g_file_set_contents(pid_path, buf, -1, NULL);
}

static void apply_css(void) {
    GtkCssProvider *css = gtk_css_provider_new();
    gtk_css_provider_load_from_data(css,
        "window { background-color: rgba(18,18,22,0.92); border-radius: 14px; }"
        "label.osd-title { color: #9aa0ac; font-size: 12px; font-weight: 700; "
        "  letter-spacing: 1.5px; }"
        "label.osd-value { color: #ffffff; font-size: 24px; font-weight: 700; }"
        "levelbar block.filled { background-color: #4f8cff; border-radius: 999px; }"
        "levelbar block.empty { background-color: rgba(255,255,255,0.14); "
        "  border-radius: 999px; }"
        "levelbar trough { min-height: 8px; border-radius: 999px; border: none; }",
        -1, NULL);
    gtk_style_context_add_provider_for_screen(
        gdk_screen_get_default(), GTK_STYLE_PROVIDER(css),
        GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    g_object_unref(css);
}

int main(void) {
    /* No argv parsing here on purpose -- the value to show always comes
     * from osd.state, written by osd-notify.sh right before it launches
     * or signals this process. That keeps "what to show" in one place
     * whether this is a fresh launch or a SIGUSR1 refresh, rather than
     * argv for the first show and a file for every refresh after it. */
    int argc = 0;
    gtk_init(&argc, NULL);

    const char *home = g_get_home_dir();
    g_snprintf(state_path, sizeof(state_path),
               "%s/.local/share/arktube-overlay/osd.state", home);
    g_snprintf(pid_path, sizeof(pid_path),
               "%s/.local/share/arktube-overlay/osd.pid", home);

    if (!write_pid_file()) {
        g_printerr("osd: failed to write PID file %s\n", pid_path);
        return 1;
    }

    window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_decorated(GTK_WINDOW(window), FALSE);
    gtk_widget_set_size_request(window, OSD_WIDTH, OSD_HEIGHT);

    if (!gtk_layer_is_supported()) {
        /* Same honest logging posture as overlay.py's own layer-shell
         * guard: don't pretend, log it, let init_for_window() below be
         * the real authority on whether it actually works. */
        g_printerr(
            "osd: gtk_layer_is_supported() returned FALSE -- compositor "
            "does not advertise wlr-layer-shell-v1; placement will fall "
            "back to whatever the window manager does with a plain "
            "undecorated toplevel.\n");
    }
    gtk_layer_init_for_window(GTK_WINDOW(window));
    gtk_layer_set_layer(GTK_WINDOW(window), GTK_LAYER_SHELL_LAYER_OVERLAY);
    gtk_layer_set_anchor(GTK_WINDOW(window), GTK_LAYER_SHELL_EDGE_TOP, TRUE);
    /* LEFT/RIGHT deliberately left unanchored -- gtk-layer-shell centers
     * a surface on any axis where neither of that axis's edges is
     * anchored, same horizontal-centering trick overlay.py's own
     * PANEL_GEOMETRY['osd'] uses. */
    gtk_layer_set_margin(GTK_WINDOW(window), GTK_LAYER_SHELL_EDGE_TOP, TOP_MARGIN);
    gtk_layer_set_exclusive_zone(GTK_WINDOW(window), -1);
    gtk_layer_set_keyboard_mode(GTK_WINDOW(window), GTK_LAYER_SHELL_KEYBOARD_MODE_NONE);

    apply_css();

    GtkWidget *box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 10);
    gtk_widget_set_margin_start(box, 20);
    gtk_widget_set_margin_end(box, 20);
    gtk_widget_set_margin_top(box, 18);
    gtk_widget_set_margin_bottom(box, 18);
    gtk_container_add(GTK_CONTAINER(window), box);

    title_label = gtk_label_new("");
    gtk_widget_set_halign(title_label, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(title_label), "osd-title");
    gtk_box_pack_start(GTK_BOX(box), title_label, FALSE, FALSE, 0);

    value_label = gtk_label_new("");
    gtk_widget_set_halign(value_label, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(value_label), "osd-value");
    gtk_box_pack_start(GTK_BOX(box), value_label, FALSE, FALSE, 0);

    level_bar = gtk_level_bar_new_for_interval(0.0, 1.0);
    gtk_widget_set_hexpand(level_bar, TRUE);
    gtk_box_pack_start(GTK_BOX(box), level_bar, FALSE, FALSE, 0);

    refresh_from_state();
    reset_hide_timer();

    /* g_unix_signal_add, not signal(2) -- runs the handler on the GLib
     * main loop instead of an arbitrary signal-delivery context, so it's
     * safe to touch GTK widgets directly from it (same reasoning
     * overlay.py's own module docstring gives for using GLib.idle_add
     * from its raw Python signal.signal() handlers instead of touching
     * GTK there directly). */
    g_unix_signal_add(SIGUSR1, on_usr1, NULL);

    gtk_widget_show_all(window);
    gtk_main();

    cleanup_pid_file();
    return 0;
}

/* power-menu.c — standalone Shut Down / Restart / Log Out menu.
 *
 * Same motivation as src/overlay/osd/osd.c (see that file's own top
 * comment): overlay.py's 'power' panel state (PANEL_GEOMETRY['power'],
 * driven over pywebview/WebKit) has not been reliably appearing, most
 * likely the same pywebview-vs-gtk-layer-shell realize-timing issue
 * suspected for the OSD -- see the is_layer_window() logging added to
 * overlay.py's attach_layer_shell() to actually confirm that. Until
 * that's confirmed and fixed, the physical Power button is wired
 * (20-arktube.conf's XF86PowerOff bindsym) straight to this binary
 * instead of overlay.py's SIGUSR2 handler -- a plain GTK3 +
 * gtk-layer-shell program, same dependency chain (and already-proven
 * correctness) as osd.c, nothing shared with overlay.py/pywebview at
 * all. Given what a wrong guess here costs (an actual, immediate
 * shutdown/reboot), this deliberately does the simplest, most directly
 * verifiable thing rather than anything clever.
 *
 * Singleton via a PID file, same reasoning as osd-notify.sh: a second
 * physical Power press while the menu is already up should not spawn a
 * second overlapping window (and must never be interpreted as "confirm
 * whatever's focused" -- see main()'s own comment on that). Unlike the
 * OSD, there's no live value to refresh here, so a second press while
 * one is already running just does nothing and lets the existing menu
 * keep waiting for an explicit choice.
 */

#include <gtk/gtk.h>
#include <gtk-layer-shell.h>
#include <glib/gstdio.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <signal.h>
#include <unistd.h>
#include <ctype.h>

#define MENU_WIDTH 420
#define MENU_HEIGHT 300

static GtkWidget *window;
static char pid_path[PATH_MAX];

static void cleanup_pid_file(void) {
    gchar *contents = NULL;
    if (g_file_get_contents(pid_path, &contents, NULL, NULL)) {
        char mine[32];
        g_snprintf(mine, sizeof(mine), "%d", (int)getpid());
        if (g_strcmp0(g_strstrip(contents), mine) == 0) g_remove(pid_path);
        g_free(contents);
    }
}

static gboolean already_running(void) {
    gchar *contents = NULL;
    if (!g_file_get_contents(pid_path, &contents, NULL, NULL)) return FALSE;
    pid_t pid = (pid_t)g_ascii_strtoll(g_strstrip(contents), NULL, 10);
    g_free(contents);
    if (pid <= 0) return FALSE;
    /* kill(pid, 0) -- existence check only, sends nothing. */
    return kill(pid, 0) == 0;
}

static gboolean write_pid_file(void) {
    char buf[32];
    g_snprintf(buf, sizeof(buf), "%d", (int)getpid());
    return g_file_set_contents(pid_path, buf, -1, NULL);
}

/* fork+execvp, not system()/g_spawn_command_line_*() -- no shell
 * involved at all, so there is no quoting/injection surface to reason
 * about even though every argv here is currently a fixed literal.
 * Waits for the child so poweroff/reboot/terminate-session have
 * actually been issued before this process's own gtk_main_quit() below
 * tears down its window -- doesn't matter for poweroff/reboot (the
 * whole machine is going down regardless) but matters for logout,
 * where this process's own exit is otherwise racing loginctl's request
 * against Sway's own teardown of everything, this program included. */
static void run_argv(char *const argv[]) {
    pid_t pid = fork();
    if (pid == 0) {
        execvp(argv[0], argv);
        _exit(127); /* execvp only returns on failure */
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
    }
}

/* Mirrors overlay.py's own logout()/SystemAPI.logout() precisely: prefer
 * $XDG_SESSION_ID (what pam_systemd already exports into every login
 * session, so it doesn't depend on `show-session self`'s "self" alias
 * needing systemd-233+), fall back to querying it, and do nothing
 * destructive if neither is available -- see overlay.py's own logout()
 * docstring for the full reasoning; not repeated here.
 *
 * Written into a fixed-size buffer, not returned as an allocated
 * string, and validated to be alnum-only -- loginctl session IDs are
 * short alnum tokens (`3`, `c1`, ...), and even though this only ever
 * reaches execvp() (no shell to inject into), rejecting anything
 * unexpected here is one extra guard against ever handing something
 * malformed to terminate-session. */
static gboolean get_session_id(char *out, size_t out_len) {
    const char *env = g_getenv("XDG_SESSION_ID");
    if (env && *env) {
        g_strlcpy(out, env, out_len);
    } else {
        char *stdout_buf = NULL;
        char *argv[] = {"loginctl", "show-session", "self", "-p", "Id", "--value", NULL};
        gint exit_status = 0;
        if (!g_spawn_sync(NULL, argv, NULL, G_SPAWN_SEARCH_PATH, NULL, NULL,
                           &stdout_buf, NULL, &exit_status, NULL) ||
            exit_status != 0 || !stdout_buf) {
            g_free(stdout_buf);
            return FALSE;
        }
        g_strlcpy(out, g_strstrip(stdout_buf), out_len);
        g_free(stdout_buf);
    }
    if (!*out) return FALSE;
    for (const char *p = out; *p; p++) {
        if (!g_ascii_isalnum(*p)) return FALSE;
    }
    return TRUE;
}

static void quit_now(void) {
    cleanup_pid_file();
    gtk_main_quit();
}

static void on_shutdown(GtkButton *b, gpointer data) {
    (void)b; (void)data;
    char *argv[] = {"systemctl", "poweroff", NULL};
    run_argv(argv);
    quit_now();
}

static void on_reboot(GtkButton *b, gpointer data) {
    (void)b; (void)data;
    char *argv[] = {"systemctl", "reboot", NULL};
    run_argv(argv);
    quit_now();
}

static void on_logout(GtkButton *b, gpointer data) {
    (void)b; (void)data;
    char session_id[64];
    if (get_session_id(session_id, sizeof(session_id))) {
        char *argv[] = {"loginctl", "terminate-session", session_id, NULL};
        run_argv(argv);
    } else {
        g_printerr(
            "power-menu: could not determine a session ID (neither "
            "$XDG_SESSION_ID nor `loginctl show-session self` worked) "
            "-- not calling terminate-session with nothing to target. "
            "Closing the menu without logging out.\n");
    }
    quit_now();
}

static void on_cancel(GtkButton *b, gpointer data) {
    (void)b; (void)data;
    quit_now();
}

/* Escape always just closes the menu -- it never confirms whatever
 * button happens to have focus. A stray Enter from a remote/keyboard
 * landing on a focused Shut Down button is exactly the kind of mistake
 * this file exists to make hard to make by accident, so focus starts
 * on Cancel (see main()) rather than on the first/most dangerous
 * button, unlike overlay.py's own power-panel which focused
 * powerPanelButtons[0]. */
static gboolean on_key_press(GtkWidget *w, GdkEventKey *event, gpointer data) {
    (void)w; (void)data;
    if (event->keyval == GDK_KEY_Escape) {
        quit_now();
        return TRUE;
    }
    return FALSE;
}

static void apply_css(void) {
    GtkCssProvider *css = gtk_css_provider_new();
    gtk_css_provider_load_from_data(css,
        "window { background-color: rgba(18,18,22,0.96); border-radius: 16px; }"
        "label.pm-title { color: #ffffff; font-size: 16px; font-weight: 700; }"
        "button.pm-btn { background: rgba(255,255,255,0.06); color: #ffffff; "
        "  border: 1px solid rgba(255,255,255,0.1); border-radius: 10px; "
        "  padding: 10px 14px; font-size: 14px; }"
        "button.pm-btn:hover, button.pm-btn:focus { background: rgba(79,140,255,0.25); "
        "  border-color: #4f8cff; }"
        "button.pm-danger:hover, button.pm-danger:focus { "
        "  background: rgba(255,90,90,0.25); border-color: #ff5a5a; }",
        -1, NULL);
    gtk_style_context_add_provider_for_screen(
        gdk_screen_get_default(), GTK_STYLE_PROVIDER(css),
        GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    g_object_unref(css);
}

static GtkWidget *make_button(const char *label, gboolean danger,
                               void (*handler)(GtkButton *, gpointer)) {
    GtkWidget *btn = gtk_button_new_with_label(label);
    GtkStyleContext *ctx = gtk_widget_get_style_context(btn);
    gtk_style_context_add_class(ctx, "pm-btn");
    if (danger) gtk_style_context_add_class(ctx, "pm-danger");
    g_signal_connect(btn, "clicked", G_CALLBACK(handler), NULL);
    return btn;
}

int main(void) {
    int argc = 0;
    gtk_init(&argc, NULL);

    const char *home = g_get_home_dir();
    g_snprintf(pid_path, sizeof(pid_path),
               "%s/.local/share/arktube-overlay/power-menu.pid", home);

    if (already_running()) {
        /* Deliberately a silent no-op, not a refocus/toggle-closed --
         * see this file's own top comment. Nothing to clean up; the
         * running instance owns the PID file. */
        return 0;
    }
    if (!write_pid_file()) {
        g_printerr("power-menu: failed to write PID file %s\n", pid_path);
        return 1;
    }

    window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_decorated(GTK_WINDOW(window), FALSE);
    gtk_widget_set_size_request(window, MENU_WIDTH, MENU_HEIGHT);

    if (!gtk_layer_is_supported()) {
        g_printerr(
            "power-menu: gtk_layer_is_supported() returned FALSE -- "
            "compositor does not advertise wlr-layer-shell-v1.\n");
    }
    gtk_layer_init_for_window(GTK_WINDOW(window));
    gtk_layer_set_layer(GTK_WINDOW(window), GTK_LAYER_SHELL_LAYER_OVERLAY);
    /* No edges anchored on either axis -- gtk-layer-shell centers a
     * surface on any axis where neither edge is anchored, same as
     * overlay.py's own PANEL_GEOMETRY['power'] (anchors=()). */
    gtk_layer_set_exclusive_zone(GTK_WINDOW(window), -1);
    /* EXCLUSIVE, not NONE (unlike osd.c) -- this menu needs real
     * keyboard input (Tab/arrow-key focus, Enter, Escape), same
     * reasoning as overlay.py's own PANEL_GEOMETRY['power'] entry. The
     * session-crash this was originally suspected of causing (see
     * chat history) turned out to be the separate pkill/systemd-inhibit
     * signal-collision bug, already fixed -- EXCLUSIVE keyboard mode
     * itself was never the actual problem.
     */
    gtk_layer_set_keyboard_mode(GTK_WINDOW(window), GTK_LAYER_SHELL_KEYBOARD_MODE_EXCLUSIVE);

    apply_css();

    GtkWidget *outer = gtk_box_new(GTK_ORIENTATION_VERTICAL, 16);
    gtk_widget_set_margin_start(outer, 24);
    gtk_widget_set_margin_end(outer, 24);
    gtk_widget_set_margin_top(outer, 22);
    gtk_widget_set_margin_bottom(outer, 22);
    gtk_container_add(GTK_CONTAINER(window), outer);

    GtkWidget *title = gtk_label_new("Power");
    gtk_widget_set_halign(title, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(title), "pm-title");
    gtk_box_pack_start(GTK_BOX(outer), title, FALSE, FALSE, 0);

    GtkWidget *btn_shutdown = make_button("Shut Down", TRUE, on_shutdown);
    GtkWidget *btn_reboot   = make_button("Restart", FALSE, on_reboot);
    GtkWidget *btn_logout   = make_button("Log Out", FALSE, on_logout);
    GtkWidget *btn_cancel   = make_button("Cancel", FALSE, on_cancel);

    gtk_box_pack_start(GTK_BOX(outer), btn_shutdown, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(outer), btn_reboot, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(outer), btn_logout, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(outer), btn_cancel, FALSE, FALSE, 0);

    g_signal_connect(window, "key-press-event", G_CALLBACK(on_key_press), NULL);
    g_signal_connect(window, "destroy", G_CALLBACK(quit_now), NULL);

    gtk_widget_show_all(window);
    /* Focus starts on Cancel, not Shut Down -- see on_key_press()'s own
     * comment. GTK's default Tab/arrow-key focus chain (the box's
     * declaration order above) handles moving between the four buttons;
     * nothing custom needed for that, unlike overlay.py's own
     * powerPanel keydown handler which had to hand-roll it in JS. */
    gtk_widget_grab_focus(btn_cancel);

    gtk_main();

    cleanup_pid_file();
    return 0;
}

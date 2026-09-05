#ifndef CG_LAYER_SHELL_H
#define CG_LAYER_SHELL_H

#include <wayland-server-core.h>
#include <wlr/types/wlr_layer_shell_v1.h>
#include <wlr/types/wlr_scene.h>

#include "server.h"

/*
 * cg_layer_surface wraps one wlr_layer_surface_v1 client (e.g.
 * session/overlay's overlay.py, ported to gtk-layer-shell -- see
 * docs/foundational/CAGE-MIGRATION.md and the root README's "Session
 * controls" section for why this exists at all: pywebview's x=0,y=0/
 * on_top=True window model has no Wayland equivalent, but wlr-layer-shell
 * does, and it's the one Wayland compositors actually offer clients for
 * this exact job).
 *
 * Scope, stated up front rather than discovered mid-review:
 *
 *   - Single-output only. If a layer surface doesn't request a specific
 *     output, it's assigned the first output in server->outputs. Cage
 *     itself is a single-app kiosk; the realistic deployment here is one
 *     display, and multi-output layer-surface placement (the client
 *     creating one layer surface per wl_output, the way waybar does) is
 *     not something this kiosk's own overlay needs to do.
 *   - Exclusive zones are accepted (so the protocol's own bookkeeping in
 *     wlr_scene_layer_surface_v1_configure stays correct for the client),
 *     but NOT enforced against the primary view: ARKtube's toplevel is
 *     still sized to the output's full geometry regardless of any layer
 *     surface's exclusive_zone. This overlay always asks for
 *     exclusive_zone -1 ("don't reserve space, just float above") for
 *     exactly this reason -- see overlay.py -- so this simplification
 *     costs nothing for the one client this compositor actually runs
 *     today. A general-purpose compositor would need real usable-area
 *     tracking; this kiosk does not.
 *   - A layer surface changing its own `layer` after the initial map
 *     (rare; the protocol allows it) is not handled -- it keeps
 *     rendering in whichever of the four scene trees it was placed in
 *     at map time. Our own overlay and its lock screen each pick a
 *     fixed layer for their whole lifetime, so this doesn't affect them.
 */
struct cg_layer_surface {
	struct wl_list link; // cg_server::layer_surfaces
	struct cg_server *server;
	struct wlr_layer_surface_v1 *layer_surface;
	struct wlr_scene_layer_surface_v1 *scene;

	bool keyboard_focused;

	struct wl_listener map;
	struct wl_listener unmap;
	struct wl_listener destroy;
	struct wl_listener surface_commit;
	struct wl_listener new_popup;
	struct wl_listener output_destroy;
};

void handle_new_layer_shell_surface(struct wl_listener *listener, void *data);

/* Re-run placement for every mapped layer surface assigned to `output`
 * (or every output, if output is NULL) -- call this whenever an output's
 * usable geometry changes (mode change, new output, output removed). */
void layer_shell_arrange(struct cg_server *server, struct wlr_output *output);

/* A pointer button press landed on `surface` and it didn't belong to a
 * view (seat.c already checked). If `surface` is a layer-shell surface
 * requesting on_demand keyboard interactivity, give it real seat keyboard
 * focus. No-op for anything else. Kept separate from seat_set_focus()/
 * seat_get_focus(), which only know about cg_view, on purpose -- see
 * seat.c's desktop_surface_at() comment for why layer surfaces
 * deliberately never populate the scene node's view-shaped node->data. */
void layer_shell_handle_pointer_press(struct cg_server *server, struct wlr_surface *surface);

#endif

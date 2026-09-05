/*
 * Cage: A Wayland kiosk.
 *
 * wlr-layer-shell-v1 support, added for ARKtube's webtop session (see
 * layer_shell.h for the scope this deliberately covers and doesn't).
 */

#define _POSIX_C_SOURCE 200809L

#include "config.h"

#include <stdlib.h>
#include <wayland-server-core.h>
#include <wlr/types/wlr_layer_shell_v1.h>
#include <wlr/types/wlr_output.h>
#include <wlr/types/wlr_output_layout.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/util/log.h>

#include "layer_shell.h"
#include "output.h"
#include "seat.h"
#include "server.h"
#include "view.h"

static struct wlr_scene_tree *
tree_for_layer(struct cg_server *server, enum zwlr_layer_shell_v1_layer layer)
{
	switch (layer) {
	case ZWLR_LAYER_SHELL_V1_LAYER_BACKGROUND:
		return server->layer_bg_tree;
	case ZWLR_LAYER_SHELL_V1_LAYER_BOTTOM:
		return server->layer_bottom_tree;
	case ZWLR_LAYER_SHELL_V1_LAYER_TOP:
		return server->layer_top_tree;
	case ZWLR_LAYER_SHELL_V1_LAYER_OVERLAY:
		return server->layer_overlay_tree;
	}
	/* Unreached with a spec-conforming client; fail safe onto "top"
	 * rather than crash on a malformed enum value. */
	return server->layer_top_tree;
}

static void
arrange_one(struct cg_layer_surface *layer_surface)
{
	struct wlr_layer_surface_v1 *wlr_layer_surface = layer_surface->layer_surface;
	struct wlr_output *output = wlr_layer_surface->output;
	if (!output || !output->enabled) {
		return;
	}

	struct wlr_box full_area = {0};
	wlr_output_layout_get_box(layer_surface->server->output_layout, output, &full_area);
	/* wlr_scene_layer_surface_v1_configure wants output-local areas
	 * (0,0 origin), not the layout-relative box output_layout hands
	 * back -- same convention every other wlroots-based compositor's
	 * layer-shell arrangement code follows. */
	full_area.x = 0;
	full_area.y = 0;

	/* usable_area would shrink as layer surfaces claim exclusive
	 * zones, and in a general-purpose compositor would feed back into
	 * how the primary view is sized. This kiosk deliberately doesn't
	 * do that -- see layer_shell.h's header comment -- so usable_area
	 * is only here because wlr_scene_layer_surface_v1_configure
	 * requires the parameter; its output value is unused. */
	struct wlr_box usable_area = full_area;
	wlr_scene_layer_surface_v1_configure(layer_surface->scene, &full_area, &usable_area);
}

void
layer_shell_arrange(struct cg_server *server, struct wlr_output *output)
{
	struct cg_layer_surface *layer_surface;
	wl_list_for_each (layer_surface, &server->layer_surfaces, link) {
		if (!layer_surface->layer_surface->surface->mapped) {
			continue;
		}
		if (output && layer_surface->layer_surface->output != output) {
			continue;
		}
		arrange_one(layer_surface);
	}
}

static void
focus_keyboard(struct cg_layer_surface *layer_surface)
{
	struct wlr_seat *seat = layer_surface->server->seat->seat;
	struct wlr_keyboard *keyboard = wlr_seat_get_keyboard(seat);

	layer_surface->keyboard_focused = true;
	if (keyboard) {
		wlr_seat_keyboard_notify_enter(seat, layer_surface->layer_surface->surface, keyboard->keycodes,
						keyboard->num_keycodes, &keyboard->modifiers);
	} else {
		wlr_seat_keyboard_notify_enter(seat, layer_surface->layer_surface->surface, NULL, 0, NULL);
	}
}

static void
restore_keyboard_to_focused_view(struct cg_server *server)
{
	if (!wl_list_empty(&server->views)) {
		struct cg_view *view = wl_container_of(server->views.next, view, link);
		seat_set_focus(server->seat, view);
	}
}

static void
handle_layer_surface_map(struct wl_listener *listener, void *data)
{
	struct cg_layer_surface *layer_surface = wl_container_of(listener, layer_surface, map);

	if (layer_surface->layer_surface->current.keyboard_interactive ==
	    ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_EXCLUSIVE) {
		focus_keyboard(layer_surface);
	}
}

static void
handle_layer_surface_unmap(struct wl_listener *listener, void *data)
{
	struct cg_layer_surface *layer_surface = wl_container_of(listener, layer_surface, unmap);
	struct cg_server *server = layer_surface->server;

	if (!layer_surface->keyboard_focused) {
		return;
	}
	layer_surface->keyboard_focused = false;

	/* Only reclaim focus for ARKtube if this surface still actually
	 * holds it -- it may already have lost focus to something else
	 * (e.g. a second exclusive layer surface mapped over it) before
	 * unmapping. */
	if (server->seat->seat->keyboard_state.focused_surface == layer_surface->layer_surface->surface) {
		restore_keyboard_to_focused_view(server);
	}
}

static void
handle_layer_surface_destroy(struct wl_listener *listener, void *data)
{
	struct cg_layer_surface *layer_surface = wl_container_of(listener, layer_surface, destroy);

	wl_list_remove(&layer_surface->link);
	wl_list_remove(&layer_surface->map.link);
	wl_list_remove(&layer_surface->unmap.link);
	wl_list_remove(&layer_surface->destroy.link);
	wl_list_remove(&layer_surface->surface_commit.link);
	wl_list_remove(&layer_surface->new_popup.link);
	if (layer_surface->output_destroy.notify) {
		wl_list_remove(&layer_surface->output_destroy.link);
	}
	free(layer_surface);
}

static void
handle_layer_surface_commit(struct wl_listener *listener, void *data)
{
	struct cg_layer_surface *layer_surface = wl_container_of(listener, layer_surface, surface_commit);

	if (layer_surface->layer_surface->initial_commit) {
		/* The protocol makes output assignment the compositor's job
		 * when a client doesn't request one. See layer_shell.h: this
		 * kiosk only ever expects one output in practice, so "the
		 * first output" is a reasonable, stated simplification
		 * rather than a general-purpose policy. */
		if (!layer_surface->layer_surface->output) {
			if (!wl_list_empty(&layer_surface->server->outputs)) {
				struct cg_output *output =
					wl_container_of(layer_surface->server->outputs.next, output, link);
				layer_surface->layer_surface->output = output->wlr_output;
			} else {
				wlr_log(WLR_ERROR, "New layer surface but no output is available; closing it");
				wlr_layer_surface_v1_destroy(layer_surface->layer_surface);
				return;
			}
		}
	}

	arrange_one(layer_surface);
}

static void
handle_new_popup(struct wl_listener *listener, void *data)
{
	/* Scoped out on purpose -- see layer_shell.h. Popups render (as a
	 * scene child of their layer surface) but aren't unconstrained to
	 * the output box the way a view's popups are in
	 * xdg_shell.c:popup_unconstrain(). Not exercised by this session's
	 * own overlay, which renders its dropdowns within a single
	 * WebKitGTK surface rather than as real xdg_popups. */
	struct cg_layer_surface *layer_surface = wl_container_of(listener, layer_surface, new_popup);
	struct wlr_xdg_popup *popup = data;

	wlr_scene_xdg_surface_create(layer_surface->scene->tree, popup->base);
}

static void
handle_output_destroy(struct wl_listener *listener, void *data)
{
	struct cg_layer_surface *layer_surface = wl_container_of(listener, layer_surface, output_destroy);
	wlr_layer_surface_v1_destroy(layer_surface->layer_surface);
}

void
handle_new_layer_shell_surface(struct wl_listener *listener, void *data)
{
	struct cg_server *server = wl_container_of(listener, server, new_layer_surface);
	struct wlr_layer_surface_v1 *wlr_layer_surface = data;

	struct cg_layer_surface *layer_surface = calloc(1, sizeof(struct cg_layer_surface));
	if (!layer_surface) {
		wlr_log(WLR_ERROR, "Failed to allocate cg_layer_surface");
		wlr_layer_surface_v1_destroy(wlr_layer_surface);
		return;
	}

	layer_surface->server = server;
	layer_surface->layer_surface = wlr_layer_surface;

	struct wlr_scene_tree *parent_tree = tree_for_layer(server, wlr_layer_surface->pending.layer);
	layer_surface->scene = wlr_scene_layer_surface_v1_create(parent_tree, wlr_layer_surface);
	if (!layer_surface->scene) {
		wlr_log(WLR_ERROR, "Failed to allocate scene node for layer surface");
		free(layer_surface);
		wlr_layer_surface_v1_destroy(wlr_layer_surface);
		return;
	}
	/* Deliberately NOT setting layer_surface->scene->tree->node.data:
	 * leaving it NULL is what tells seat.c's desktop_surface_at() (and
	 * anything else walking node->data looking for a cg_view) that
	 * this subtree isn't a view. See that function's own comment. */

	wlr_layer_surface->data = layer_surface;

	wl_list_insert(&server->layer_surfaces, &layer_surface->link);

	layer_surface->map.notify = handle_layer_surface_map;
	wl_signal_add(&wlr_layer_surface->surface->events.map, &layer_surface->map);
	layer_surface->unmap.notify = handle_layer_surface_unmap;
	wl_signal_add(&wlr_layer_surface->surface->events.unmap, &layer_surface->unmap);
	layer_surface->destroy.notify = handle_layer_surface_destroy;
	wl_signal_add(&wlr_layer_surface->events.destroy, &layer_surface->destroy);
	layer_surface->surface_commit.notify = handle_layer_surface_commit;
	wl_signal_add(&wlr_layer_surface->surface->events.commit, &layer_surface->surface_commit);
	layer_surface->new_popup.notify = handle_new_popup;
	wl_signal_add(&wlr_layer_surface->events.new_popup, &layer_surface->new_popup);

	if (wlr_layer_surface->output) {
		layer_surface->output_destroy.notify = handle_output_destroy;
		wl_signal_add(&wlr_layer_surface->output->events.destroy, &layer_surface->output_destroy);
	} else {
		layer_surface->output_destroy.notify = NULL;
	}
}

void
layer_shell_handle_pointer_press(struct cg_server *server, struct wlr_surface *surface)
{
	struct wlr_layer_surface_v1 *wlr_layer_surface = wlr_layer_surface_v1_try_from_wlr_surface(surface);
	if (!wlr_layer_surface) {
		return;
	}

	if (wlr_layer_surface->current.keyboard_interactive != ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ON_DEMAND) {
		return;
	}

	struct cg_layer_surface *layer_surface;
	wl_list_for_each (layer_surface, &server->layer_surfaces, link) {
		if (layer_surface->layer_surface == wlr_layer_surface) {
			focus_keyboard(layer_surface);
			return;
		}
	}
}

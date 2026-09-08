#!/usr/bin/env bash
#
# uninstall.sh — reverses everything install.sh sets up: the gear-menu
# entry, ARKtube's /etc/sway/config.d/* fragments, the sway-session.target
# unit, and the deployed overlay. See install.sh's own header for what
# each of those is and why it lives where it does.
#
# Deliberately narrow, mirroring install.sh's own scope (see its "What
# this is not" reference in the root README): this removes what
# install.sh *placed*, not the packages install.sh had apt install --
# sway, network-manager, wireplumber, etc. are shared system packages
# that other things on the machine may also depend on, so removing
# them is opt-in via --purge-packages below, never the default.
#
# Safe to run whether or not install.sh ever fully completed — every
# step here checks for what it's about to remove first and skips
# quietly if it's already gone, the same "checked directly" approach
# install.sh itself uses for the config.d include line.
set -euo pipefail

PURGE_PACKAGES=0
for arg in "$@"; do
    case "$arg" in
        --purge-packages)
            PURGE_PACKAGES=1
            ;;
        -h|--help)
            cat <<'EOF'
Usage: ./uninstall.sh [--purge-packages]

Removes the gear-menu entry, ARKtube's Sway config fragments, the
sway-session.target user unit, and the deployed overlay.

  --purge-packages   Also `apt-get remove` the packages install.sh
                      installed (sway, gjs, gir1.2-webkit2-4.1,
                      gir1.2-gtklayershell-0.1, network-manager,
                      wireplumber, pulseaudio-utils, brightnessctl,
                      upower). Off by default since these are shared
                      system packages other software may depend on.
                      Does not touch arktube_linux itself — that's
                      main's own binary, outside this script's scope,
                      same boundary install.sh draws.
EOF
            exit 0
            ;;
        *)
            echo "unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

if [ "$(id -u)" -eq 0 ]; then
    echo "warning: running as root. This script sudo's the system-level" >&2
    echo "         steps itself and expects the per-user steps (systemd" >&2
    echo "         --user, ~/.local/share/arktube-overlay) to run as the" >&2
    echo "         actual account ARKtube was installed for, not root." >&2
fi

echo "==> Removing the gear-menu entry"
if [ -f /usr/share/wayland-sessions/arktube.desktop ]; then
    sudo rm -f /usr/share/wayland-sessions/arktube.desktop
else
    echo "    already gone — nothing to remove"
fi

echo "==> Removing ARKtube's Sway config"
for f in 20-arktube.conf 10-systemd.conf; do
    if [ -f "/etc/sway/config.d/$f" ]; then
        sudo rm -f "/etc/sway/config.d/$f"
    else
        echo "    /etc/sway/config.d/$f already gone — nothing to remove"
    fi
done
# The `include /etc/sway/config.d/*` line install.sh may have appended
# to /etc/sway/config is deliberately left in place, not stripped back
# out: it's a no-op with an empty config.d/ (Sway's own stock config
# already ships that line on most installs anyway — install.sh only
# appended it when it found it missing), and anything else that's
# since dropped a fragment into config.d/ would break if this removed
# the include out from under it. Consistent with this project's
# standing rule of never editing a package-owned conffile more
# aggressively than the minimum needed.

echo "==> Stopping and removing the sway-session.target unit"
if systemctl --user list-unit-files sway-session.target >/dev/null 2>&1; then
    systemctl --user stop sway-session.target 2>/dev/null || true
fi
if [ -f "${HOME}/.config/systemd/user/sway-session.target" ]; then
    rm -f "${HOME}/.config/systemd/user/sway-session.target"
    systemctl --user daemon-reload 2>/dev/null || true
else
    echo "    already gone — nothing to remove"
fi

# Power-key handling is no longer a systemd-logind config change to
# undo here: install.sh now hands that key to overlay.py via a
# `systemd-inhibit --what=handle-power-key` runtime lock (see
# 20-arktube.conf), which is scoped to overlay.py's own process and
# releases itself the moment that process exits — nothing under
# /etc/systemd was ever written for it, so there's nothing to restore.

# The video-group membership install.sh grants for brightnessctl is
# deliberately left alone here, same reasoning as leaving the pip
# packages below: it's a general account permission (any other
# brightness/backlight tool on the same account benefits from it too),
# not something ARKtube-specific to claw back on uninstall.

echo "==> Removing the deployed overlay"
if pgrep -f "$HOME/.local/share/arktube-overlay/overlay.js" >/dev/null 2>&1; then
    pkill -f "$HOME/.local/share/arktube-overlay/overlay.js" || true
fi
if [ -d "${HOME}/.local/share/arktube-overlay" ]; then
    rm -rf "${HOME}/.local/share/arktube-overlay"
else
    echo "    already gone — nothing to remove"
fi
# No pip-installed dependencies to leave alone any more -- overlay.js is
# GJS, not Python, and `gjs` itself is only removed via --purge-packages
# below, same as every other apt package install.sh pulled in.

if [ "$PURGE_PACKAGES" -eq 1 ]; then
    echo "==> Removing packages installed for ARKtube (--purge-packages)"
    sudo apt-get remove -y \
        sway \
        gjs gir1.2-webkit2-4.1 gir1.2-gtklayershell-0.1 \
        network-manager wireplumber pulseaudio-utils brightnessctl upower
else
    echo "==> Leaving packages installed (sway, network-manager, wireplumber, etc.)"
    echo "    Re-run with --purge-packages to remove them too."
fi

cat <<'EOF'

==> Done.

ARKtube is no longer selectable from the GDM gear menu, and its Sway
config, session unit, and overlay are removed. arktube_linux itself
(the ARKtube binary from `main`) was not touched — that's outside
this script's scope, same as it was outside install.sh's; remove it
the same way it was installed if it's no longer wanted either.
EOF

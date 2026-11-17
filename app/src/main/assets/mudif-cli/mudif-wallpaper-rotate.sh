#!/bin/sh
# mudif-wallpaper-rotate.sh (p91, p95): alternate the desktop wallpaper
# between the two new p91 wallpapers (premium, green) on every
# desktop start and every 30 minutes while the desktop runs.
#
# State: /root/.config/mudif/wallpaper-index holds 0 (premium) or
# 1 (green), the index of the wallpaper currently applied. A
# missing or unreadable state means "never rotated yet": the first
# run applies premium (index 0), because the staged default is
# still the gradient wallpaper.
#
# Single instance: flock on the state file. A second copy (a
# desktop restart while the old session's rotator still lives)
# exits silently; the old one keeps its own 30-minute cadence.
# p95: every sleep below closes fd 9 (sleep ... 9>&-), so an
# orphaned sleep from a dead rotator cannot hold the lock: the
# p93 R2 round proved a dead S6 rotator's orphaned `sleep 1800`
# (pid 65348) held fd 9 until ~20:56 and every new rotator copy
# (S7 autostart + two manual runs) exited silently at flock -n 9.
# With the fd closed the sleep holds no lock, so a new rotator
# can start while the old sleep is still counting down.
#
# Fail-closed: a missing wallpaper file does nothing (the desktop
# is never blanked); a missing pcmanfm-qt process exits silently;
# a failed --set-wallpaper leaves the state untouched so the next
# run retries the same target. p92 F2: a set that exits 0 without
# changing settings.conf is also a failure (the desktop manager
# was absent), so the state is written only after settings.conf
# shows the new Wallpaper value. Nothing is ever killed. No log is
# written (the guest disk is tight).
# p95: a failed apply is retried up to 3 times, 10 s apart, instead
# of exiting: one transient failure (the desktop still starting,
# settings.conf mid-write) used to kill the autostart rotator for
# the whole session. A persistent failure keeps the same target
# for the next 30-minute cycle (the state is untouched, so next
# is not flipped).
# p98: a theme pins its wallpaper (wallpaper-pinned holds the
# theme id, written by LxqtLook on every stage and switch). While
# pinned, the loop no-ops (see the pin check inside the loop):
# the theme's wallpaper stays. The script, the autostart entry
# and the state file stay in place; only the flip pauses.
#
# No systemd in the proot guest: this script is started by the
# LXQt autostart entry mudif-wallpaper-rotate.desktop, which runs
# on every desktop start (same mechanism as xrdb-xresources).

PREMIUM=/usr/share/backgrounds/mudif-wallpaper-premium.png
GREEN=/usr/share/backgrounds/mudif-wallpaper-green.png
STATE_DIR=/root/.config/mudif
STATE=$STATE_DIR/wallpaper-index
SETTINGS=/root/.config/pcmanfm-qt/lxqt/settings.conf

# The session must be up: no DISPLAY, no desktop to change.
[ -n "$DISPLAY" ] || exit 0
command -v pcmanfm-qt >/dev/null 2>&1 || exit 0
command -v flock >/dev/null 2>&1 || exit 0

# The desktop may still be starting when the autostart entry
# fires: wait up to 60 s for pcmanfm-qt, then give up silently.
tries=0
while ! pgrep -x pcmanfm-qt >/dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -ge 12 ] && exit 0
    sleep 5
done

mkdir -p "$STATE_DIR" || exit 0

# Open the state file read/write (created if missing, never
# truncated) and take the exclusive lock; a second copy exits.
exec 9<>"$STATE" || exit 0
flock -n 9 || exit 0

apply() {
    # $1 = index to apply (0 premium, 1 green). Returns 0 only
    # when the wallpaper was set AND the state was written.
    # p92 F2: pcmanfm-qt --set-wallpaper exits 0 as a no-op
    # when the desktop manager is absent, so the exit code
    # alone cannot prove the change. The state is written
    # only after settings.conf shows the new Wallpaper value
    # (polled up to 5 s for a slow config write). A mismatch
    # leaves the state untouched, so the next run retries
    # the same target. The -x whole-line match keeps
    # WallpaperMode=stretch from ever matching.
    target=
    if [ "$1" = "0" ]; then
        target=$PREMIUM
    else
        target=$GREEN
    fi
    [ -f "$target" ] || return 1
    pcmanfm-qt --set-wallpaper "$target" || return 1
    confirm=0
    while ! grep -Fqx "Wallpaper=$target" "$SETTINGS" 2>/dev/null; do
        confirm=$((confirm + 1))
        [ "$confirm" -ge 5 ] && return 1
        sleep 1 9>&-
    done
    printf '%s\n' "$1" > "$STATE" || return 1
    return 0
}

current=$(cat "$STATE" 2>/dev/null)
case "$current" in
    0) next=1 ;;
    1) next=0 ;;
    *) next=0 ;;
esac

while true; do
    # p98: a theme pins its wallpaper (wallpaper-pinned holds the
    # theme id). While pinned, the loop no-ops: no flip on the
    # timer and no flip on desktop start. A rotator started
    # unpinned stops flipping the moment a theme switch writes
    # the pin mid-session.
    if [ -f "$STATE_DIR/wallpaper-pinned" ]; then
        sleep 1800 9>&-
        continue
    fi
    # p95: retry a failed apply (up to 3 attempts, 10 s apart)
    # instead of exiting: one transient failure must not kill
    # the autostart rotator for the whole session. A persistent
    # failure keeps retrying the SAME target: apply() leaves
    # the state untouched on failure, so next is not flipped.
    attempt=0
    until apply "$next"; do
        attempt=$((attempt + 1))
        [ "$attempt" -ge 3 ] && break
        sleep 10 9>&-
    done
    if [ "$attempt" -ge 3 ]; then
        sleep 1800 9>&-
        continue
    fi
    if [ "$next" = "0" ]; then
        next=1
    else
        next=0
    fi
    # p95: close fd 9 before sleeping (see the header note).
    sleep 1800 9>&-
done

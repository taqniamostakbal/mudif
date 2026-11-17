#!/bin/sh
# mudif-wallpaper-rotate.sh (p91): alternate the desktop wallpaper
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
#
# Fail-closed: a missing wallpaper file does nothing (the desktop
# is never blanked); a missing pcmanfm-qt process exits silently;
# a failed --set-wallpaper leaves the state untouched so the next
# run retries the same target. Nothing is ever killed. No log is
# written (the guest disk is tight).
#
# No systemd in the proot guest: this script is started by the
# LXQt autostart entry mudif-wallpaper-rotate.desktop, which runs
# on every desktop start (same mechanism as xrdb-xresources).

PREMIUM=/usr/share/backgrounds/mudif-wallpaper-premium.png
GREEN=/usr/share/backgrounds/mudif-wallpaper-green.png
STATE_DIR=/root/.config/mudif
STATE=$STATE_DIR/wallpaper-index

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
    target=
    if [ "$1" = "0" ]; then
        target=$PREMIUM
    else
        target=$GREEN
    fi
    [ -f "$target" ] || return 1
    pcmanfm-qt --set-wallpaper "$target" || return 1
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
    apply "$next" || exit 0
    if [ "$next" = "0" ]; then
        next=1
    else
        next=0
    fi
    sleep 1800
done

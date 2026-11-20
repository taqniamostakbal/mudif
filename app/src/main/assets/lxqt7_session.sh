#!/bin/sh
# Mudif nested desktop launcher (Option B, p121; minimal session p123).
# Runs Xvfb on :7 + x11vnc + startlxqt, detached from the app (setsid
# proot) so the desktop survives the app dying or being force-stopped.
# Production uses startlxqt (not manual component starts) so
# session.conf [Environment] (QT_QPA_PLATFORMTHEME=lxqt, LANG, fonts)
# applies exactly as on :6. Xvfb is the anchor: the script waits on
# it, so the proot lives exactly as long as Xvfb.
# Usage: lxqt7_session.sh <width> <height>   (default 848x472)
# Env: MUDIF_MINIMAL_SESSION=1 prevents the non-essential daemons
# (gvfs, at-spi, pulseaudio, xiccd, qlipper) from starting; 0 (or
# unset) restores the full session. The app sets it from the Android
# API level: 1 only where the phantom killer exists (API 31+), 0 on
# API 30 and below, so the phone never loses audio, network locations
# in the file manager, or accessibility. The prevention runs BEFORE
# startlxqt, so nothing can activate the daemons mid-session.
# p123 (20 Nov 2026): the :7 size is 848x472, 8 px shorter than the
# :6 server (848x480). TigerVNC shrinks a window that equals the
# screen size exactly by 40 px ("Adjusting window size to avoid
# accidental full-screen request", probe B2: a 848x472 window stays
# exactly 848x472 with no Adjusting line, panel visible, no
# scrollbars). The size is set at Xvfb start: a live xrandr switch
# kills x11vnc (probe A2: listener dies, viewer gets End of stream).
W="${1:-848}"
H="${2:-472}"
MINIMAL="${MUDIF_MINIMAL_SESSION:-0}"
export HOME=/root
export LANG=ar_MA.UTF-8
DBUS_DIR=/usr/share/dbus-1/services
# D-Bus service files whose activation starts the daemons. Proven by
# inspection (p123 probe 1): gvfs Daemon + 5 volume monitors +
# Metadata, org.a11y.Bus. A user-dir shadow file does NOT work (probe
# C/C2: the bus daemon ignores /root/.local/share/dbus-1/services,
# gvfsd respawned twice), so the system files are renamed, which is
# deterministic: no file, no activation. The rename IS the backup.
MUDIF_DBUS_SERVICES="org.a11y.Bus.service org.gtk.vfs.Daemon.service org.gtk.vfs.AfcVolumeMonitor.service org.gtk.vfs.GoaVolumeMonitor.service org.gtk.vfs.GPhoto2VolumeMonitor.service org.gtk.vfs.Metadata.service org.gtk.vfs.MTPVolumeMonitor.service org.gtk.vfs.UDisks2VolumeMonitor.service"
# XDG autostart entries (Hidden=true in ~/.config/autostart shadows
# the system entry per the XDG spec, no system file touched).
MUDIF_AUTOSTART="at-spi-dbus-bus.desktop lxqt-qlipper-autostart.desktop pulseaudio.desktop xiccd.desktop"
if [ "$MINIMAL" = "1" ]; then
  echo "minimal session: preventing daemons"
  export NO_AT_BRIDGE=1
  for s in $MUDIF_DBUS_SERVICES; do
    if [ -f "$DBUS_DIR/$s" ]; then mv "$DBUS_DIR/$s" "$DBUS_DIR/$s.mudif-disabled"; fi
  done
  mkdir -p /root/.config/autostart /root/.config/pulse
  for a in $MUDIF_AUTOSTART; do
    printf "[Desktop Entry]\nHidden=true\n" > "/root/.config/autostart/$a"
  done
  # The panel volume plugin would autospawn pulseaudio through
  # libpulse even with the autostart entry shadowed, so autospawn
  # is disabled too. Back up a pre-existing client.conf first, but
  # only if it is not already the minimal state: a previous minimal
  # run that was never restored leaves autospawn = no behind, and
  # backing that up would make the full restore put it straight back
  # (proven 20 Nov: a full probe still had autospawn = no).
  if [ -f /root/.config/pulse/client.conf ] && [ ! -f /root/.config/pulse/client.conf.mudif-bak ] && ! grep -q "autospawn = no" /root/.config/pulse/client.conf; then
    cp /root/.config/pulse/client.conf /root/.config/pulse/client.conf.mudif-bak
  fi
  printf "autospawn = no\n" > /root/.config/pulse/client.conf
else
  echo "full session: restoring daemons"
  for s in $MUDIF_DBUS_SERVICES; do
    if [ -f "$DBUS_DIR/$s.mudif-disabled" ]; then mv "$DBUS_DIR/$s.mudif-disabled" "$DBUS_DIR/$s"; fi
  done
  for a in $MUDIF_AUTOSTART; do
    rm -f "/root/.config/autostart/$a"
  done
  if [ -f /root/.config/pulse/client.conf.mudif-bak ]; then
    if grep -q "autospawn = no" /root/.config/pulse/client.conf.mudif-bak; then
      rm -f /root/.config/pulse/client.conf /root/.config/pulse/client.conf.mudif-bak
    else
      mv /root/.config/pulse/client.conf.mudif-bak /root/.config/pulse/client.conf
    fi
  else
    rm -f /root/.config/pulse/client.conf
  fi
fi
Xvfb :7 -screen 0 "${W}x${H}x24" -ac > /tmp/xvfb7.log 2>&1 &
echo $! > /tmp/mudif-xvfb7.pid
i=0
while [ $i -lt 50 ]; do
  if DISPLAY=:7 xdpyinfo >/dev/null 2>&1; then break; fi
  sleep 0.2
  i=$((i + 1))
done
DISPLAY=:7 xdpyinfo > /tmp/xvfb7-xdpyinfo.log 2>&1
echo "xdpyinfo after ${i} polls (0.2s each)" >> /tmp/xvfb7.log
x11vnc -display :7 -forever -shared -noxdamage -nopw -listen localhost -rfbport 5900 > /tmp/x11vnc7.log 2>&1 &
echo $! > /tmp/mudif-x11vnc.pid
DISPLAY=:7 startlxqt > "/root/lxqt7-session-$(date +%s).log" 2>&1 &
echo $! > /tmp/mudif-lxqt7.pid
wait "$(cat /tmp/mudif-xvfb7.pid)"

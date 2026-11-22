#!/bin/sh
# Mudif direct desktop launcher (take 1, native :6).
# Runs startlxqt directly on the native :6 Termux:X11 server, no
# nested display, no VNC: the :7 Xvfb plus x11vnc plus guest
# vncviewer stack is gone. The session is detached from the app
# (setsid proot) so the desktop processes survive the app dying;
# the :6 server itself lives in the app's :xserver process, so a
# force-stop still cold-starts the desktop (design section 6).
# Production uses startlxqt (not manual component starts) so
# session.conf [Environment] (QT_QPA_PLATFORMTHEME=lxqt, LANG, fonts)
# applies exactly as on :7. lxqt-session is the anchor: the script
# waits on it, so the proot lives exactly as long as the session.
# Usage: lxqt6_session.sh <width> <height>   (default 848x480)
# The width and height are the full :6 viewer resolution, recorded
# in the log only: the :6 size itself is set by the viewer on
# attach (RandR), never by this script, and there is no 8 px
# subtraction anymore (the TigerVNC shrink rule died with :7).
# Env: MUDIF_MINIMAL_SESSION=1 prevents the non-essential daemons
# (gvfs, at-spi, pulseaudio, xiccd, qlipper) from starting; 0 (or
# unset) restores the full session. The app sets it from the Android
# API level: 1 only where the phantom killer exists (API 31+), 0 on
# API 30 and below, so the phone never loses audio, network locations
# in the file manager, or accessibility. The prevention runs BEFORE
# startlxqt, so nothing can activate the daemons mid-session.
W="${1:-848}"
H="${2:-480}"
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
echo "direct desktop :6 at ${W}x${H} (server size set by the viewer)"
DISPLAY=127.0.0.1:6 startlxqt > "/root/lxqt6-session-$(date +%s).log" 2>&1 &
echo $! > /tmp/mudif-lxqt6.pid
wait "$(cat /tmp/mudif-lxqt6.pid)"

#!/bin/sh
# PPSSPP installer for the Mudif Debian guest.
# Downloads the official PPSSPP v1.20.4 ARM64 AppImage, extracts it
# WITHOUT FUSE (--appimage-extract), installs the extracted tree under
# /opt/ppsspp, and puts a PPSSPP wrapper on PATH.
# The download runs in the background with a heartbeat line every 20
# seconds so the caller sees output while the large archive downloads
# (the store's idle timeout is 180s; the total timeout is 1800s).
# Inner binary: the official AppImage is built by scripts/makeappimage_64-bit.sh
# (hrydgard/ppsspp), which deploys ./build/PPSSPPSDL via quick-sharun, so the
# extracted tree holds bin/PPSSPPSDL (the desktop file SDL/PPSSPPSDL.desktop
# also has Exec=PPSSPPSDL). The catalog binary name is PPSSPP, so the wrapper
# is named PPSSPP and execs PPSSPPSDL. Assets ship next to the binary
# (AppDir/bin/assets), so the wrapper execs the binary at its real location
# instead of symlinking it: a raw symlink would break asset loading.
set -e
apt-get update && apt-get install -y curl
cd /tmp
curl -fsSL -o PPSSPP-v1.20.4-anylinux-aarch64.AppImage https://github.com/hrydgard/ppsspp/releases/download/v1.20.4/PPSSPP-v1.20.4-anylinux-aarch64.AppImage &
download_pid=$!
while kill -0 "$download_pid" 2>/dev/null; do
  echo "جار تنزيل بي بي إس إس بي بي..."
  sleep 20
done
wait "$download_pid"
chmod +x PPSSPP-v1.20.4-anylinux-aarch64.AppImage
rm -rf squashfs-root
./PPSSPP-v1.20.4-anylinux-aarch64.AppImage --appimage-extract
rm -f PPSSPP-v1.20.4-anylinux-aarch64.AppImage
rm -rf /opt/ppsspp
mv squashfs-root /opt/ppsspp
# Locate the real binary: documented path first, then a defensive search.
# Fail loudly if nothing is found instead of installing a broken entry.
if [ -x /opt/ppsspp/bin/PPSSPPSDL ]; then
  PPSPP_BIN=/opt/ppsspp/bin/PPSSPPSDL
elif [ -x /opt/ppsspp/usr/bin/PPSSPPSDL ]; then
  PPSPP_BIN=/opt/ppsspp/usr/bin/PPSSPPSDL
else
  PPSPP_BIN=$(find /opt/ppsspp -type f -name 'PPSSPPSDL*' -perm -u+x 2>/dev/null | head -n 1)
fi
if [ -z "$PPSPP_BIN" ] || [ ! -x "$PPSPP_BIN" ]; then
  echo "ERROR: no PPSSPP executable found under /opt/ppsspp after extraction" >&2
  exit 1
fi
# Wrapper, not a symlink: the binary resolves its assets relative to its
# real location (bin/assets sits next to it).
cat > /usr/local/bin/PPSSPP <<EOF
#!/bin/sh
exec $PPSPP_BIN "\$@"
EOF
chmod +x /usr/local/bin/PPSSPP
command -v PPSSPP
# Best-effort version check only: a GUI binary must never fail the install
# for lacking a cheap --version path, and it must never hang the store.
timeout 15 PPSSPP --version || echo "تحذير: تعذر التحقق من إصدار PPSSPP"

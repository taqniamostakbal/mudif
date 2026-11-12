#!/bin/sh
# Vita3K installer for the Mudif Debian guest.
# Downloads the official Vita3K continuous ARM64 AppImage (rolling CI build,
# no stable release), extracts it WITHOUT FUSE (--appimage-extract), installs
# the extracted tree under /opt/vita3k, and puts a Vita3K wrapper on PATH.
# The download runs in the background with a heartbeat line every 20
# seconds so the caller sees output while the large archive downloads
# (the store's idle timeout is 180s; the total timeout is 1800s).
# Inner binary: the upstream packaging script .ci/package-appimage.sh
# (Vita3K/Vita3K) installs with prefix /usr, then deploys /usr/bin/Vita3K via
# quick-sharun, whose binaries land in AppDir/bin (DST_BIN_DIR=$APPDIR/bin),
# so the extracted tree holds bin/Vita3K. The wrapper execs the binary at its
# real location instead of symlinking it: a raw symlink would break resource
# loading relative to the extracted tree.
set -e
apt-get update && apt-get install -y curl
cd /tmp
curl -fsSL -o Vita3K-aarch64.AppImage https://github.com/Vita3K/Vita3K/releases/download/continuous/Vita3K-aarch64.AppImage &
download_pid=$!
while kill -0 "$download_pid" 2>/dev/null; do
  echo "جار تنزيل فيتا 3 كاي..."
  sleep 20
done
wait "$download_pid"
chmod +x Vita3K-aarch64.AppImage
rm -rf squashfs-root
./Vita3K-aarch64.AppImage --appimage-extract
rm -f Vita3K-aarch64.AppImage
rm -rf /opt/vita3k
mv squashfs-root /opt/vita3k
# Locate the real binary: documented path first, then a defensive search.
# Fail loudly if nothing is found instead of installing a broken entry.
if [ -x /opt/vita3k/bin/Vita3K ]; then
  VITA3K_BIN=/opt/vita3k/bin/Vita3K
elif [ -x /opt/vita3k/usr/bin/Vita3K ]; then
  VITA3K_BIN=/opt/vita3k/usr/bin/Vita3K
else
  VITA3K_BIN=$(find /opt/vita3k -type f -name 'Vita3K*' -perm -u+x 2>/dev/null | head -n 1)
fi
if [ -z "$VITA3K_BIN" ] || [ ! -x "$VITA3K_BIN" ]; then
  echo "ERROR: no Vita3K executable found under /opt/vita3k after extraction" >&2
  exit 1
fi
# Wrapper, not a symlink: the binary resolves its resources relative to its
# real location inside the extracted tree.
cat > /usr/local/bin/Vita3K <<EOF
#!/bin/sh
exec $VITA3K_BIN "\$@"
EOF
chmod +x /usr/local/bin/Vita3K
command -v Vita3K
# Best-effort version check only: a GUI binary must never fail the install
# for lacking a cheap --version path, and it must never hang the store.
timeout 15 Vita3K --version || echo "تحذير: تعذر التحقق من إصدار Vita3K"

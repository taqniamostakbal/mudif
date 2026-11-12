#!/bin/sh
# melonDS installer for the Mudif Debian guest.
# Downloads the official melonDS v1.1 ARM64 AppImage, extracts it WITHOUT
# FUSE (--appimage-extract), installs the extracted tree under /opt/melonds,
# and puts a melonDS wrapper on PATH.
# The download runs in the background with a heartbeat line every 20
# seconds so the caller sees output while the large archive downloads
# (the store's idle timeout is 180s; the total timeout is 1800s).
# The official download is a ZIP, not a bare AppImage: the "Linux AppImage
# ARM64" row on https://melonds.kuribo64.net/downloads.php (31.84 MB, SHA256
# c537ae018d6dcedfe9da0317a1d5c0163e69201e23abfa163fa2a63e437ace09) links to
# melonDS-1.1-appimage-aarch64.zip, which contains the AppImage. The zip is
# checksum-verified, unpacked, and the AppImage inside is extracted.
# Inner binary: the upstream CI workflow .github/workflows/build-ubuntu.yml
# (melonDS-emu/melonDS) installs with CMAKE_INSTALL_PREFIX=/usr into
# DESTDIR=AppDir and packages with linuxdeploy, so the extracted tree holds
# usr/bin/melonDS. The wrapper execs the binary at its real location instead
# of symlinking it: a raw symlink would break resource loading relative to
# the extracted tree.
set -e
apt-get update && apt-get install -y curl unzip
cd /tmp
curl -fsSL -o melonDS-1.1-appimage-aarch64.zip https://melonds.kuribo64.net/downloads/melonDS-1.1-appimage-aarch64.zip &
download_pid=$!
while kill -0 "$download_pid" 2>/dev/null; do
  echo "جار تنزيل ميلون دي إس..."
  sleep 20
done
wait "$download_pid"
echo "c537ae018d6dcedfe9da0317a1d5c0163e69201e23abfa163fa2a63e437ace09  melonDS-1.1-appimage-aarch64.zip" | sha256sum -c -
rm -rf melonds-dl
mkdir -p melonds-dl
unzip -q -o melonDS-1.1-appimage-aarch64.zip -d melonds-dl
rm -f melonDS-1.1-appimage-aarch64.zip
APPIMAGE=$(find melonds-dl -name '*.AppImage' -type f | head -n 1)
if [ -z "$APPIMAGE" ]; then
  echo "ERROR: no .AppImage found inside the melonDS download" >&2
  exit 1
fi
chmod +x "$APPIMAGE"
rm -rf squashfs-root
"$APPIMAGE" --appimage-extract
rm -f "$APPIMAGE"
rm -rf melonds-dl
rm -rf /opt/melonds
mv squashfs-root /opt/melonds
# Locate the real binary: documented path first, then a defensive search.
# Fail loudly if nothing is found instead of installing a broken entry.
if [ -x /opt/melonds/usr/bin/melonDS ]; then
  MELONDS_BIN=/opt/melonds/usr/bin/melonDS
elif [ -x /opt/melonds/bin/melonDS ]; then
  MELONDS_BIN=/opt/melonds/bin/melonDS
else
  MELONDS_BIN=$(find /opt/melonds -type f -name 'melonDS*' -perm -u+x 2>/dev/null | head -n 1)
fi
if [ -z "$MELONDS_BIN" ] || [ ! -x "$MELONDS_BIN" ]; then
  echo "ERROR: no melonDS executable found under /opt/melonds after extraction" >&2
  exit 1
fi
# Wrapper, not a symlink: the binary resolves its resources relative to its
# real location inside the extracted tree.
cat > /usr/local/bin/melonDS <<EOF
#!/bin/sh
exec $MELONDS_BIN "\$@"
EOF
chmod +x /usr/local/bin/melonDS
command -v melonDS
# Best-effort version check only: a GUI binary must never fail the install
# for lacking a cheap --version path, and it must never hang the store.
timeout 15 melonDS --version || echo "تحذير: تعذر التحقق من إصدار melonDS"

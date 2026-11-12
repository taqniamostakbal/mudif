#!/bin/sh
# Mission Center installer for the Mudif Debian guest.
# Downloads the official Mission Center v1.2.0 ARM64 AppImage, extracts it
# WITHOUT FUSE (--appimage-extract), installs the extracted tree under
# /opt/mission-center, and puts a missioncenter wrapper on PATH.
# The download runs in the background with a heartbeat line every 20
# seconds so the caller sees output while the large archive downloads
# (the store's idle timeout is 180s; the total timeout is 1800s).
# Download URL: the asset link the GitLab API lists for release v1.2.0
# (26 Jul 2026), verified HTTP 200, 39,513,281 bytes, 2026-11-11. NOTE:
# the /-/releases/v1.2.0/downloads/... permalink 404s for this project,
# so the job-artifact URL below is the working one.
# Payload: DwarFS, NOT squashfs (magic "DWARFS" at offset 1327144).
# The AppImage runtime's --appimage-extract handles it; the layout was
# verified locally with dwarfsextract 0.15.8 (bin/missioncenter,
# io.missioncenter.MissionCenter.desktop, icons all present).
# Inner binary: the upstream CI script support/create-appimage.sh
# (mission-center-devs/mission-center) deploys via quick-sharun, so the
# extracted tree holds bin/missioncenter (verified by extraction).
# The wrapper execs the binary at its real location instead of
# symlinking it: a raw symlink would break resource loading relative
# to the extracted tree.
set -e
apt-get update && apt-get install -y curl
cd /tmp
curl -fsSL -o MissionCenter-1.2.0-aarch64.AppImage https://gitlab.com/mission-center-devs/mission-center/-/jobs/15536631700/artifacts/raw/MissionCenter-1.2.0-aarch64.AppImage &
download_pid=$!
while kill -0 "$download_pid" 2>/dev/null; do
  echo "جار تنزيل ميشن سنتر..."
  sleep 20
done
wait "$download_pid"
chmod +x MissionCenter-1.2.0-aarch64.AppImage
rm -rf squashfs-root
./MissionCenter-1.2.0-aarch64.AppImage --appimage-extract
rm -f MissionCenter-1.2.0-aarch64.AppImage
rm -rf /opt/mission-center
mv squashfs-root /opt/mission-center
# Locate the real binary: documented path first, then a defensive search.
# Fail loudly if nothing is found instead of installing a broken entry.
if [ -x /opt/mission-center/bin/missioncenter ]; then
  MC_BIN=/opt/mission-center/bin/missioncenter
elif [ -x /opt/mission-center/usr/bin/missioncenter ]; then
  MC_BIN=/opt/mission-center/usr/bin/missioncenter
else
  MC_BIN=$(find /opt/mission-center -type f -name 'missioncenter*' -perm -u+x 2>/dev/null | head -n 1)
fi
if [ -z "$MC_BIN" ] || [ ! -x "$MC_BIN" ]; then
  echo "ERROR: no missioncenter executable found under /opt/mission-center after extraction" >&2
  exit 1
fi
# Wrapper, not a symlink: the binary resolves its resources relative to
# its real location inside the extracted tree.
cat > /usr/local/bin/missioncenter <<EOF
#!/bin/sh
exec $MC_BIN "\$@"
EOF
chmod +x /usr/local/bin/missioncenter
command -v missioncenter
# Best-effort version check only: a GUI binary must never fail the install
# for lacking a cheap --version path, and it must never hang the store.
timeout 15 missioncenter --version || echo "تحذير: تعذر التحقق من إصدار Mission Center"

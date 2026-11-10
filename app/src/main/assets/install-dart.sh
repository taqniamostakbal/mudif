#!/bin/sh
# Dart SDK installer for the Mudif Debian guest.
# Downloads the official Dart SDK arm64 zip (stable channel), extracts it
# to /opt/dart-sdk, symlinks dart into /usr/local/bin, and verifies.
# The download runs in the background with a heartbeat line every 20
# seconds so the caller sees output while the large archive downloads
# (the store's idle timeout is 180s; the total timeout is 1800s).
set -e
apt-get update && apt-get install -y unzip curl
cd /tmp
curl -fsSL -o dartsdk-linux-arm64-release.zip https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/dartsdk-linux-arm64-release.zip &
download_pid=$!
while kill -0 "$download_pid" 2>/dev/null; do
  echo "جار تنزيل دارت..."
  sleep 20
done
wait "$download_pid"
rm -rf /opt/dart-sdk
unzip -q -o dartsdk-linux-arm64-release.zip -d /opt
rm -f dartsdk-linux-arm64-release.zip
ln -sf /opt/dart-sdk/bin/dart /usr/local/bin/dart
ln -sf /opt/dart-sdk/bin/dartaotruntime /usr/local/bin/dartaotruntime
command -v dart
dart --version

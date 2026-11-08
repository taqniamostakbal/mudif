#!/bin/sh
# Ollama installer for the Mudif Debian guest.
# Downloads the official Ollama install script and runs it. The
# installer runs in the background with a heartbeat line every 20
# seconds so the caller sees output while the large binary downloads.
set -e
cd /tmp
curl -fsSL -o ollama-install.sh https://ollama.com/install.sh
sh ollama-install.sh &
installer_pid=$!
while kill -0 "$installer_pid" 2>/dev/null; do
  echo "جار تثبيت أولاما..."
  sleep 20
done
wait "$installer_pid"
command -v ollama

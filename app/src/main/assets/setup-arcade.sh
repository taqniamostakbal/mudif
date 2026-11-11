#!/bin/sh
# Mudif workspace setup: محطة الألعاب (Arcade)
# Runs INSIDE the Debian guest (proot, as root) after the 16 programs are
# installed via the Store. Creates folders and desktop shortcuts only.
# Does NOT install any program, ROM, BIOS, or firmware.
# Does NOT download anything.
# Idempotent: re-running it rewrites the same folders and shortcuts.
# Exit code 0 = folders + shortcuts done; anything else = real failure
# (set -e). The final shortcuts_created / shortcuts_skipped lines are
# the machine-checkable summary for the caller.
set -eu

GAMES_HOME="$HOME/Games"

echo "== Mudif Arcade setup =="
mkdir -p "$GAMES_HOME/ROMs/GBA" "$GAMES_HOME/ROMs/PSP" \
         "$GAMES_HOME/ROMs/GC" "$GAMES_HOME/ROMs/NDS" \
         "$GAMES_HOME/ROMs/DOS" "$GAMES_HOME/ROMs/ScummVM" \
         "$GAMES_HOME/Saves"

DESK="$HOME/Desktop"
mkdir -p "$DESK"

CREATED=0
SKIPPED=0

# Desktop shortcut helper: only create the shortcut if the binary exists.
# The Exec line carries the ABSOLUTE binary path: Debian game packages
# install to /usr/games, which is not on root's PATH, so a bare binary
# name would silently fail to launch from the desktop even though the
# program is installed (command -v misses it too — hence the fallback).
mkshortcut() {
    _name="$1"; _bin="$2"; _icon="$3"
    _path="$(command -v "$_bin" 2>/dev/null || true)"
    if [ -z "$_path" ] && [ -x "/usr/games/$_bin" ]; then
        _path="/usr/games/$_bin"
    fi
    if [ -n "$_path" ] && [ -x "$_path" ]; then
        cat > "$DESK/$_name.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$_name
Exec=$_path
Icon=$_icon
Terminal=false
Categories=Game;
EOF
        # Executable bit: pcmanfm-qt only launches a desktop
        # .desktop file without a trust prompt when it is +x.
        chmod +x "$DESK/$_name.desktop"
        CREATED=$((CREATED + 1))
        echo "shortcut: $_name -> $_path"
    else
        SKIPPED=$((SKIPPED + 1))
        echo "skip (not installed): $_name ($_bin)"
    fi
}

mkshortcut "RetroArch"      "retroarch"   "retroarch"
mkshortcut "PPSSPP"         "PPSSPP"      "ppsspp"
mkshortcut "mGBA"           "mgba-qt"     "mgba"
mkshortcut "Dolphin"        "dolphin-emu" "dolphin-emu"
mkshortcut "DOSBox-X"       "dosbox-x"    "dosbox-x"
mkshortcut "ScummVM"        "scummvm"     "scummvm"
mkshortcut "melonDS"        "melonDS"     "melonds"
mkshortcut "Vita3K"         "Vita3K"      "vita3k"
mkshortcut "SuperTuxKart"   "supertuxkart" "supertuxkart"
mkshortcut "0 A.D."         "0ad"         "0ad"
mkshortcut "Wesnoth"        "wesnoth-1.18" "wesnoth"
mkshortcut "OpenTTD"        "openttd"     "openttd"
mkshortcut "Luanti"         "luanti"      "luanti"
mkshortcut "SuperTux"       "supertux2"   "supertux"
mkshortcut "Warzone 2100"   "warzone2100" "warzone2100"
mkshortcut "Freeciv"        "freeciv-gtk3.22" "freeciv"

# RetroArch companion cores (installed via apt in the guest when available).
# NOTE: libretro-snes9x is non-free; install only if the user accepts that.
# These two lines are intentionally commented out.
# apt-get install -y libretro-mgba
# apt-get install -y libretro-snes9x

echo "== done =="
echo "shortcuts_created=$CREATED"
echo "shortcuts_skipped=$SKIPPED"
echo "ROMs go in $GAMES_HOME/ROMs/<system>/ — bring your own legal copies."
echo "Vita3K needs your own PS Vita firmware + Vulkan; likely slow under llvmpipe."

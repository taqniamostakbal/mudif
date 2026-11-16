#!/bin/bash
# p68 - Mudif download library (مكتبة التحميل الداخلية).
# One script, four commands:
#   dl <url> <dest>   segmented parallel download with resume + on-device cache
#   mirror-pick       measure Debian mirrors once, switch only if clearly faster
#   prune             drop the oldest cached files over the 1 GiB cap
#   cache-info        count + total bytes of the cache
# The library never invents a second installer: it only fetches the
# file; apt/dpkg still install it exactly as before. The one honest
# limit: it cannot exceed the WiFi/4G line speed; it gets the download
# to the full line speed and makes repeats instant.
set -u

LIB_DIR="/root/.config/mudif/library"
INDEX="$LIB_DIR/index.tsv"
MAX_BYTES=1073741824
MIRROR_MARKER="/root/.config/mudif/mirror-picked"
CURRENT_HOST="deb.debian.org"

ensure_engine() {
    # aria2c is the segmented engine. It is NOT in the image (p23
    # PACKAGES has no aria2), so the first download installs it once
    # from the normal apt lists; afterwards it is just there. If the
    # install fails, the caller falls back to curl with resume.
    command -v aria2c >/dev/null 2>&1 && return 0
    if DEBIAN_FRONTEND=noninteractive apt-get install -y aria2 >/dev/null 2>&1; then
        return 0
    fi
    DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 \
        && DEBIAN_FRONTEND=noninteractive apt-get install -y aria2 >/dev/null 2>&1
}

cmd_prune() {
    [ -f "$INDEX" ] || return 0
    local total line file size
    total="$(awk -F'\t' '{ s += $2 } END { print s + 0 }' "$INDEX")"
    while [ "$total" -gt "$MAX_BYTES" ]; do
        line="$(head -n 1 "$INDEX")"
        [ -n "$line" ] || break
        file="$(printf '%s' "$line" | cut -f3)"
        size="$(printf '%s' "$line" | cut -f2)"
        rm -f "$LIB_DIR/$file"
        tail -n +2 "$INDEX" > "$INDEX.tmp" && mv "$INDEX.tmp" "$INDEX"
        total=$((total - size))
    done
}

cmd_dl() {
    local url="$1" dest="$2"
    mkdir -p "$LIB_DIR" "$(dirname "$dest")"
    local base key cached size
    base="$(basename "${url%%\?*}")"
    [ -z "$base" ] && base="file"
    key="$(printf '%s' "$url" | sha256sum | cut -c1-16)"
    cached="$LIB_DIR/$key-$base"

    # Cache hit: same URL key, recorded size matches the file on disk.
    # A reinstall of the same version copies from the device: instant,
    # zero network.
    if [ -f "$cached" ] && [ -s "$cached" ]; then
        size="$(stat -c%s "$cached" 2>/dev/null || echo 0)"
        if grep -q "^$key	$size	" "$INDEX" 2>/dev/null; then
            cp -f "$cached" "$dest"
            echo "MUDIF_CACHE_HIT"
            exit 0
        fi
    fi

    # URL mismatch guard: a partial for a different URL must never be
    # resumed into this file.
    if [ -f "$dest.mudif-url" ]; then
        if [ "$(cat "$dest.mudif-url")" != "$url" ]; then
            rm -f "$dest" "$dest.aria2"
        fi
    fi
    printf '%s' "$url" > "$dest.mudif-url"

    if command -v aria2c >/dev/null 2>&1 || ensure_engine; then
        # -x 8 -s 8: eight connections, eight segments at once.
        # -c: resume from the .aria2 control file after a pause or a
        # crash. --file-allocation=none: the file grows as segments
        # arrive, so the Kotlin progress loop sees the real size.
        aria2c -x 8 -s 8 -k 1M -c --allow-overwrite=true \
            --file-allocation=none --summary-interval=0 \
            -d "$(dirname "$dest")" -o "$(basename "$dest")" "$url"
    else
        curl -fsSL -C - -o "$dest" "$url"
    fi
    local rc=$?
    [ $rc -ne 0 ] && { echo "MUDIF_DL_FAIL rc=$rc" >&2; exit 1; }
    [ -s "$dest" ] || { echo "MUDIF_DL_FAIL empty" >&2; exit 1; }

    size="$(stat -c%s "$dest")"
    cp -f "$dest" "$cached"
    grep -v "^$key	" "$INDEX" 2>/dev/null > "$INDEX.tmp" || true
    mv "$INDEX.tmp" "$INDEX"
    printf '%s\t%s\t%s\n' "$key" "$size" "$(basename "$cached")" >> "$INDEX"
    cmd_prune
    echo "MUDIF_DL_DONE $size"
    exit 0
}

cmd_mirror_pick() {
    # Runs once per device (marker file). Measures the time to fetch
    # the trixie Release file from four mirrors and switches the apt
    # source only if the winner is at least 20% faster than the
    # current host. A failed validation restores the backup.
    [ -f "$MIRROR_MARKER" ] && exit 0
    local host t best_host best_t cur_t
    best_host="$CURRENT_HOST"; best_t=999; cur_t=999
    for host in deb.debian.org ftp.debian.org ftp.fr.debian.org mirror.leaseweb.com; do
        t="$(curl -o /dev/null -s -w '%{time_total}' --max-time 8 "http://$host/debian/dists/trixie/Release" 2>/dev/null || echo 999)"
        case "$t" in ''|*[!0-9.]* ) t=999 ;; esac
        if [ "$host" = "$CURRENT_HOST" ]; then cur_t="$t"; fi
        if awk -v a="$t" -v b="$best_t" 'BEGIN { exit !(a < b) }'; then
            best_t="$t"; best_host="$host"
        fi
    done
    if [ "$best_host" = "$CURRENT_HOST" ]; then
        printf '%s\n' "$CURRENT_HOST" > "$MIRROR_MARKER"
        exit 0
    fi
    if awk -v b="$best_t" -v c="$cur_t" 'BEGIN { exit !(b * 1.25 < c) }'; then
        local src=""
        [ -f /etc/apt/sources.list.d/debian.sources ] && src=/etc/apt/sources.list.d/debian.sources
        [ -z "$src" ] && [ -f /etc/apt/sources.list ] && src=/etc/apt/sources.list
        if [ -n "$src" ]; then
            cp "$src" "$src.mudif-bak"
            sed -i "s/$CURRENT_HOST/$best_host/g" "$src"
            if DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1; then
                printf '%s\n' "$best_host" > "$MIRROR_MARKER"
                exit 0
            fi
            mv "$src.mudif-bak" "$src"
        fi
    fi
    printf '%s\n' "$CURRENT_HOST" > "$MIRROR_MARKER"
    exit 0
}

cmd_cache_info() {
    [ -f "$INDEX" ] || { echo "MUDIF_CACHE 0 0"; exit 0; }
    awk -F'\t' '{ n++; s += $2 } END { printf "MUDIF_CACHE %d %d\n", n + 0, s + 0 }' "$INDEX"
}

case "${1:-}" in
    dl) [ $# -eq 3 ] || { echo "usage: mudif-dl.sh dl <url> <dest>" >&2; exit 2; }; cmd_dl "$2" "$3" ;;
    mirror-pick) cmd_mirror_pick ;;
    prune) cmd_prune ;;
    cache-info) cmd_cache_info ;;
    *) echo "usage: mudif-dl.sh {dl|mirror-pick|prune|cache-info}" >&2; exit 2 ;;
esac

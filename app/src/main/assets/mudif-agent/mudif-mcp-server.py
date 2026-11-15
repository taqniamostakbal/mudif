#!/usr/bin/env python3
"""Mudif MCP proxy (guest side, Python 3 stdlib only).

Translates MCP tool calls (JSON-RPC over stdio, the way OpenCode
spawns local MCP servers) into HTTP calls against the Mudif bridge
running in the Android app on the shared loopback
(http://127.0.0.1:18080). One tool per bridge endpoint; no tool
exists for anything the bridge does not implement. One extra tool,
mudif_desktop_screenshot, runs guest ffmpeg x11grab in the guest
(no bridge call): it is the only screenshot tool that sees the
guest desktop, while mudif_android_screenshot sees only the
Android phone display.

Auth: the bridge token is read once at startup from
/tmp/mudif-bridge-token (the guest path of the app's
files/tmp/mudif-bridge-token, bind-mounted into the guest) and
sent as `Authorization: Bearer <token>` on every request. The
token is NEVER printed to stdout or stderr: stdout is the MCP
channel, and stderr must not leak it either.

Bridge errors are returned as MCP error objects carrying the real
bridge message (error code -32000). A tool that cannot run because
the bridge is down returns an error too, never a faked result.

Long operations: store installs run synchronously in the bridge
and can take minutes, so that tool uses a 30-minute read timeout.
State reads are fast, but /state can take up to ~40 s when the
desktop is down (the bridge's desktop check polls twice), so it
uses a 60 s timeout. Never retry a timed-out install: it may
still be running in the guest.

Usage (from opencode.json "mcp"."mudif"):
  {"type": "local",
   "command": ["python3", "/root/.config/mudif/mudif-mcp-server.py"],
   "environment": {}}
Optional env overrides: MUDIF_URL (default http://127.0.0.1:18080),
MUDIF_TOKEN_FILE (default /tmp/mudif-bridge-token).
"""

import base64
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BRIDGE_URL = os.environ.get("MUDIF_URL", "http://127.0.0.1:18080").rstrip("/")
TOKEN_FILE = os.environ.get("MUDIF_TOKEN_FILE", "/tmp/mudif-bridge-token")

# Web search/fetch (brain fix phase 1): no bridge involved, stdlib
# only. Keys come from the environment at call time, never from a
# file, never logged.
ALLOWLIST_FILE = os.environ.get("MUDIF_ALLOWLIST_FILE", "/tmp/mudif-allowed-urls.txt")
ALLOWLIST_TTL_S = 1800  # allowlist entries older than 30 minutes are ignored
WEB_TEXT_CAP = 15000  # max chars of returned text per web tool
_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

# Read timeouts in seconds, per tool.
TIMEOUTS = {
    "mudif_state": 60,
    "mudif_engine_start": 60,
    "mudif_store_install": 1800,
    "mudif_settings_set": 30,
    "mudif_cast_start": 120,
    "mudif_cast_stop": 60,
    "mudif_android_ui": 30,
    "mudif_android_tap": 30,
    "mudif_android_type": 30,
    "mudif_android_swipe": 30,
    "mudif_android_screenshot": 60,
    "mudif_desktop_screenshot": 60,
    "mudif_desktop_windows": 30,
    "mudif_desktop_window": 120,
    "mudif_desktop_type": 30,
    "mudif_desktop_key": 30,
    "mudif_logs": 30,
    "mudif_web_search": 60,
    "mudif_web_fetch": 60,
    "mudif_web_answer": 120,
}

TOOLS = [
    {
        "name": "mudif_state",
        "description": "Live Mudif snapshot: engine ready, desktop running, agent server up, battery unrestricted, accessibility service enabled.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_engine_start",
        "description": "Boot the Debian guest. Does NOT start the X11 desktop.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_store_install",
        "description": "Install one store program by id, then verify it for real. Synchronous and slow (minutes); never retry on timeout.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string", "description": "Program id from the store catalog."}},
            "required": ["id"],
        },
    },
    {
        "name": "mudif_settings_set",
        "description": "Set one Mudif viewer setting. Only real ViewerPrefs keys are accepted; unknown keys are rejected with 400.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "key": {"type": "string", "description": "Viewer setting key."},
                "value": {"description": "New value (boolean, string, or integer, per the setting)."},
            },
            "required": ["key", "value"],
        },
    },
    {
        "name": "mudif_cast_start",
        "description": "Start the TV cast (HLS stream + load on the connected Cast session). Returns no_cast_session with the stream URL when no TV is connected yet; the user must pick a TV in the Cast chooser.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_cast_stop",
        "description": "Stop the TV cast and end the Cast session.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_android_ui",
        "description": "The active Android window's UI tree (class, text, bounds, clickable, editable). Fails when the AccessibilityService is not enabled.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_android_tap",
        "description": "Tap at screen coordinates x, y.",
        "inputSchema": {
            "type": "object",
            "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
            "required": ["x", "y"],
        },
    },
    {
        "name": "mudif_android_type",
        "description": "Type text into the focused editable field. Refuses password fields.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "mudif_android_swipe",
        "description": "Swipe from (x1, y1) to (x2, y2).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "x1": {"type": "integer"}, "y1": {"type": "integer"},
                "x2": {"type": "integer"}, "y2": {"type": "integer"},
            },
            "required": ["x1", "y1", "x2", "y2"],
        },
    },
    {
        "name": "mudif_android_screenshot",
        "description": "One still screenshot of the Android phone display as base64 JPEG. This is the PHONE screen only, never the Debian guest desktop: while the chat is open it shows the Mudif app itself. Do NOT use it to answer what is on the desktop or computer screen; a desktop description is supplied as text in the prompt, or use mudif_desktop_screenshot.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_desktop_screenshot",
        "description": "One still screenshot of the Debian guest desktop (the computer screen inside Mudif) as base64 JPEG. Use this when the user asks what is on the desktop or computer screen. This is the ONLY screenshot tool that sees the guest desktop; mudif_android_screenshot sees only the Android phone display.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_desktop_windows",
        "description": "List the open windows on the Debian guest desktop by name, with size and position. Use this to find a window's exact name before acting on it.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_desktop_window",
        "description": "Act on one guest desktop window by NAME: activate (bring to front), close, or minimize. The name is matched as a substring of the window title; zero or several matches is an error, never a guess. Installs xdotool on first use. After any action, verify with mudif_desktop_screenshot before reporting it done.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Window title, or part of it."},
                "action": {"type": "string", "description": "activate, close, or minimize."},
            },
            "required": ["name", "action"],
        },
    },
    {
        "name": "mudif_desktop_type",
        "description": "Type text into the focused guest desktop window, or into the named window (activated first). The text goes to the desktop, never to the phone. Never type a password.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to type."},
                "window": {"type": "string", "description": "Optional window title to activate first."},
            },
            "required": ["text"],
        },
    },
    {
        "name": "mudif_desktop_key",
        "description": "Press keys on the guest desktop, like 'Return', 'Escape', 'Tab', or 'ctrl+s'. Keys are X keysyms; several groups press in sequence. Pass 'window' to activate a named window first in the same call, so the keys land there even when focus drifts. Never press a destructive key without the user's explicit approval.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "keys": {"type": "string", "description": "Keys to press, e.g. 'ctrl+s' or 'Escape'."},
                "window": {"type": "string", "description": "Optional window title to activate first."},
            },
            "required": ["keys"],
        },
    },
    {
        "name": "mudif_logs",
        "description": "Last 100 lines of the bridge log.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_web_search",
        "description": "Web search: Exa first, Tavily second, then DuckDuckGo HTML, FreeSerp and Wikipedia when no key is set. Returns titles, URLs and snippets. Never invents a result.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query."},
                "count": {"type": "integer", "description": "Results wanted, default 5, max 10."},
            },
            "required": ["query"],
        },
    },
    {
        "name": "mudif_web_fetch",
        "description": "Read one web page as text. Opens only URLs the search tool returned or the user pasted; any other URL is refused.",
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string", "description": "Page URL to read."}},
            "required": ["url"],
        },
    },
    {
        "name": "mudif_web_answer",
        "description": "Answer one question from live web sources, with citations. Exa answer first, Tavily extract second.",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "Question to answer."}},
            "required": ["query"],
        },
    },
    {
        "name": "mudif_usage_log",
        "description": "Read the last N lines of the local usage log (what the user opened, installed, or asked for, on this phone only). Read-only: it cannot write, change, or delete the log. Cite it as 'حسب سجل الاستعمال' when you answer from it.",
        "inputSchema": {
            "type": "object",
            "properties": {"count": {"type": "integer", "description": "How many last lines to read (default 10, max 50)."}},
            "required": [],
        },
    },
]

# Tool name -> (HTTP method, bridge path).
ROUTES = {
    "mudif_state": ("GET", "/state"),
    "mudif_engine_start": ("POST", "/engine/start"),
    "mudif_store_install": ("POST", "/store/install"),
    "mudif_settings_set": ("POST", "/settings"),
    "mudif_cast_start": ("POST", "/cast/start"),
    "mudif_cast_stop": ("POST", "/cast/stop"),
    "mudif_android_ui": ("GET", "/android/ui"),
    "mudif_android_tap": ("POST", "/android/tap"),
    "mudif_android_type": ("POST", "/android/type"),
    "mudif_android_swipe": ("POST", "/android/swipe"),
    "mudif_android_screenshot": ("GET", "/android/screenshot"),
    "mudif_logs": ("GET", "/logs"),
}


def read_token():
    """Bridge token from the bind-mounted file. None when missing."""
    try:
        with open(TOKEN_FILE, "r", encoding="utf-8") as f:
            token = f.read().strip()
        return token or None
    except OSError:
        return None


TOKEN = read_token()


def bridge_call(tool, arguments):
    """One bridge call. Returns the parsed JSON dict, or raises BridgeError."""
    method, path = ROUTES[tool]
    url = BRIDGE_URL + path
    body = None
    headers = {"Accept": "application/json"}
    if TOKEN is None:
        raise BridgeError("Bridge token not found at %s: is the Mudif bridge running?" % TOKEN_FILE)
    headers["Authorization"] = "Bearer " + TOKEN
    if method == "POST":
        body = json.dumps(arguments or {}).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUTS[tool]) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode("utf-8"))
            message = payload.get("detail") or payload.get("error") or ("bridge HTTP %d" % e.code)
        except Exception:
            message = "bridge HTTP %d" % e.code
        raise BridgeError(message)
    except urllib.error.URLError as e:
        if isinstance(e.reason, TimeoutError):
            raise BridgeError("Bridge call %s timed out after %ds." % (tool, TIMEOUTS[tool]))
        raise BridgeError("Cannot reach the Mudif bridge at %s: %s" % (url, e.reason))


class BridgeError(Exception):
    pass


# -------------------------------------------- guest desktop capture
# Vision fix: the brain used to answer desktop questions with the
# Android screenshot, which shows the phone display (the chat
# itself), then disavowed the correct guest description. This tool
# re-points that impulse at the real source: the same guest ffmpeg
# x11grab primitive VisionCapture uses (display 127.0.0.1:6, real
# size from xdpyinfo, downscaled to 960 wide). No bridge involved:
# ffmpeg runs in the guest. Any failure returns an error dict,
# never a stale frame and never an Android frame.

DESKTOP_TOOLS = {"mudif_desktop_screenshot"}
DESKTOP_DISPLAY = "127.0.0.1:6"
DESKTOP_SHOT = "/tmp/mudif-mcp-shot.jpg"
DESKTOP_SHOT_WIDTH = 960


def _desktop_display_size():
    """Real guest display size from xdpyinfo, or None."""
    try:
        proc = subprocess.run(
            ["xdpyinfo", "-display", DESKTOP_DISPLAY],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=30, text=True,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    match = re.search(r"dimensions:\s*(\d+)x(\d+)\s*pixels", proc.stdout or "")
    if not match:
        return None
    width, height = int(match.group(1)), int(match.group(2))
    if width <= 0 or height <= 0:
        return None
    return width, height


def desktop_screenshot(arguments):
    """One guest desktop frame as base64 JPEG, labelled by source."""
    size = _desktop_display_size()
    if size is None:
        return {"source": "guest_desktop_x11grab", "ok": False,
                "error": "could not read the guest display size (is the desktop running?)"}
    width, height = size
    scale = ["-vf", "scale=%d:-1" % DESKTOP_SHOT_WIDTH] if width > DESKTOP_SHOT_WIDTH else []
    cmd = ["ffmpeg", "-y", "-f", "x11grab",
           "-video_size", "%dx%d" % (width, height),
           "-i", DESKTOP_DISPLAY, "-frames:v", "1"] + scale + [DESKTOP_SHOT]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE,
                              timeout=TIMEOUTS["mudif_desktop_screenshot"], text=True)
    except (OSError, subprocess.SubprocessError) as e:
        return {"source": "guest_desktop_x11grab", "ok": False,
                "error": "ffmpeg capture failed: %s" % e}
    if proc.returncode != 0:
        return {"source": "guest_desktop_x11grab", "ok": False,
                "error": "ffmpeg capture failed with exit %d" % proc.returncode}
    try:
        with open(DESKTOP_SHOT, "rb") as f:
            data = f.read()
    except OSError as e:
        return {"source": "guest_desktop_x11grab", "ok": False,
                "error": "could not read the captured frame: %s" % e}
    if not data:
        return {"source": "guest_desktop_x11grab", "ok": False,
                "error": "ffmpeg produced an empty frame"}
    try:
        os.remove(DESKTOP_SHOT)
    except OSError:
        pass
    return {"source": "guest_desktop_x11grab", "ok": True, "format": "jpeg",
            "display": DESKTOP_DISPLAY, "size": "%dx%d" % (width, height),
            "data": base64.b64encode(data).decode("ascii")}


# --------------------------------------------- guest desktop actions
# Vision increment 2: act by window NAME, never by invented pixel
# coordinates. The window list comes from xwininfo (in the image);
# actions go through xdotool, installed on demand the first time
# one is needed (tiny package, into the persistent guest rootfs,
# never baked into the image). Names are matched in Python against
# the xwininfo list, so no user text ever reaches xdotool as a
# regex. All commands run as argv lists, never through a shell.

DESKTOP_ACTION_TOOLS = {
    "mudif_desktop_windows", "mudif_desktop_window",
    "mudif_desktop_type", "mudif_desktop_key",
}
_KEY_TOKEN = re.compile(r"^[A-Za-z0-9_]+(\+[A-Za-z0-9_]+)*$")


def _ensure_xdotool():
    """xdotool on demand: already installed is a no-op; otherwise
    one apt install. Returns (True, None) or (False, error)."""
    if shutil.which("xdotool"):
        return True, None
    env = dict(os.environ, DEBIAN_FRONTEND="noninteractive")
    try:
        upd = subprocess.run(
            ["apt-get", "update"], stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE, timeout=300, text=True, env=env)
    except (OSError, subprocess.SubprocessError) as e:
        return False, "apt-get update failed: %s" % e
    if upd.returncode != 0:
        return False, "apt-get update failed with exit %d" % upd.returncode
    try:
        ins = subprocess.run(
            ["apt-get", "install", "-y", "--no-install-recommends", "xdotool"],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            timeout=300, text=True, env=env)
    except (OSError, subprocess.SubprocessError) as e:
        return False, "apt-get install xdotool failed: %s" % e
    if ins.returncode != 0:
        return False, "apt-get install xdotool failed with exit %d" % ins.returncode
    if not shutil.which("xdotool"):
        return False, "xdotool still not found after install"
    return True, None


def _list_windows():
    """Top-level guest windows from xwininfo (in the image, no
    xdotool needed). Returns (windows, None) or (None, error dict).
    Windows are {id, title, width, height, x, y} with absolute
    positions; tiny helpers (Firefox and Geany spawn 200x200
    top-level helpers) and nameless windows are dropped, so a bare
    program name matches its real window."""
    try:
        proc = subprocess.run(
            ["xwininfo", "-root", "-tree", "-display", DESKTOP_DISPLAY],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=30, text=True)
    except (OSError, subprocess.SubprocessError) as e:
        return None, {"ok": False, "error": "xwininfo failed: %s" % e}
    if proc.returncode != 0:
        return None, {"ok": False, "error": "xwininfo failed with exit %d" % proc.returncode}
    windows = []
    for line in (proc.stdout or "").splitlines():
        match = re.search(
            r'^\s*(0x[0-9a-fA-F]+)\s+"([^"]*)".*?(\d+)x(\d+)\+(-?\d+)\+(-?\d+)\s+\+(-?\d+)\+(-?\d+)\s*$',
            line)
        if not match:
            continue
        width, height = int(match.group(3)), int(match.group(4))
        if width <= 200 and height <= 200:
            continue
        title = match.group(2)
        if not title:
            continue
        windows.append({"id": match.group(1), "title": title,
                        "width": width, "height": height,
                        "x": int(match.group(7)), "y": int(match.group(8))})
    return windows, None


def _match_window(name):
    """One window whose title contains name (case-insensitive).
    Returns (window, None), or (None, error dict). Zero or several
    matches is an error, never a guess."""
    windows, error = _list_windows()
    if error is not None:
        return None, error
    needle = name.lower()
    matches = [w for w in windows if needle in w["title"].lower()]
    if not matches:
        return None, {"ok": False, "error": "no window matches '%s'" % name,
                      "windows": [w["title"] for w in windows]}
    if len(matches) > 1:
        return None, {"ok": False, "error": "several windows match '%s', be more specific" % name,
                      "matches": [w["title"] for w in matches]}
    return matches[0], None


def desktop_windows(arguments):
    """The open guest windows, by name."""
    windows, error = _list_windows()
    if error is not None:
        return error
    return {"ok": True, "windows": windows}


def desktop_window(arguments):
    """Activate, close, or minimize one window, by name."""
    name = (arguments.get("name") or "").strip()
    action = (arguments.get("action") or "").strip()
    if not name:
        return {"ok": False, "error": "window name is empty"}
    if action not in ("activate", "close", "minimize"):
        return {"ok": False, "error": "unknown action: %s (activate, close, minimize)" % action}
    window, error = _match_window(name)
    if error is not None:
        return error
    ok, err = _ensure_xdotool()
    if not ok:
        return {"ok": False, "error": err}
    cmd = {"activate": ["xdotool", "windowactivate", "--sync", window["id"]],
           "close": ["xdotool", "windowclose", window["id"]],
           "minimize": ["xdotool", "windowminimize", window["id"]]}[action]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                              timeout=TIMEOUTS["mudif_desktop_window"], text=True)
    except (OSError, subprocess.SubprocessError) as e:
        return {"ok": False, "error": "xdotool failed: %s" % e}
    if proc.returncode != 0:
        return {"ok": False, "error": "xdotool %s failed with exit %d" % (action, proc.returncode)}
    return {"ok": True, "action": action, "window": window["title"], "id": window["id"]}


def desktop_type(arguments):
    """Type text into the focused window, or into a named window
    (activated first)."""
    text = arguments.get("text")
    if text is None or text == "":
        return {"ok": False, "error": "text is empty"}
    window_name = (arguments.get("window") or "").strip()
    ok, err = _ensure_xdotool()
    if not ok:
        return {"ok": False, "error": err}
    window = None
    if window_name:
        window, error = _match_window(window_name)
        if error is not None:
            return error
        try:
            act = subprocess.run(
                ["xdotool", "windowactivate", "--sync", window["id"]],
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                timeout=30, text=True)
        except (OSError, subprocess.SubprocessError) as e:
            return {"ok": False, "error": "xdotool activate failed: %s" % e}
        if act.returncode != 0:
            return {"ok": False, "error": "could not activate window '%s'" % window["title"]}
    try:
        proc = subprocess.run(
            ["xdotool", "type", "--clearmodifiers", "--", text],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            timeout=TIMEOUTS["mudif_desktop_type"], text=True)
    except (OSError, subprocess.SubprocessError) as e:
        return {"ok": False, "error": "xdotool type failed: %s" % e}
    if proc.returncode != 0:
        return {"ok": False, "error": "xdotool type failed with exit %d" % proc.returncode}
    out = {"ok": True, "typed_chars": len(text)}
    if window is not None:
        out["window"] = window["title"]
    return out


def desktop_key(arguments):
    """Press X keys on the guest desktop, or into a named window
    (activated first, in the same call, so focus cannot drift
    away between calls)."""
    keys = arguments.get("keys")
    if isinstance(keys, str):
        groups = keys.split()
    elif isinstance(keys, list):
        groups = [str(k) for k in keys]
    else:
        return {"ok": False, "error": "keys must be a string like 'ctrl+s' or a list"}
    if not groups:
        return {"ok": False, "error": "keys is empty"}
    for group in groups:
        if not _KEY_TOKEN.match(group):
            return {"ok": False, "error": "bad key: %s" % group}
    window_name = (arguments.get("window") or "").strip()
    ok, err = _ensure_xdotool()
    if not ok:
        return {"ok": False, "error": err}
    window = None
    if window_name:
        window, error = _match_window(window_name)
        if error is not None:
            return error
        try:
            act = subprocess.run(
                ["xdotool", "windowactivate", "--sync", window["id"]],
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                timeout=30, text=True)
        except (OSError, subprocess.SubprocessError) as e:
            return {"ok": False, "error": "xdotool activate failed: %s" % e}
        if act.returncode != 0:
            return {"ok": False, "error": "could not activate window '%s'" % window["title"]}
    try:
        proc = subprocess.run(
            ["xdotool", "key", "--clearmodifiers"] + groups,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            timeout=TIMEOUTS["mudif_desktop_key"], text=True)
    except (OSError, subprocess.SubprocessError) as e:
        return {"ok": False, "error": "xdotool key failed: %s" % e}
    if proc.returncode != 0:
        return {"ok": False, "error": "xdotool key failed with exit %d" % proc.returncode}
    out = {"ok": True, "keys": groups}
    if window is not None:
        out["window"] = window["title"]
    return out


def desktop_action_call(name, arguments):
    """Dispatch one desktop action tool. Errors live in the dict."""
    os.environ.setdefault("DISPLAY", DESKTOP_DISPLAY)
    try:
        if name == "mudif_desktop_windows":
            return desktop_windows(arguments)
        if name == "mudif_desktop_window":
            return desktop_window(arguments)
        if name == "mudif_desktop_type":
            return desktop_type(arguments)
        return desktop_key(arguments)
    except Exception as e:  # Never let one bad call kill the server.
        return {"ok": False, "error": "internal error: %s" % e}


# ------------------------------------------------------- usage log
# Slice 1 of agent-as-OS (agent-as-os/DESIGN.md section 5). The app
# writes one JSONL line per action into filesDir/tmp, which the guest
# reads as /tmp/usage-log.jsonl. This tool only READS that file:
# grep usage-log in this file to prove no other access exists.
# Errors live in the dict, like the web and desktop tools.
USAGE_LOG_FILE = "/tmp/usage-log.jsonl"


def usage_log(arguments):
    """Read the last N lines of the local usage log. Read-only."""
    count = arguments.get("count", 10)
    try:
        count = int(count)
    except (TypeError, ValueError):
        count = 10
    count = max(1, min(count, 50))
    try:
        with open(USAGE_LOG_FILE, "r", encoding="utf-8") as log_file:
            lines = log_file.read().splitlines()
    except OSError:
        return {"ok": False, "error": "no usage log yet"}
    entries = []
    for line in lines[-count:]:
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return {"ok": True, "entries": entries}


# ------------------------------------------------------- web tools
# Brain fix phase 1 (BRAIN-FIX-PLAN.md sections 9-10). No bridge
# involved: these tools call the search providers and the web
# directly. Errors are returned inside the result JSON (with a
# "source" field), never invented, never a faked result.

WEB_TOOLS = {"mudif_web_search", "mudif_web_fetch", "mudif_web_answer"}


class WebError(Exception):
    pass


def _env_key(name):
    """API key from the environment at call time. None when unset."""
    value = os.environ.get(name, "").strip()
    return value or None


def _clean_text(raw):
    """Strip HTML tags/entities, collapse whitespace."""
    text = html.unescape(raw or "")
    text = re.sub(r"(?is)<[^>]+>", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _truncate_text(text, cap):
    """Cap text length; returns (text, was_truncated)."""
    text = text or ""
    if len(text) <= cap:
        return text, False
    cut = text[:cap]
    if " " in cut:
        cut = cut.rsplit(" ", 1)[0]
    return cut + " ...", True


def _post_json(url, payload, headers, timeout):
    """POST a JSON body, return the parsed dict. WebError on failure."""
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raise WebError("HTTP %d from %s" % (e.code, url))
    except urllib.error.URLError as e:
        raise WebError("cannot reach %s: %s" % (url, e.reason))


def _get_json(url, headers, timeout):
    """GET a URL, return the parsed dict. WebError on failure."""
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raise WebError("HTTP %d from %s" % (e.code, url))
    except urllib.error.URLError as e:
        raise WebError("cannot reach %s: %s" % (url, e.reason))


def allowlist_add(urls):
    """Append URLs to the fetch allowlist with a timestamp; prune
    entries older than ALLOWLIST_TTL_S. One entry per line:
    "<epoch>\\t<url>" (the timestamp is what makes the 30-minute
    expiry enforceable). Best-effort, never raises."""
    try:
        now = int(time.time())
        kept = []
        try:
            with open(ALLOWLIST_FILE, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.rstrip("\n")
                    if "\t" not in line:
                        continue
                    stamp, entry = line.split("\t", 1)
                    try:
                        stamp = int(stamp)
                    except ValueError:
                        continue
                    if now - stamp <= ALLOWLIST_TTL_S and entry.strip():
                        kept.append((stamp, entry.strip()))
        except OSError:
            pass
        seen = {entry for _, entry in kept}
        for url in urls:
            url = (url or "").strip()
            if url and url not in seen:
                kept.append((now, url))
                seen.add(url)
        with open(ALLOWLIST_FILE, "w", encoding="utf-8") as f:
            for stamp, entry in kept:
                f.write("%d\t%s\n" % (stamp, entry))
    except OSError:
        pass


def allowlist_contains(url):
    """True when the exact URL is in the allowlist and still fresh."""
    url = (url or "").strip()
    if not url:
        return False
    try:
        now = int(time.time())
        with open(ALLOWLIST_FILE, "r", encoding="utf-8") as f:
            for line in f:
                if "\t" not in line:
                    continue
                stamp, entry = line.rstrip("\n").split("\t", 1)
                try:
                    if now - int(stamp) > ALLOWLIST_TTL_S:
                        continue
                except ValueError:
                    continue
                if entry.strip() == url:
                    return True
    except OSError:
        return False
    return False


def _search_result(source, results):
    """Shape a search result: allowlist the URLs, fit the JSON in
    WEB_TEXT_CAP by shrinking snippets. Adds "truncated" when cut."""
    results = [r for r in results if r.get("url")]
    allowlist_add([r["url"] for r in results])
    truncated = False
    while results:
        payload = {"source": source, "results": results}
        if len(json.dumps(payload, ensure_ascii=False)) <= WEB_TEXT_CAP:
            break
        longest = max(range(len(results)), key=lambda i: len(results[i].get("snippet", "")))
        snippet = results[longest].get("snippet", "")
        if len(snippet) <= 20:
            results.pop(longest)
        else:
            results[longest]["snippet"] = snippet[:len(snippet) // 2]
        truncated = True
    out = {"source": source, "results": results}
    if truncated:
        out["truncated"] = True
    return out


def _exa_search(query, count, key):
    data = _post_json(
        "https://api.exa.ai/search",
        {"query": query, "numResults": count},
        {"x-api-key": key, "Content-Type": "application/json"},
        30,
    )
    return [
        {"title": r.get("title", ""), "url": r.get("url", ""), "snippet": r.get("text", "")}
        for r in data.get("results", [])[:count]
    ]


def _tavily_search(query, count, key):
    data = _post_json(
        "https://api.tavily.com/search",
        {"api_key": key, "query": query, "max_results": count, "search_depth": "basic"},
        {"Content-Type": "application/json"},
        30,
    )
    return [
        {"title": r.get("title", ""), "url": r.get("url", ""), "snippet": r.get("content", "")}
        for r in data.get("results", [])[:count]
    ]


def _ddg_real_url(href):
    """DuckDuckGo result links are redirectors; unwrap to the real URL."""
    href = html.unescape(href or "").strip()
    if href.startswith("//duckduckgo.com/l/?"):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(href).query)
        target = query.get("uddg", [""])[0]
        return urllib.parse.unquote(target) if target else ""
    if href.startswith("http://") or href.startswith("https://"):
        return href
    if href.startswith("//"):
        return "https:" + href
    return ""


def _ddg_search(query, count):
    """DuckDuckGo HTML, best-effort parse of titles, links, snippets."""
    url = "https://html.duckduckgo.com/html/?q=" + urllib.parse.quote_plus(query)
    request = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            page = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        raise WebError("DuckDuckGo HTTP %d" % e.code)
    except urllib.error.URLError as e:
        raise WebError("cannot reach DuckDuckGo: %s" % e.reason)
    results = []
    for match in re.finditer(
        r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', page, re.S
    ):
        link = _ddg_real_url(match.group(1))
        title = _clean_text(match.group(2))
        if link and title:
            results.append({"title": title, "url": link, "snippet": ""})
        if len(results) >= count:
            break
    if not results:
        # A gate/captcha page parses to zero results: that is a
        # failure, never a silent empty success.
        raise WebError("DuckDuckGo returned no results (bot check?)")
    snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</a>', page, re.S)
    for i, snippet in enumerate(snippets):
        if i < len(results):
            results[i]["snippet"] = _clean_text(snippet)
    return results


def _parse_freeserp(data, count):
    """FreeSerp JSON results into title/url/snippet rows."""
    results = []
    for item in (data.get("results") or [])[:count]:
        url = (item.get("url") or "").strip()
        title = _clean_text(item.get("title") or "")
        snippet = _clean_text(item.get("ai_summary") or "")
        if url and title:
            results.append({"title": title, "url": url, "snippet": snippet})
    if not results:
        raise WebError("FreeSerp returned no results")
    return results


def _freeserp_search(query, count):
    """FreeSerp keyless JSON search (own index, no key, no signup).
    Published terms on freeserp.ai: free, no hard quota, a few
    requests per second, cache when possible. The agent= parameter
    identifies the caller, as their FAQ asks."""
    url = ("https://freeserp.ai/api.php?index=web&q="
           + urllib.parse.quote_plus(query)
           + "&size=%d&agent=Mudif" % count)
    data = _get_json(url, {"User-Agent": _USER_AGENT}, 20)
    if not data.get("ok"):
        raise WebError("FreeSerp error: %s" % (data.get("engine_error") or "unknown"))
    return _parse_freeserp(data, count)


_WIKI_UA = "Mudif/0.1 (https://github.com/taqniamostakbal/mudif)"


def _parse_wikipedia(data, lang, count):
    """MediaWiki list=search JSON into title/url/snippet rows."""
    results = []
    for item in ((data.get("query") or {}).get("search") or [])[:count]:
        title = (item.get("title") or "").strip()
        if not title:
            continue
        url = ("https://%s.wikipedia.org/wiki/" % lang
               + urllib.parse.quote(title.replace(" ", "_")))
        results.append({"title": title, "url": url,
                        "snippet": _clean_text(item.get("snippet") or "")})
    if not results:
        raise WebError("Wikipedia returned no results")
    return results


def _wikipedia_search(query, count):
    """Wikipedia list=search, no key. Arabic queries go to the Arabic
    edition, everything else to English. Wikimedia policy requires a
    descriptive User-Agent, so this call identifies the app."""
    lang = "ar" if re.search(r"[\u0600-\u06FF]", query) else "en"
    url = ("https://%s.wikipedia.org/w/api.php?action=query&list=search"
           "&srsearch=%s&srlimit=%d&format=json&formatversion=2"
           % (lang, urllib.parse.quote_plus(query), count))
    data = _get_json(url, {"User-Agent": _WIKI_UA}, 15)
    return _parse_wikipedia(data, lang, count)


def web_search(arguments):
    """Exa, else Tavily, else DuckDuckGo HTML, else FreeSerp, else
    Wikipedia. One try per provider; the last two need no key."""
    query = (arguments.get("query") or "").strip()
    if not query:
        return {"source": "none", "error": "search failed: empty query"}
    try:
        count = int(arguments.get("count", 5))
    except (TypeError, ValueError):
        count = 5
    count = max(1, min(10, count))
    errors = []
    exa_key = _env_key("EXA_API_KEY")
    if exa_key:
        try:
            return _search_result("exa", _exa_search(query, count, exa_key))
        except WebError as e:
            errors.append("exa: %s" % e)
    tavily_key = _env_key("TAVILY_API_KEY")
    if tavily_key:
        try:
            return _search_result("tavily", _tavily_search(query, count, tavily_key))
        except WebError as e:
            errors.append("tavily: %s" % e)
    try:
        return _search_result("duckduckgo", _ddg_search(query, count))
    except WebError as e:
        errors.append("duckduckgo: %s" % e)
    try:
        return _search_result("freeserp", _freeserp_search(query, count))
    except WebError as e:
        errors.append("freeserp: %s" % e)
    try:
        return _search_result("wikipedia", _wikipedia_search(query, count))
    except WebError as e:
        errors.append("wikipedia: %s" % e)
    return {"source": "none", "error": "search failed: " + "; ".join(errors)}


def _fetch_url(url):
    """GET one URL as decoded text. WebError with the real reason."""
    request = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUTS["mudif_web_fetch"]) as response:
            raw = response.read()
            charset = response.headers.get_content_charset() or "utf-8"
            return raw.decode(charset, "replace")
    except urllib.error.HTTPError as e:
        raise WebError("HTTP %d fetching %s" % (e.code, url))
    except urllib.error.URLError as e:
        raise WebError("cannot fetch %s: %s" % (url, e.reason))


def _html_to_text(page):
    """Strip scripts/styles/tags, collapse whitespace."""
    page = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", page)
    page = re.sub(r"(?is)<[^>]+>", " ", page)
    return _clean_text(page)


def _page_title(page):
    match = re.search(r"(?is)<title[^>]*>(.*?)</title>", page)
    return _clean_text(match.group(1)) if match else ""


def _chromium_path():
    for name in ("chromium", "chromium-browser", "google-chrome", "google-chrome-stable"):
        path = shutil.which(name)
        if path:
            return path
    return None


def web_fetch(arguments):
    """Read one page as text. Allowlist first; anything else refused."""
    url = (arguments.get("url") or "").strip()
    if not url:
        return {"source": "fetch", "error": "url not allowed: empty url"}
    if urllib.parse.urlparse(url).scheme.lower() not in ("http", "https"):
        return {"source": "fetch", "error": "url not allowed: %s" % url}
    if not allowlist_contains(url):
        return {"source": "fetch", "error": "url not allowed: %s" % url}
    try:
        page = _fetch_url(url)
    except WebError as e:
        return {"source": "fetch", "error": str(e)}
    text = _html_to_text(page)
    title = _page_title(page)
    if len(text) < 50:
        # Possibly JavaScript-rendered: one retry with headless
        # Chromium --dump-dom, only when it is installed (on-demand
        # store install, never baked into the image in v1).
        chrome = _chromium_path()
        if chrome is None:
            return {"source": "fetch", "url": url, "title": title,
                    "error": "page needs JavaScript and Chromium is not installed"}
        try:
            dom = subprocess.run(
                [chrome, "--headless", "--disable-gpu", "--no-sandbox", "--dump-dom", url],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                timeout=TIMEOUTS["mudif_web_fetch"], text=True,
            ).stdout
            dom_text = _html_to_text(dom)
            if len(dom_text) > len(text):
                text = dom_text
                title = _page_title(dom) or title
        except (OSError, subprocess.SubprocessError) as e:
            return {"source": "fetch", "url": url, "title": title,
                    "error": "chromium fetch failed: %s" % e}
    text, truncated = _truncate_text(text, WEB_TEXT_CAP)
    out = {"source": "fetch", "url": url, "title": title, "text": text}
    if truncated:
        out["truncated"] = True
    return out


def _answer_from_results(source, results):
    """Tavily path: no model call in the proxy. The brain synthesises
    from the returned snippets; the tool only returns the sources."""
    results = [r for r in results if r.get("url")][:3]
    allowlist_add([r["url"] for r in results])
    citations = [{"title": r.get("title", ""), "url": r["url"]} for r in results]
    answer = "\n".join(r.get("snippet", "") for r in results if r.get("snippet"))
    answer, truncated = _truncate_text(answer, WEB_TEXT_CAP)
    out = {"source": source, "answer": answer, "citations": citations}
    if truncated:
        out["truncated"] = True
    return out


def web_answer(arguments):
    """Exa answer first, else Tavily search + extract of the top 3."""
    query = (arguments.get("query") or "").strip()
    if not query:
        return {"source": "none", "error": "answer failed: empty query"}
    exa_key = _env_key("EXA_API_KEY")
    if exa_key:
        try:
            data = _post_json(
                "https://api.exa.ai/answer",
                {"query": query},
                {"x-api-key": exa_key, "Content-Type": "application/json"},
                60,
            )
            citations = [
                {"title": c.get("title", ""), "url": c.get("url", "")}
                for c in data.get("citations", []) if c.get("url")
            ]
            allowlist_add([c["url"] for c in citations])
            return {"source": "exa-answer", "answer": data.get("answer", ""),
                    "citations": citations}
        except WebError:
            pass  # fall through to Tavily
    tavily_key = _env_key("TAVILY_API_KEY")
    if tavily_key:
        try:
            return _answer_from_results("tavily", _tavily_search(query, 3, tavily_key))
        except WebError:
            pass  # fall through to the honest error
    return {"source": "none",
            "error": "answer failed: no search key set (Exa or Tavily)"}


def web_call(name, arguments):
    """Dispatch one web tool. Errors are inside the result dict."""
    try:
        if name == "mudif_web_search":
            return web_search(arguments)
        if name == "mudif_web_fetch":
            return web_fetch(arguments)
        return web_answer(arguments)
    except Exception as e:  # Never let one bad call kill the server.
        source = "fetch" if name == "mudif_web_fetch" else "none"
        return {"source": source, "error": "internal error: %s" % e}


def handle(request):
    """Handle one JSON-RPC request dict. Returns a response dict, or None for notifications."""
    method = request.get("method")
    req_id = request.get("id")
    params = request.get("params") or {}

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": params.get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "mudif", "version": "1.0.0"},
            },
        }
    if method in ("notifications/initialized", "notifications/cancelled"):
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if name in WEB_TOOLS:
            # Web tools need no bridge token; errors live in the dict.
            result = web_call(name, arguments)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                },
            }
        if name in DESKTOP_TOOLS:
            # Guest desktop capture: no bridge token needed; errors
            # live in the dict, like the web tools.
            result = desktop_screenshot(arguments)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                },
            }
        if name in DESKTOP_ACTION_TOOLS:
            # Guest desktop actions (vision increment 2): no bridge
            # token needed; errors live in the dict, like the web tools.
            result = desktop_action_call(name, arguments)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                },
            }
        if name == "mudif_usage_log":
            # Slice 1 (agent-as-OS): local log read, no bridge token
            # needed; errors live in the dict, like the web tools.
            result = usage_log(arguments)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                },
            }
        if name not in ROUTES:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32602, "message": "Unknown tool: %s" % name},
            }
        try:
            result = bridge_call(name, arguments)
        except BridgeError as e:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32000, "message": str(e)},
            }
        if name == "mudif_android_screenshot" and isinstance(result, dict):
            # Label the source in the result itself: this frame is
            # the Android phone display, never the guest desktop.
            result["source"] = "android_display"
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
            },
        }
    if method == "ping":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": "Method not found: %s" % method},
    }


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError as e:
            sys.stdout.write(json.dumps({
                "jsonrpc": "2.0", "id": None,
                "error": {"code": -32700, "message": "Parse error: %s" % e},
            }) + "\n")
            sys.stdout.flush()
            continue
        try:
            response = handle(request)
        except Exception as e:  # Never let one bad call kill the server.
            response = {
                "jsonrpc": "2.0", "id": request.get("id"),
                "error": {"code": -32603, "message": "Internal error: %s" % e},
            }
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Mudif MCP proxy (guest side, Python 3 stdlib only).

Translates MCP tool calls (JSON-RPC over stdio, the way OpenCode
spawns local MCP servers) into HTTP calls against the Mudif bridge
running in the Android app on the shared loopback
(http://127.0.0.1:18080). One tool per bridge endpoint; no tool
exists for anything the bridge does not implement.

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

import json
import os
import sys
import urllib.error
import urllib.request

BRIDGE_URL = os.environ.get("MUDIF_URL", "http://127.0.0.1:18080").rstrip("/")
TOKEN_FILE = os.environ.get("MUDIF_TOKEN_FILE", "/tmp/mudif-bridge-token")

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
    "mudif_logs": 30,
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
        "description": "One still screenshot of the Android display as base64 JPEG.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_logs",
        "description": "Last 100 lines of the bridge log.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
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

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
        "name": "mudif_logs",
        "description": "Last 100 lines of the bridge log.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "mudif_web_search",
        "description": "Web search: Exa first, Tavily second, DuckDuckGo HTML when no key is set. Returns titles, URLs and snippets. Never invents a result.",
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


def web_search(arguments):
    """Exa, else Tavily, else DuckDuckGo HTML. One try per provider."""
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

#!/usr/bin/env python3
"""Mudif About page (P20).

Replaces the desktop's built-in About dialog entirely: that dialog
(name, version, team, links) can never be rebranded cleanly, so it is
removed from the image and this branded page takes its place.

Python standard library only. Serves one static About page on
http://127.0.0.1:8898/ and opens it in Firefox (via mudif-firefox)
when started with --open. GET /quit stops the server.

Content (Anas's choice: the "المضيف" concept):
  نظام مضيف + app version 0.1.0 + image version
  "أنت المضيف، ولينكس ضيفك"
  "دبيان كامل داخل هاتفك الأندرويد، بلا روت وبلا حاسوب"
  مشروع تقنية المستقبل | Exploring Tech
  owner taqniamostakbal + GitHub profile link + YouTube link.
No personal name anywhere, only the channel and GitHub, as ordered.
No desktop-environment brand string anywhere in the page.
"""
import base64
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8898
APP_VERSION = "0.1.0"
LOGO_PATH = "/usr/share/lxqt/graphics/helix_150.png"
AVATAR_PATH = "/usr/share/mudif/avatar.png"
VERSION_PATH = "/root/.mudif_image_version"
GITHUB_URL = "https://github.com/taqniamostakbal/mudif"
GITHUB_PROFILE_URL = "https://github.com/taqniamostakbal"
YOUTUBE_URL = "https://www.youtube.com/@taqniaMostakbal"


def logo_data_uri():
    try:
        with open(LOGO_PATH, "rb") as f:
            raw = f.read()
        return "data:image/png;base64," + base64.b64encode(raw).decode("ascii")
    except OSError:
        return ""


def avatar_data_uri():
    try:
        with open(AVATAR_PATH, "rb") as f:
            raw = f.read()
        return "data:image/png;base64," + base64.b64encode(raw).decode("ascii")
    except OSError:
        return ""


def image_version():
    try:
        with open(VERSION_PATH, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def page_html():
    logo = logo_data_uri()
    avatar = avatar_data_uri()
    version = image_version()
    logo_html = (
        '<img src="%s" alt="مضيف" class="logo">' % logo
        if logo
        else '<div class="logo-fallback">م</div>'
    )
    avatar_html = (
        '<img src="%s" alt="تقنية المستقبل" class="avatar">' % avatar
        if avatar
        else ""
    )
    version_html = '<p class="version">الإصدار %s</p>' % APP_VERSION
    if version:
        version_html += '<p class="version">صورة النظام %s</p>' % version
    return """<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>عن نظام مضيف</title>
<style>
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{ font-family: Cairo, sans-serif; background: #F7F6FF; color: #1A1A1A;
    min-height: 100vh; display: flex; flex-direction: column; align-items: center; }}
  header {{ background: #593BFE; color: #fff; width: 100%; text-align: center;
    padding: 36px 24px 28px; }}
  .logo {{ width: 120px; height: 120px; border-radius: 50%; background: #fff;
    padding: 8px; box-shadow: 0 4px 16px rgba(0,0,0,.25); }}
  .logo-fallback {{ width: 120px; height: 120px; border-radius: 50%; background: #fff;
    color: #593BFE; display: inline-flex; align-items: center; justify-content: center;
    font-size: 64px; font-weight: bold; box-shadow: 0 4px 16px rgba(0,0,0,.25); }}
  header h1 {{ font-family: Lifta, Cairo, sans-serif; font-size: 44px; margin-top: 16px; }}
  .version {{ color: #DCD6FF; font-size: 16px; margin-top: 4px; }}
  main {{ max-width: 640px; width: 100%; padding: 32px 24px; text-align: center; }}
  .tagline {{ font-size: 28px; font-weight: bold; color: #593BFE; line-height: 1.5; }}
  .oneline {{ font-size: 19px; color: #444; margin-top: 16px; line-height: 1.7; }}
  hr {{ border: none; border-top: 1px solid #DCD6FF; margin: 28px 0; }}
  .credit {{ font-size: 19px; font-weight: bold; }}
  .owner {{ font-size: 19px; color: #593BFE; margin-top: 8px; font-weight: bold; }}
  .avatar {{ width: 120px; height: 120px; border-radius: 50%; object-fit: cover;
    box-shadow: 0 3px 12px rgba(89,59,254,.35); margin-bottom: 14px; }}
  .links {{ display: flex; gap: 12px; justify-content: center; margin-top: 20px;
    flex-wrap: wrap; }}
  .links a {{ display: inline-block; padding: 12px 22px; border-radius: 24px;
    background: #593BFE; color: #fff; text-decoration: none; font-size: 16px;
    font-weight: bold; }}
  .links a.ghost {{ background: #fff; color: #593BFE; border: 2px solid #593BFE; }}
  #quit {{ margin-top: 32px; padding: 12px 28px; border: none; border-radius: 24px;
    background: #E5E2FF; color: #593BFE; font-size: 16px; font-weight: bold;
    font-family: Cairo, sans-serif; cursor: pointer; }}
</style>
</head>
<body>
<header>{logo}<h1>نظام مضيف</h1>{version}</header>
<main>
<p class="tagline">أنت المضيف، ولينكس ضيفك</p>
<p class="oneline">دبيان كامل داخل هاتفك الأندرويد، بلا روت وبلا حاسوب</p>
<hr>
{avatar}<p class="credit">مشروع تقنية المستقبل | Exploring Tech</p>
<p class="owner">taqniamostakbal</p>
<div class="links">
<a href="{github}">GitHub: taqniamostakbal/mudif</a>
<a class="ghost" href="{profile}">github.com/taqniamostakbal</a>
<a class="ghost" href="{youtube}">يوتيوب: تقنية المستقبل</a>
</div>
<button id="quit">إغلاق ✕</button>
</main>
<script>
  document.getElementById('quit').addEventListener('click', function () {{
    fetch('/quit').then(function () {{ window.close(); }});
  }});
</script>
</body>
</html>
""".format(
        logo=logo_html, avatar=avatar_html, version=version_html,
        github=GITHUB_URL, profile=GITHUB_PROFILE_URL, youtube=YOUTUBE_URL
    )


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/":
            body = page_html().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/quit":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"bye")
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        self.send_error(404, "not found")


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("mudif-about on http://127.0.0.1:%d/" % PORT, flush=True)
    if "--open" in sys.argv:
        def open_browser():
            time.sleep(0.8)
            try:
                subprocess.Popen(
                    ["mudif-firefox", "http://127.0.0.1:%d/" % PORT],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    start_new_session=True,
                )
            except OSError:
                pass
        threading.Thread(target=open_browser, daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

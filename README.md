# Mudif (مضيف)

**The host of systems.** An Android app that runs full Linux systems on the
phone — any distro, one tap, in Fusha Arabic, for absolutely everyone.

> Vision: the user never hears the word "Linux". They tap what they want to do;
> the system handles the rest.

## Pillars

1. **Radically easy** — no jargon (no "container", "proot", "distro", "mount").
   Install → pick a use → done. Target: < 5 minutes, < 10 taps to first app.
2. **Any distro** — Debian, Ubuntu, Arch, Fedora, Kali, Alpine… one-tap each.
3. **Lightweight** — Alpine-class micro options (~100 MB); smart storage.
4. **Arabic-first** — full Fusha system (desktop, menus, settings), Cairo font,
   verified glyph shaping, RTL. The first Arabic Linux-on-phone done right.
5. **Visual app store** — install GIMP / VS Code / games from a picture catalog.
   Installed apps appear as normal Android launcher icons.
6. **Gaming container** — Box64 + Wine preconfigured, one tap.
7. **Community containers** — share ready-made setups ("programmer's box",
   "designer's box").
8. **Instant boot** — saved state, resume in seconds.
9. **Phase 2: AI agent** — voice-controlled agent (Darija/Fusha) with eyes
   (screen) and hands (input injection + shell). Free model, hybrid
   local/cloud. Hardest feature — after the core works.

## Project layout

```
mudif/
├── app/                    # Android app (Kotlin, package com.bolm.mudif)
│   └── src/main/
│       ├── java/com/bolm/mudif/
│       ├── res/            # mipmap-* icons, values (RTL, Arabic strings)
│       └── AndroidManifest.xml
├── brand/                  # BRAND.md + icon master
├── doc/ARCHITECTURE.md     # technical architecture
├── gradle/                 # version catalog
└── README.md
```

## Status

Phase 1 scaffold (2026-11-03). Build follows Anas's method: reference →
plan → infer → guide → one need at a time, verified each step. No big-bang.

## Reference study

`~/workspace/tiny-container/` — full source analysis of Tiny Container
(Cateners, GPL-3.0): the `.so` bootstrap trick, custom proot
(`--tiny-storage`, `--assured-path`), PipeWire/AAudio bridges, Termux:X11 +
built-in AVNC frontends. See `doc/ARCHITECTURE.md`.

# Mudif — Architecture

Derived from the full source study of Tiny Container (`~/workspace/tiny-container/`,
41 Kotlin files + 4 JNI modules, Cateners, GPL-3.0). This doc records what we
reuse, what we change, and the phase plan.

## 1. Core trick: the `.so` bootstrap (REUSE)

Android forbids executing files from the app data dir, but native libraries
in `nativeLibraryDir` are executable. Package every Linux binary (busybox,
proot, tar, zstd, virgl_test_server, proot loaders) as fake `.so` files with
mangled names (`lib__bin__proot-latest__.so`), then symlink them into
`filesDir/bootstrap/bin/` with real names. Requires
`useLegacyPackaging = true` so the `.so` files stay extractable.

Result: a full mini-Linux userspace inside the app. No root. No Termux.

## 2. proot (REUSE + EXTEND)

Two variants shipped: `proot-classic` (default, stable) and `proot-latest`.
Custom fork adds:
- `--tiny-storage` — unix-socket listener; the app pushes dynamic bind-mounts
  (`/mnt/...`) when USB/SD devices appear, without restarting proot.
- `--assured-path=` — lstat cache paths; attacks proot's known lstat slowness.

Boot command is a template with `$EXTRA_ARGS`, `$EXTRA_PATH`,
`$EXTRA_LD_LIBRARY_PATH`, `$EXTRA_LD_PRELOAD`, `$EXTRA_ENV` placeholders,
resolved from the merged YAML config, written to a temp script (bypasses the
PTY 4096-byte line limit), then sourced in the terminal session.

Mudif extension: per-distro boot templates (Alpine needs different flags than
Debian); distro profiles live in YAML, not code.

## 3. Terminal backend (REUSE pattern)

A persistent shell session (NetHunter-style `TerminalSession`) is the
command bus: every install/boot/GUI step is a shell command. Signal-9
(LMK kill) is caught and surfaced as a friendly screen, never a crash.

## 4. GUI frontends (REUSE pattern, SIMPLIFY UX)

- **Termux:X11** via `CmdEntryPointService` (env: TMPDIR, XKB_CONFIG_ROOT
  inside the container, TERMUX_X11_DEBUG=1).
- **Built-in AVNC client** over a **unix socket** (`tmp/.tiny.vnc`) —
  bypasses the network stack, lower latency than TCP VNC.
- Readiness gates: wait for the port (TCP/UDP bind probe) or the socket
  file before opening the viewer. Never show a black screen.

Mudif change: the user never picks a frontend. The app chooses per task
(desktop → X11; single app → AVNC) and hides the choice.

## 5. Bridges (REUSE ideas, REWRITE)

| Bridge | Tiny Container | Mudif |
|---|---|---|
| Audio | PipeWire module → unix socket → AAudio (48 kHz PCM, custom JNI) | same pattern |
| Mic | AAudio capture → unix socket → PipeWire virtual mic | same pattern |
| Printing | IPP server on unix socket → Android Print framework | phase 2 |
| Storage | Storage monitor → proot `--tiny-storage` socket | same pattern |

## 6. Config model (REUSE)

One YAML per system: `datadir/<code>/.tiny.yaml`, SnakeYAML, dot-path
access (`feature.0.type`). Feature types: `webview`, `avnc`, `x11`,
`audio`, `print`, `storage`, `lstat-cache` + recursive `options` trees
merged at boot. Mudif adds distro profiles + app-catalog entries as
first-class config.

## 7. What Mudif does differently (the leap)

1. **No jargon UI** — tasks, not systems. ("What do you want to do?")
2. **Distro marketplace** — Debian/Ubuntu/Arch/Fedora/Kali/Alpine one-tap.
3. **Visual app store** — picture catalog → Android launcher icons.
4. **Arabic system** — `ar_MA.UTF-8` locale, Cairo font as system font,
   Arabic DE translations, per-screen glyph QA. Zero tofu.
5. **Gaming box** — Box64 + Wine preconfigured.
6. **Community boxes** — shareable configured systems.
7. **Instant resume** — saved session state.
8. **Phase 2: voice AI agent** — accessibility-tree driven (10x cheaper than
   screenshots), hybrid local/cloud free model.

## 8. Phase plan

- **P1 (now):** scaffold + brand + this doc. DONE 2026-11-03.
- **P2:** bootstrap proof — `.so` packaging + busybox/proot symlinks,
  terminal session, `proot --version` running on-device. (Needs Android
  build env + device test — Anas's call on where it runs.)
- **P3:** first boot — Alpine micro rootfs under proot, shell working.
- **P4:** Arabic system image — locale, Cairo, Xfce Arabic.
- **P5:** GUI (X11) + audio bridge.
- **P6:** distro marketplace + visual app store.
- **P7:** gaming box, community boxes, instant resume.
- **P8:** AI agent.

Each phase: reference → plan → build → verify on-device, one need at a
time. No big-bang.

# PROGRESS.md — Mudif X Server Death Investigation

## 2026-11-05 04:30 — Project Start

### Reference Study: Tiny Container (github.com/Cateners/tiny_container, GPL-3.0)

**Architecture findings:**
- X11 via Termux:X11: `CmdEntryPointService` with `ACTION_START`, args `[":0"]`
- Env: `TMPDIR=${filesDir}/tmp`, `XKB_CONFIG_ROOT=${dataDir}/<code>/usr/share/X11/xkb`, `TERMUX_X11_DEBUG=1`, `TERMUX_X11_OVERRIDE_PACKAGE=<package>`
- **Waits ONLY for socket file** (`waitForFile("${filesDir}/tmp/.X11-unix/X0")`) — NO connection test
- GUI command sent via `Global.sendCommand()` to **persistent PTY terminal session** (NetHunter Terminal), NOT a pipe
- `killXServer()` called in `ViewModel.onCleared()`
- AVNC fallback: Xvfb + x11vnc inside container, AVNC viewer inside app, via unix socket `.tiny.vnc`

**Key difference from Mudif:**
- Mudif P5.6 added `LocalSocket` connection test — Tiny Container does NOT do this. This may be harmful.
- Mudif uses one-shot proot or pipe-based session — Tiny Container uses PTY terminal session.

### Current State (from user screenshots 2026-11-05 03:02–03:19)
- P5.6 installed (toast "نسخة: P5.6-ConnTest" confirmed)
- X server starts, socket file appears
- openbox launches and connects (يشتغل: true)
- **X server dies:** "X connection to :0 broken (explicit kill or server shutdown)"
- Result: black screen, no desktop

### Hypotheses to Test (in order)
1. [ ] **Phantom Process Killer** (Android 12+): kills proot child processes
2. [ ] **Memory pressure** (LMK/OOM): server killed by signal 9
3. [ ] **proot seccomp/trace**: server killed by proot itself
4. [ ] **X socket files**: stale /tmp/.X0-lock or /tmp/.X11-unix/X0, bind issues
5. [ ] **Session env**: missing dbus, XDG_RUNTIME_DIR kills session

### Plan
- [ ] Build diagnostic APK: capture X server stderr + logcat at moment of death
- [ ] Test H1: check Android version, logcat for "phantom" kills
- [ ] Document each hypothesis result
- [ ] Fallback: Plan A (fix X), Plan B (embedded Termux:X11), Plan C (embedded VNC)

---

## 2026-11-05 22:48 — P5.8 REBUILD FROM SCRATCH (user order)

User order (verbatim sense): stop patching, rebuild the desktop layer from
Tiny Container's sources, professionally, with zero problems like Tiny;
add a very detailed log button/icon. Scope confirmed by user: desktop layer
ONLY (same approach, full professionalism), NOT the whole app.

### Study note — Tiny Container v4.2.3 (GPL-3.0), read line by line

**X server — `app/src/main/java/com/fct/tc4/ui/page/ContainerMainViewModel.kt`:**
- `launchXServer()` L311-330: `xserverStarted = true`; env keys
  `[TMPDIR, XKB_CONFIG_ROOT, TERMUX_X11_DEBUG, TERMUX_X11_OVERRIDE_PACKAGE]`
  = `[filesDir/tmp, dataDir/$code/usr/share/X11/xkb, "1", packageName]`;
  `startService(Intent(CmdEntryPointService).ACTION_START
  + EXTRA_ARGS + EXTRA_ENV_KEYS + EXTRA_ENV_VALUES)`.
- x11 feature flow L287-300: launch X in a coroutine, then
  `waitForFile(filesDir/tmp/.X11-unix/X<display>)`, then
  `Global.sendCommand(command)` (the GUI command), then emit OpenX11.
- `waitForFile()` L~560: 15 s timeout, 500 ms poll, `file.exists()` ONLY.
  No connection test, no extra sleep.
- `killXServer()` L332-340: ONLY if `xserverStarted` (avoids spawning a
  `:xserver` process with a stray ACTION_STOP); called from
  `exitContainer()` L135 and `onCleared()` L344.
- `setupEnvironment()` L176-196: `terminalSession?.finishIfRunning()`,
  `newSession(onFinished = { exitCode -> if -9 signal9 handler })`,
  `setupEnvironment()`, `export CONTAINER_DIR`, proot symlink from applib.
- `launchContainer()` L213-250: boot command written to a script file
  `boot_${code}.sh` containing `exec $resolvedBootCmd` — this bypasses the
  PTY 4096-byte single-line limit; then `sendCommand("source boot.sh")`.
- `exitContainer()` L135-148: killXServer, stop audio/mic/ipp/storage,
  `Os.kill(-pid, SIGQUIT)` on the terminal session pid (process GROUP),
  then newSession + setupEnvironment.

**PTY session — `app/src/main/java/com/fct/tc4/ui/misc/Global.kt`:**
- `terminalSession: TerminalSession?` — `com.offsec.nhterm.backend.TerminalSession`
  from `com.github.tiny-computer:terminal-lib:e7a15b7877` (JitPack,
  `gradle/libs.versions.toml`).
- `newSession()` L139-156: `TerminalSession("/system/bin/sh", dataDir, args,
  env, callback)` + `updateSize(80, 24)`.
- `sendCommand()` L177-181: writes `(command + "\n")` UTF-8 bytes into the
  PTY — typed like a user. Real shell job control: `&` works.
- `setupEnvironment()` L158-175: recreates `tmp/run/proot_tmp`; exports
  `BIN_DIR, PUBLIC_DIR, CACHE_DIR, PATH, LD_LIBRARY_PATH, PROOT_LOADER,
  PROOT_LOADER_32, PROOT_TMP_DIR`; mkdirs.
- `init()` L98-112: skips ALL shell setup when the process is not the main
  package (`:xserver` process check via `Application.getProcessName()`).
- `onSessionSignal9`: session exit code -9 opens Signal9Activity.

**Viewer — `app/src/main/java/com/fct/tc4/ui/page/ContainerMainFragment.kt:151-155`:**
- OpenX11 = `Intent(context, com.termux.x11.MainActivity::class.java)` +
  `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT`.

**Terminal lib API — verified against the REAL artifact**
(`terminal-lib-e7a15b7877.aar` from JitPack, `classes.jar` inspected):
- Constructor `TerminalSession(String, String, String[], String[],
  SessionChangedCallback)`; methods `write(byte[],int,int)`,
  `updateSize(int,int)`, `finishIfRunning()`, `getPid()`,
  `getExitStatus()`, `getEmulator()`; static `setResources(Resources)`;
  callback `onTextChanged/onTitleChanged/onSessionFinished/onClipboardText/
  onBell/onColorsChanged`. Matches Tiny's usage exactly.
- No hardcoded boot command or GUI command exists anywhere in Tiny's
  source: both are user-configured per container. Mudif therefore keeps
  its own proven proot invocation (`RootfsManager.prootBuilder`) and
  `openbox` as the GUI command — only the DELIVERY changes (PTY session).

### Root causes of the patch loop (from the comparison)

- R1: GUI commands went through one-shot `ProcessBuilder` proot
  invocations (P5.5) or a pipe-fed shell (P5.4) — NEVER through the same
  persistent session that owns the container. Tiny runs everything in ONE
  PTY session.
- R2: Pipe-fed `/bin/sh` has no controlling terminal: `&` job control is
  unreliable and stdout block-buffers (P5.4 failures). A real PTY fixes
  both.
- R3: No lifecycle kill path: Mudif only had a manual `stopXServer()`;
  Tiny kills in `onCleared()` + `exitContainer()`, guarded by the
  `xserverStarted` flag.
- R4: No container-death detection (Tiny: `onFinished` exit code -9).
- R5: `waitForXSocket()` added a 2 s sleep Tiny does not have (harmless,
  removed for exactness).
- R6: Direct-Process openbox runs in a SEPARATE proot instance from any
  container session; its stdin/stdout pipes are owned by the app process.

### Rebuild plan P5.8 (desktop layer only)

- NEW `engine/ContainerSession.kt`: headless PTY session (NetHunter
  `TerminalSession`): `ensure/boot/sendCommand/stop`, death callback,
  boot-script file (bypasses PTY 4096-byte line limit, like Tiny).
- `RootfsManager`: extract `prootCommandLine()` (argv + env) shared by
  `prootBuilder()` (unchanged behavior for TerminalSession/firstBoot)
  and the PTY boot script; shell-quote helper.
- REWRITE `engine/X11Session.kt`: Tiny-exact start/wait/stop; GUI app
  launched via PTY `sendCommand("DISPLAY=:0 openbox > /root/openbox.log
  2>&1 &")`; openbox-running check via pid marker through the PTY;
  keep `getOpenboxLog()`.
- REWRITE `ui/vnc/VncActivity.kt`: Tiny flow (ensure+boot container ->
  start X -> wait socket -> send GUI cmd -> open viewer); toast
  "نسخة: P5.8-Rebuild"; status text + log button in the layout;
  on X failure show the log dialog instead of silent finish; lifecycle
  guard before showing dialogs.
- NEW `engine/DesktopDiagnostics.kt`: H1 test + logcat collection +
  full state snapshot (shared by dialog + log viewer).
- NEW `ui/logs/LogViewerActivity.kt` (+ layout): detailed log viewer —
  H1, X state, container session state, openbox state + log, logcat —
  copy + refresh. Entry points: VncActivity (desktop screen) + حسابي tab.
- `ui/profile/ProfileFragment.kt` + `fragment_profile.xml`: new row
  "سجلات سطح المكتب" -> LogViewerActivity. `strings.xml` additions.
- `AndroidManifest.xml`: register LogViewerActivity.
- Deps: add `com.github.tiny-computer:terminal-lib:e7a15b7877` to
  `gradle/libs.versions.toml` + `app/build.gradle.kts` (JitPack already
  configured). NOTE: no local Android build exists here; CI build must
  confirm resolution. If stdlib metadata conflicts appear, apply the
  same exclude pattern already used for termux-x11.
- DELETE: `engine/ProotSession.kt` (pipe session, proven broken),
  `engine/GuiSession.kt` + `engine/vnc/RfbClient.kt` (dead VNC, zero
  references anywhere). KEEP `ui/vnc/VncView.kt` (referenced by
  `activity_vnc.xml` binding).
- DO NOT TOUCH: 5 tabs UI, store catalog, TerminalSession/TerminalActivity,
  Alpine boot (RootfsManager extract/firstBoot behavior unchanged),
  signing config, fonts/branding. `prootBuilder()` behavior unchanged.

### Latest field evidence (2026-11-05 22:40-22:42, user screenshots)

- P5.7 IS installed (toast "نسخة: P5.7-Diag" photographed).
- Terminal inside container: `/tmp/.X11-unix/` does NOT exist,
  `/root/openbox.log` does NOT exist — X server never started in that
  session, openbox never attempted. Failure moved one step earlier vs
  P5.5/P5.6 (server started, openbox connected, then died). The WHY
  lives in logcat — the detailed log viewer is built for exactly this.
**What:** Diagnostic APK to test Hypothesis 1 (Phantom Process Killer) and collect logs.
**Changes:**
- `VncActivity.kt`: Added `testHypothesis1()` (checks Android API level, tries to read phantom settings), added `collectAndShowLogs()` (captures logcat filtered for phantom/kill/X11), dialog now shows H1 results + "جمع لوگات" button.
- `X11Session.kt`: Reverted `waitForXSocket()` to Tiny Container approach (wait for file only, no LocalSocket test). The LocalSocket test was NOT in Tiny Container and may have been harmful.
- Version: "نسخة: P5.7-Diag"
**Why:** Need evidence before fixing. H1 requires knowing Android version and seeing phantom kills in logcat.
**Status:** Pushed, build in progress.

## 2026-11-05 ~23:15 — P5.8 REBUILD DONE (code only, NOT built, NOT tested)

**What changed (files):**
- NEW `engine/ContainerSession.kt` — headless PTY session (NetHunter
  `TerminalSession`, terminal-lib). `ensure()` / `bootContainer()` /
  `sendCommand()` / `stop()` + death callback (exit code, -9 = SIGKILL).
  Boot script `mudif_boot.sh` (exports + `exec proot ...`), guest marker
  `.mudif_guest_ready` proves the guest shell is alive.
- REWRITE `engine/X11Session.kt` — Tiny-exact start/wait/stop;
  `waitForXSocket` 15 s / 500 ms / `exists()` only, no 2 s sleep;
  `stopXServer` guarded by the started flag; openbox via PTY
  `sendCommand("DISPLAY=:0 openbox > /root/openbox.log 2>&1 &")`;
  `isOpenboxRunning()` via pid marker + `kill -0` through the PTY.
  Direct-Process launch REMOVED.
- REWRITE `ui/vnc/VncActivity.kt` — Tiny flow (boot container -> start X
  -> wait socket -> send GUI cmd -> open viewer); toast
  "نسخة: P5.8-Rebuild"; status line + log button; failure dialog with
  log-viewer / retry instead of silent finish; `onDestroy` stops X +
  session only when finishing; dialog lifecycle guard.
- NEW `engine/DesktopDiagnostics.kt` — H1 + logcat + X state + PTY state
  + openbox log, one `fullSnapshot()` shared by dialog + viewer.
- NEW `ui/logs/LogViewerActivity.kt` + `activity_log_viewer.xml` —
  detailed log viewer (copy + refresh).
- `ui/profile/ProfileFragment.kt` + `fragment_profile.xml` + `strings.xml`
  — new row "سجلات سطح المكتب" -> LogViewerActivity.
- `AndroidManifest.xml` — LogViewerActivity registered.
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — added
  `com.github.tiny-computer:terminal-lib:e7a15b7877` (API verified against
  the real AAR `classes.jar`).
- `engine/RootfsManager.kt` — `prootCommandLine()` extracted (argv + env
  shared by `prootBuilder()` and the PTY boot script); `shellQuote()`.
  `prootBuilder()` behavior UNCHANGED (same argv, same env, same
  redirectErrorStream).
- DELETED: `engine/ProotSession.kt` (pipe session, proven broken),
  `engine/GuiSession.kt`, `engine/vnc/RfbClient.kt`, `ui/vnc/VncView.kt`
  (dead VNC, zero references after cleanup). `VncView` removed from
  `activity_vnc.xml` + VncActivity.
- UNTOUCHED: 5 tabs UI, store catalog, TerminalSession/TerminalActivity,
  Alpine boot + extract, signing config, fonts/branding.

**New desktop flow (step by step):**
1. VncActivity -> `ContainerSession.bootContainer()`: PTY shell created,
   boot script sourced, proot execs, guest writes ready marker.
2. `X11Session.startXServer()` (Tiny launchXServer, unchanged).
3. `waitForXSocket()` 15 s, file only.
4. `ContainerSession.sendCommand("DISPLAY=:0 openbox ... &")`.
5. Marker check for openbox, diagnostic dialog, open viewer.

**NOT done (honest):** NOTHING was compiled, built, or run here — no
Android SDK build exists on this machine. CI build must confirm:
terminal-lib resolution + all Kotlin/XML references. Then the user tests
on the phone: install as UPDATE, tap سطح المكتب, watch for the desktop,
and use "سجلات سطح المكتب" if it fails. No claim of "works" until that
run + screenshot proof.

# libXlorie.so (x86_64) — why it is here (P5.18)

The Termux:X11 AAR the app depends on
(`com.github.tiny-computer:termux-x11:21159a1405`) ships `libXlorie.so`
for arm64-v8a ONLY. On x86_64 devices the `:xserver` process dies at
class-load time (`CmdEntryPoint.<clinit>`) because
`lib/x86_64/libXlorie.so` is missing — proven by the P5.16 test on the
InstaBox x86_64 box (no X socket, no X process, FATAL EXCEPTION in the
static initializer).

## P5.17 (superseded — DO NOT use the nightly .so)

P5.17 bundled the x86_64 `libXlorie.so` from the OFFICIAL Termux:X11
nightly universal APK (version 1.03.01-f1d9834-02.11.26). It fixed the
missing-lib crash but introduced a NEW one: the nightly .so looks up
`LorieView.nativeInit()J` (long), while the fork's Java (June 2026)
declares `native void nativeInit()` (`()V`). The `:xserver` process
then died with `NoSuchMethodError: LorieView.nativeInit()J` (proven by
the P5.17 test, crash PIDs 15908 + 30098). The JNI interface changed
between June and November 2026 (the native API now threads a
`mNativeContext` long through its methods), so a November .so cannot
run against June Java. The nightly .so was removed in P5.18.

## P5.18 (current) — fork-era .so, built from source

This file is the x86_64 `libXlorie.so` BUILT FROM SOURCE by our own CI
(workflow `.github/workflows/build-libxlorie.yml`, run #1) from
upstream termux/termux-x11 @ `b1c8877e92188edff513d6a248e510c4b87d86aa`
(25 June 2026 — the exact commit the fork merged, so its Java is the
fork's Java). The fork itself cannot build it: its `app/build.gradle`
has `externalNativeBuild` commented out (prebuilt .so only). Upstream
at b1c8877 has the CMake build enabled (application + ABI splits).

Verified match (2026-11-06):
- ELF 64-bit x86-64, 3,488,104 bytes, BuildId
  `0632b06b75c14815e5becff3cecd855ac355b8f7`, md5
  `5680edbcc890b28b4f65ddf17651cabc`.
- Same old-style JNI exports as the AAR's arm64 .so
  (`Java_com_termux_x11_CmdEntryPoint_{start,connected,
  getXConnection,getLogcatOutput,listenForConnections}`).
- `nativeInit` registered as `()V` (void); NO `()J` string anywhere
  in the binary — the fork-era interface, matching the fork's
  `LorieView.nativeInit()V`. (The nightly .so, by contrast, contains
  `()J` and is incompatible.)

Termux:X11 is Apache-2.0 licensed.

Gradle packages `src/main/jniLibs/<abi>/*.so` into the APK as
`lib/<abi>/*.so`, so this file lands as `lib/x86_64/libXlorie.so`.
The AAR still supplies the arm64-v8a copy; there is no duplicate for
any ABI. If the AAR ever ships x86_64 itself, delete this file.

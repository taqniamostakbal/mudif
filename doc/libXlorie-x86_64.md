# libXlorie.so (x86_64) — why it is here (P5.17)

The Termux:X11 AAR the app depends on
(`com.github.tiny-computer:termux-x11:21159a1405`) ships `libXlorie.so`
for arm64-v8a ONLY. On x86_64 devices the `:xserver` process dies at
class-load time (`CmdEntryPoint.<clinit>`) because
`lib/x86_64/libXlorie.so` is missing — proven by the P5.16 test on the
InstaBox x86_64 box (no X socket, no X process, FATAL EXCEPTION in the
static initializer).

This file is the x86_64 `libXlorie.so` from the OFFICIAL Termux:X11
nightly universal APK (`termux-x11-universal-debug.apk`, version
1.03.01-f1d9834-02.11.26), downloaded from
https://github.com/termux/termux-x11/releases/tag/nightly
and md5-verified identical (4f7522346db0ebca4c3a16129c4f5881) to the APK
installed on the test box. Termux:X11 is Apache-2.0 licensed.
Its JNI interface matches the fork's `CmdEntryPoint` (JNI_OnLoad
registers `start`, `connected`, `getXConnection`, `getLogcatOutput`,
`listenForConnections`).

Gradle packages `src/main/jniLibs/<abi>/*.so` into the APK as
`lib/<abi>/*.so`, so this file lands as `lib/x86_64/libXlorie.so`.
The AAR still supplies the arm64-v8a copy; there is no duplicate for
any ABI. If the AAR ever ships x86_64 itself, delete this file.

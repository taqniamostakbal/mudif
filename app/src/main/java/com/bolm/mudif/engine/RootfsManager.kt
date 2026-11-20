package com.bolm.mudif.engine

import android.content.Context
import android.net.ConnectivityManager
import com.bolm.mudif.R
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * P3: Alpine minirootfs bootstrap.
 * Downloads the Alpine minirootfs matching the device ABI (~4MB),
 * extracts it into our private files dir, then runs the first real
 * command inside it: proot -r rootfs /bin/cat /etc/alpine-release.
 *
 * Robustness contract (hardened 2026-11-04 after two field failures):
 * - extract() ALWAYS starts from a wiped dest dir: no stale partial
 *   state can ever survive a failed run.
 * - Symlinks are removed unconditionally before creation: File.exists()
 *   returns false for DANGLING symlinks, so a stale dangling link would
 *   otherwise trip EEXIST on retry.
 * - Completion is atomic: a .ready marker is written ONLY after the full
 *   archive extracted cleanly. isReady() checks the marker, so a partial
 *   extraction can never look "installed".
 * - download() verifies the received size against Content-Length.
 */
object RootfsManager {

    private const val ALPINE_VERSION = "3.24.0"
    private const val BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"
    private const val READY_MARKER = ".mudif_ready"

    private fun abi(): String {
        val a = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return if (a.contains("x86_64")) "x86_64" else "aarch64"
    }

    /**
     * p121 (Option B): the guest architecture ("x86_64" or
     * "aarch64"), the same value abi() uses to pick the Alpine
     * minirootfs and the Debian image. The VNC .deb set is chosen
     * by this value, so the rig (x86_64) and the phone (aarch64)
     * each install their own arch's packages.
     */
    fun guestArch(): String = abi()

    fun alpineUrl(): String {
        val a = abi()
        return "$BASE/$a/alpine-minirootfs-$ALPINE_VERSION-$a.tar.gz"
    }

    fun rootfsDir(context: Context): File =
        File(context.filesDir, "rootfs").apply { mkdirs() }

    /**
     * The shared folder: one directory visible on BOTH sides.
     * Android side: <external-files>/mudif-shared (browsable from
     * the Files app and over USB; app-private, needs no permission).
     * Falls back to the internal files dir when external storage is
     * absent. Guest side: /mnt/mudif-shared (bind-mounted below) with
     * a /root/shared symlink for convenience. The same dir is where
     * screen recordings land, so the user can pull them out.
     */
    fun sharedDir(context: Context): File =
        (context.getExternalFilesDir("mudif-shared")
            ?: File(context.filesDir, "mudif-shared")).apply { mkdirs() }

    /** Guest path of the shared folder (inside proot). */
    const val SHARED_GUEST_PATH = "/mnt/mudif-shared"

    private fun tarball(context: Context): File =
        File(context.filesDir, "alpine-minirootfs.tar.gz")

    /** True only after a COMPLETE verified extraction (marker file). */
    fun isReady(context: Context): Boolean =
        File(rootfsDir(context), READY_MARKER).exists()

    fun download(context: Context, onProgress: (done: Long, total: Long) -> Unit) {
        val out = tarball(context)
        if (out.exists()) out.delete()
        val conn = (URL(alpineUrl()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK)
            throw RuntimeException(context.getString(R.string.ui_dl_failed_http, conn.responseCode))
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
        if (total > 0 && out.length() != total)
            throw RuntimeException(context.getString(R.string.ui_dl_incomplete, out.length(), total))
        if (out.length() < 1024 * 1024)
            throw RuntimeException(context.getString(R.string.ui_dl_too_small, out.length()))
    }

    fun extract(context: Context) {
        val dest = rootfsDir(context)
        // Radical: start from zero. Any partial state from a failed run dies here.
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath + File.separator
        var count = 0
        try {
            TarArchiveInputStream(
                GzipCompressorInputStream(BufferedInputStream(tarball(context).inputStream()))
            ).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val f = File(dest, entry.name)
                    val canon = f.canonicalPath
                    // "./" (the archive root itself) canonicalizes to dest: allow it
                    if (canon != dest.canonicalPath && !canon.startsWith(destPath))
                        throw RuntimeException(context.getString(R.string.ui_dl_bad_entry, entry.name))
                    when {
                        entry.isDirectory -> f.mkdirs()
                        entry.isSymbolicLink -> {
                            f.parentFile?.mkdirs()
                            // Unconditional: exists() is false for DANGLING symlinks,
                            // and a stale one would cause EEXIST on retry.
                            try {
                                android.system.Os.remove(f.absolutePath)
                            } catch (e: Exception) {
                                // didn't exist: the normal case
                            }
                            try {
                                android.system.Os.symlink(entry.linkName, f.absolutePath)
                            } catch (e: Exception) {
                                throw RuntimeException(context.getString(R.string.ui_dl_link_failed, entry.name, e.message))
                            }
                        }
                        else -> {
                            f.parentFile?.mkdirs()
                            tar.copyTo(f.outputStream())
                            try {
                                android.system.Os.chmod(f.absolutePath, entry.mode and 4095)
                            } catch (e: Exception) {
                                // non-fatal: worst case a binary loses +x
                            }
                        }
                    }
                    count++
                    entry = tar.nextEntry
                }
            }
        } catch (e: Exception) {
            // Leave no half-system behind: the next run wipes and retries clean.
            try {
                dest.deleteRecursively()
            } catch (ignored: Exception) {
            }
            throw e
        }
        if (count < 100)
            throw RuntimeException(context.getString(R.string.ui_dl_archive_short, count))
        // Atomic completion: marker written ONLY after everything succeeded.
        File(dest, READY_MARKER).writeText(ALPINE_VERSION)
        tarball(context).delete()
    }

    /**
     * Phone-side diagnostics: reports what is ACTUALLY in the rootfs,
     * so we debug from facts instead of guesses.
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        try {
            val rootfs = File(context.filesDir, "rootfs")
            sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            sb.appendLine(context.getString(R.string.ui_diag_rootfs_exists, rootfs.exists()))
            sb.appendLine("marker: ${File(rootfs, READY_MARKER).exists()}")
            val binCat = File(rootfs, "bin/cat")
            val isLink = try {
                java.nio.file.Files.isSymbolicLink(binCat.toPath())
            } catch (e: Exception) {
                null
            }
            sb.appendLine(context.getString(R.string.ui_diag_bincat_link, isLink))
            if (isLink == true) {
                val target = try {
                    java.nio.file.Files.readSymbolicLink(binCat.toPath()).toString()
                } catch (e: Exception) {
                    context.getString(R.string.ui_error_prefix, e.message)
                }
                sb.appendLine("bin/cat -> $target")
            }
            sb.appendLine(context.getString(R.string.ui_diag_bincat_resolves, binCat.exists()))
            sb.appendLine(context.getString(R.string.ui_diag_busybox, File(rootfs, "bin/busybox").exists()))
            val libDir = File(rootfs, "lib")
            val loaders = libDir.listFiles { f -> f.name.startsWith("ld-musl") }
                ?.map { it.name } ?: emptyList()
            sb.appendLine("ld-musl: $loaders")
            sb.appendLine(context.getString(R.string.ui_diag_bin_count, File(rootfs, "bin").listFiles()?.size))
            sb.appendLine(context.getString(R.string.ui_diag_lib_count, libDir.listFiles()?.size))
        } catch (e: Exception) {
            sb.appendLine(context.getString(R.string.ui_diag_diag_failed, e.message))
        }
        return sb.toString()
    }

    /**
     * Path to the proot loader binary (required: our proot was built with
     * PROOT_UNBUNDLE_LOADER pointing at Termux's libexec, which doesn't
     * exist here; without PROOT_LOADER every execve fails with ENOENT).
     * Shipped as jniLibs/<abi>/libtiny_loader.so, built from the exact
     * proot source (termux/proot 5.1.107.102).
     */
    private fun loaderPath(context: Context): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, "libtiny_loader.so")
        return if (f.exists()) f else null
    }

    /**
     * P5.22 (RADICAL REBUILD, Tiny Container's boot_command, adapted).
     * Single source of truth for the proot invocation, usable BOTH as a
     * ProcessBuilder (one-shot installs via TerminalSession) and as a
     * shell command string (PTY boot script in GuestSession).
     *
     * Tiny Container's boot_command (sample proot-distro.tiny.yaml),
     * adapted to Mudif's Alpine guest:
     * - Whole-filesystem binds: --bind=/dev --bind=/proc --bind=/sys
     *   --bind=/data (Mudif used to bind single nodes only, and had no
     *   /dev, /sys, /data, /dev/fd, /dev/stdin/stdout/stderr at all).
     * - fd binds: --bind=/proc/self/fd:/dev/fd + stdio binds, so the
     *   guest's /dev/fd and stdio symlinks resolve through the host
     *   proc fd table (load-bearing for pty allocation paths).
     * - --bind=/dev/urandom:/dev/random; --bind=<tmp>:/dev/shm +
     *   :/tmp; --bind=<run>:/run; --bind=<rootfs>/sys/.empty:/sys/fs/selinux
     *   (hides SELinux from the guest).
     * - --kill-on-exit (cleanup), --link2symlink,
     *   --kernel-release (fakes uname; P8 fix 2026-11-07: TC's exact
     *   string "Linux localhost 6.17.0-..." broke Debian's libc6
     *   preinst kernel arithmetic, aborting the P8 apt chain, so a
     *   clean numeric version "6.17.0").
     * - P6.5: --sysvipc REMOVED. It enables Termux proot's SysV IPC
     *   extension, which spawns a `proot --shm-helper` on the first
     *   shm request; the helper in this build spins forever on a
     *   missing Termux tmp path (/data/data/com.termux/files/usr/tmp)
     *   and freezes the whole session (STRACE-P65, 2026-11-07).
     *   Without the flag no helper spawns; guest shmget falls
     *   through to the kernel (Qt falls back to non-SHM rendering).
     * - -H / -L are DROPPED: TC-fork-only extensions, Mudif's
     *   termux/proot 5.1.107.102 rejects them ("unknown option").
     * - -0 (fake root) kept: TC runs as uid 1000 with a `tiny` user
     *   that does not exist in Mudif's Alpine guest.
     * - /proc stub binds DEFERRED (phase 2): the stub files do not
     *   exist in Mudif's rootfs.
     *
     * All preparation side effects are the same as before (DNS, /tmp,
     * XKB, fakelink, /dev/pts) plus the new bind targets
     * (/data, /dev/fd, /sys/.empty, filesDir/run).
     */
    private fun prootArgvBase(context: Context, rootfs: File, ready: Boolean): List<String>? {
        val bin = EngineCheck.prootPath(context) ?: return null
        EngineCheck.ensureLibs(context)
        if (!ready) return null
        val loader = loaderPath(context) ?: return null
        val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }
        val runDir = File(context.filesDir, "run").apply { mkdirs() }
        // Audio (real-computer #1): the pulseaudio runtime dir must
        // exist or the daemon refuses to start. /run is bound into
        // the guest, so this host dir IS the guest's /run/user/0.
        File(runDir, "user/0").apply {
            mkdirs()
            try { android.system.Os.chmod(absolutePath, 448) } catch (_: Exception) { }
        }
        File(context.filesDir, "proot_tmp").apply { mkdirs() }
        // DNS fix: the minirootfs ships without a working resolv.conf, so
        // name resolution fails inside proot ("DNS: transient error").
        // p66: the guest uses the phone's own DNS servers (see
        // ensureDns); public resolvers are only the fallback.
        ensureDns(context, rootfs)
        // /tmp must be writable for X servers (lock files, sockets).
        ensureWritableTmp(rootfs)
        // /var/lib/xkb must be writable for XKB keymap compilation.
        ensureWritableXkb(rootfs)
        // libfakelink.so for X servers (SELinux blocks link()).
        ensureFakelink(context, rootfs)
        // P5.12: pty devices for terminal emulators. The minirootfs
        // /dev is empty; the whole-/dev bind below covers it, but the
        // targets must exist for proot to accept the binds.
        ensureDevPty(rootfs)
        // P5.22: bind targets for TC's whole-filesystem binds.
        ensureBootBindTargets(rootfs)
        // Shared folder (real-computer #3): the host dir must exist
        // before proot accepts the bind.
        sharedDir(context)
        // X servers need a writable /tmp for lock files and sockets
        // (/tmp/.X6-lock, /tmp/.X11-unix/). The guest's /tmp is root-owned
        // and the app runs as a normal UID, so bind-mount the app's
        // private tmp dir over the guest's /tmp.
        return listOf(
            bin.absolutePath,
            "-0",
            "-r", rootfs.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "--kernel-release=6.17.0",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/data",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/dev/urandom:/dev/random",
            "--bind=" + rootfs.absolutePath + "/sys/.empty:/sys/fs/selinux",
            "--bind=" + tmpDir.absolutePath + ":/dev/shm",
            "--bind=" + tmpDir.absolutePath + ":/tmp",
            "--bind=" + runDir.absolutePath + ":/run",
            // Shared folder (real-computer #3): the Android
            // <external-files>/mudif-shared dir appears in the guest
            // as /mnt/mudif-shared (and /root/shared). Both the boot
            // session and every one-shot see the same files.
            "--bind=" + sharedDir(context).absolutePath + ":" + SHARED_GUEST_PATH,
            "-w", "/root"
        )
    }

    /** Alpine convenience: proot argv for the Alpine rootfs. */
    private fun prootArgvBase(context: Context): List<String>? =
        prootArgvBase(context, rootfsDir(context), isReady(context))

    fun prootCommandLine(context: Context, guestCommand: List<String>): Pair<List<String>, Map<String, String>>? =
        prootCommandLineFor(context, rootfsDir(context), isReady(context), guestCommand)

    fun prootCommandLineFor(context: Context, rootfs: File, ready: Boolean, guestCommand: List<String>): Pair<List<String>, Map<String, String>>? {
        val base = prootArgvBase(context, rootfs, ready) ?: return null
        val loader = loaderPath(context) ?: return null
        val tmpDir = File(context.filesDir, "tmp")
        val prootTmp = File(context.filesDir, "proot_tmp")
        val argv = base + guestCommand
        val env = mapOf(
            "LD_LIBRARY_PATH" to EngineCheck.binDir(context).absolutePath,
            // P5.10: TMPDIR for the GUEST must be the guest path "/tmp".
            // The host path (filesDir/tmp) does not exist inside the guest
            // namespace; any guest program using $TMPDIR would look in a
            // nonexistent directory. (The X server itself gets its own
            // TMPDIR from the start-service intent, unaffected.)
            "TMPDIR" to "/tmp",
            "PROOT_TMP_DIR" to prootTmp.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            // Mandatory: our proot was built with PROOT_UNBUNDLE_LOADER
            // pointing at Termux's libexec (absent here); without
            // PROOT_LOADER every execve fails with ENOENT. Loader built
            // from the exact proot source.
            "PROOT_LOADER" to loader.absolutePath,
            "HOME" to "/root",
            // P6.3.3: USER/LOGNAME were missing from the one-shot env
            // (the boot argv has USER=root, the one-shot did not), so
            // WhiteSur's lib-core.sh computed MY_USERNAME="" and
            // `getent passwd ''` (rc=2) killed install.sh under
            // errexit (P6.3.2 test, /root/whitesur.log). Every guest
            // command runs as root, so set them here once for all
            // one-shots instead of prefixing each command.
            "USER" to "root",
            "LOGNAME" to "root",
            // Audio (real-computer #1): one-shot guest commands find
            // the pulseaudio daemon through this runtime dir, same as
            // the boot shell.
            "XDG_RUNTIME_DIR" to "/run/user/0",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "ar_MA.UTF-8",
            "LANGUAGE" to "ar",
            "SHELL" to "/bin/bash"
        )
        return argv to env
    }

    /**
     * P5.22, the BOOT argv: the TC boot command. The guest command is
     * `/usr/bin/env -i` with an explicit clean environment + the guest
     * login shell. DISPLAY is set here (127.0.0.1:6, TCP, Mudif's
     * proven InstaBox workaround for the broken Unix-socket connect),
     * so every guest command inherits it and no per-command DISPLAY
     * prefix is needed anywhere. TC runs /bin/bash -l as user `tiny`;
     * Mudif runs /bin/sh -l as root (Debian has bash, but the boot
     * shell only launches the desktop). SHELL=/bin/bash is set here
     * so GUI terminals (qterminal) run bash and source .bashrc
     * (P8FIX2: qterminal ran /bin/sh because SHELL was unset).
     */
    fun bootArgv(context: Context): List<String>? =
        bootArgvFor(context, rootfsDir(context), isReady(context))

    fun bootArgvFor(context: Context, rootfs: File, ready: Boolean): List<String>? {
        val base = prootArgvBase(context, rootfs, ready) ?: return null
        return base + listOf(
            "/usr/bin/env", "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH=",
            "LD_PRELOAD=",
            "DISPLAY=127.0.0.1:6",
            "LANG=ar_MA.UTF-8",
            "LANGUAGE=ar",
            "SHELL=/bin/bash",
            "MOZ_FAKE_NO_SANDBOX=1",
            "MOZ_DISABLE_CONTENT_SANDBOX=1",
            "QTWEBENGINE_DISABLE_SANDBOX=1",
            "ELECTRON_DISABLE_SANDBOX=1",
            // Batch 3 (2026-11-12): GTK4's GL renderer fails under
            // llvmpipe/ZINK (black windows, e.g. file-roller).
            // Cairo software rendering fixes it (screenshot b3-05).
            // Also Phase 3 P3-1 (top 3).
            "GSK_RENDERER=cairo",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            // Audio (real-computer #1): guest apps find the
            // pulseaudio daemon through this runtime dir.
            "XDG_RUNTIME_DIR=/run/user/0",
            "/bin/sh", "-l"
        )
    }

    /**
     * P5.22, Tiny Container's PTY 4096-byte line-limit workaround:
     * the resolved boot command is written to a file as `exec <proot
     * ...>` and the PTY session `source`s it. The `exec` replaces the
     * host shell with proot. Returns the script file, or null if the
     * engine/rootfs/loader is missing.
     */
    fun writeBootScript(context: Context): File? =
        writeBootScriptFor(context, rootfsDir(context), isReady(context), "boot_mudif.sh")

    /** P6: boot script for any rootfs (Debian uses "boot_debian.sh"). */
    fun writeBootScriptFor(context: Context, rootfs: File, ready: Boolean, scriptName: String): File? {
        val argv = bootArgvFor(context, rootfs, ready) ?: return null
        val script = File(context.filesDir, scriptName)
        val line = "exec " + argv.joinToString(" ") { shellQuote(it) }
        // p79 R8: content-compare gate. The boot argv is identical
        // on every entry, so the script is rewritten identically
        // each time; skip the write when the bytes match.
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (script.exists() && script.readBytes().contentEquals(bytes)) {
            return script
        }
        script.writeBytes(bytes)
        return script
    }

    /** Shell-quote one argv element for the PTY boot script. */
    fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"

    /**
     * Builds a proot ProcessBuilder for running [guestCommand] inside the Alpine
     * rootfs. Returns null if the engine, rootfs, or loader is missing.
     * Shared by firstBoot() and TerminalSession, single source of truth for
     * the proot invocation (flags + environment).
     */
    fun prootBuilder(context: Context, guestCommand: List<String>): ProcessBuilder? =
        prootBuilderFor(context, rootfsDir(context), isReady(context), guestCommand)

    /** P6: proot ProcessBuilder for any rootfs (Debian one-shots). */
    fun prootBuilderFor(context: Context, rootfs: File, ready: Boolean, guestCommand: List<String>): ProcessBuilder? {
        val (argv, env) = prootCommandLineFor(context, rootfs, ready, guestCommand) ?: return null
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment().putAll(env)
        return pb
    }

    /**
     * Ensures the guest can resolve DNS names.
     *
     * P6.1 FIX (proven by the P6 test, 2026-11-07): the old check
     * ("contains nameserver") accepted Debian's shipped stub
     * `nameserver 127.0.0.53` (systemd-resolved), which resolves
     * NOTHING under proot, apt then failed on every name and the
     * whole XFCE install died. Only a REAL (non-loopback) nameserver
     * counts as working DNS; a stub/loopback-only file is overwritten.
     *
     * p66: the guest uses the PHONE's DNS servers (the same servers
     * Android itself resolves with, read from ConnectivityManager),
     * not hardcoded public ones. The image bakes 8.8.8.8/1.1.1.1 and
     * the old code wrote those whenever the file had no real
     * nameserver; on a network that blocks third-party DNS (inwi is
     * reported to do exactly this) the whole guest is then offline
     * while the phone itself works, which is the Firefox "nothing
     * loads at all" report. This runs on every proot invocation, so
     * the file follows Wi-Fi/mobile-data changes with no marker bump.
     * Public resolvers are only the fallback when Android reports no
     * DNS at all, and a genuine non-loopback file is left alone then.
     */
    private fun ensureDns(context: Context, rootfs: File) {
        val resolvConf = File(rootfs, "etc/resolv.conf")
        val current = try {
            if (resolvConf.exists()) resolvConf.readText() else ""
        } catch (_: Exception) { "" }
        val servers = current.lines()
            .map { it.trim() }
            .filter { it.startsWith("nameserver") }
            .mapNotNull { it.split(Regex("\\s+")).getOrNull(1) }
        // p67: the local was named "android" and shadowed the android
        // package for the rest of this function, so the
        // android.system.Os.remove call below failed to compile
        // ("Unresolved reference 'system'"). Renamed to phoneDns.
        val phoneDns = androidDnsServers(context)
        val wanted = when {
            phoneDns.isNotEmpty() -> phoneDns
            servers.any { s ->
                !s.startsWith("127.") && s != "::1" && s != "localhost"
            } -> return
            else -> listOf("8.8.8.8", "1.1.1.1")
        }
        if (servers == wanted) return
        try {
            resolvConf.parentFile?.mkdirs()
            // Debian ships resolv.conf as a symlink to the systemd
            // stub; replace the link with a real file, or the write
            // lands in the stub target instead of the guest's file.
            try {
                if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
                    android.system.Os.remove(resolvConf.absolutePath)
                }
            } catch (_: Exception) {
                // not a symlink (the normal case for proot-distro)
            }
            resolvConf.writeText(wanted.joinToString("") { "nameserver $it\n" })
        } catch (_: Exception) { /* non-fatal */ }
    }

    /** DNS servers of the phone's active network, empty when none are reported. */
    private fun androidDnsServers(context: Context): List<String> {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
                ?: return emptyList()
            props.dnsServers.mapNotNull { it.hostAddress?.takeIf { h -> h.isNotBlank() } }
                .distinct().take(3)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Makes the guest's /tmp writable (1777). The tarball's /tmp has
     * restrictive modes; X servers need to create lock files and sockets
     * there (/tmp/.X6-lock, /tmp/.X11-unix/). The app owns the extracted
     * files, so chmod always succeeds. Belt-and-suspenders alongside the
     * /tmp bind-mount in prootBuilder.
     */
    private fun ensureWritableTmp(rootfs: File) {
        try {
            val tmp = File(rootfs, "tmp")
            tmp.mkdirs()
            android.system.Os.chmod(tmp.absolutePath, 1777)
        } catch (_: Exception) { /* non-fatal */ }
    }

    /**
     * Makes XKB writable. Xvfb needs to compile keymaps via xkbcomp, which
     * writes to /var/lib/xkb/ (root-owned in the tarball). The app owns the
     * extracted files, so chmod works.
     */
    private fun ensureWritableXkb(rootfs: File) {
        try {
            val xkb = File(rootfs, "var/lib/xkb")
            xkb.mkdirs()
            android.system.Os.chmod(xkb.absolutePath, 511) // 0777
        } catch (_: Exception) { /* non-fatal */ }
    }

    /**
     * P5.12: creates the guest-side bind targets for the pty devices
     * (/dev/pts dir + /dev/ptmx file). proot binds need the target to
     * exist; the minirootfs /dev is empty. Non-fatal if it fails.
     */
    private fun ensureDevPty(rootfs: File) {
        try {
            File(rootfs, "dev/pts").mkdirs()
            val ptmx = File(rootfs, "dev/ptmx")
            if (!ptmx.exists()) ptmx.createNewFile()
        } catch (_: Exception) { /* non-fatal */ }
    }

    /**
     * P5.22: creates the guest-side targets for Tiny Container's
     * whole-filesystem binds. proot refuses a bind whose target does
     * not exist in the rootfs; the Alpine minirootfs ships without
     * /data and without /dev/fd, and sys/.empty (the SELinux-hiding
     * stub) does not exist either. Non-fatal if it fails.
     */
    private fun ensureBootBindTargets(rootfs: File) {
        try {
            File(rootfs, "data").mkdirs()
            File(rootfs, "dev/fd").mkdirs()
            File(rootfs, "sys").mkdirs()
            File(rootfs, "sys/.empty").mkdirs()
            File(rootfs, "proc").mkdirs()
            // Shared folder (real-computer #3): the bind target must
            // exist in the rootfs, plus a /root/shared convenience
            // symlink. Idempotent: a correct link is left alone, a
            // wrong one is replaced.
            File(rootfs, "mnt/mudif-shared").mkdirs()
            val link = File(rootfs, "root/shared")
            val want = SHARED_GUEST_PATH
            try {
                val isLink = java.nio.file.Files.isSymbolicLink(link.toPath())
                val target = if (isLink) {
                    java.nio.file.Files.readSymbolicLink(link.toPath()).toString()
                } else null
                if (target != want) {
                    if (link.exists() || isLink) {
                        try { android.system.Os.remove(link.absolutePath) } catch (_: Exception) {}
                    }
                    android.system.Os.symlink(want, link.absolutePath)
                }
            } catch (_: Exception) { /* non-fatal */ }
        } catch (_: Exception) { /* non-fatal */ }
    }

    /**
     * Installs libfakelink.so into the guest's /usr/lib so X servers can
     * use LD_PRELOAD=/usr/lib/libfakelink.so to work around Android
     * SELinux blocking the link() syscall (X lock files).
     */
    private fun ensureFakelink(context: Context, rootfs: File) {
        try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val sub = if (abi.contains("x86_64")) "x86_64" else "arm64"
            val dest = File(rootfs, "usr/lib/libfakelink.so")
            // p69: copy only when the installed file is missing or
            // differs (size, then bytes). The old code deleted and
            // re-copied on EVERY proot invocation, including every
            // one-shot, which is pure tax once the file is in
            // place. A stale glibc build is caught by the size
            // check in practice and by the byte check for certain:
            // identical bytes are the same file. An unreadable
            // destination counts as different, so a corrupt file
            // is always overwritten, never kept.
            val assetBytes = context.assets.open("fakelink/$sub/libfakelink.so").use { it.readBytes() }
            val needsCopy = try {
                if (!dest.exists()) true
                else if (dest.length() != assetBytes.size.toLong()) true
                else !dest.readBytes().contentEquals(assetBytes)
            } catch (_: Exception) {
                true
            }
            if (!needsCopy) return
            dest.parentFile?.mkdirs()
            dest.writeBytes(assetBytes)
        } catch (_: Exception) { /* non-fatal */ }
    }

    /**
     * First real boot: runs /bin/cat /etc/alpine-release inside the rootfs.
     * Returns "Alpine خدام ✓" + version on success, else the raw output.
     *
     * Environment (validated against Tiny Container's proot setup):
     * - PROOT_TMP_DIR + TMPDIR point at our private dirs: the Termux proot
     *   binary hardcodes /data/data/com.termux/files/usr/tmp which does not
     *   exist outside Termux (hence the "can't canonicalize" warnings).
     * - PROOT_NO_SECCOMP=1: proot's own documented workaround for kernels
     *   where its seccomp accelerator breaks ptrace(TRACEME)
     *   ("Operation not permitted" on emulators like BlueStacks).
     */
    fun firstBoot(context: Context): String {
        return try {
            val pb = prootBuilder(context, listOf("/bin/cat", "/etc/alpine-release"))
                ?: return context.getString(R.string.ui_boot_no_engine)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            val lower = out.lowercase()
            val failed = lower.contains("cannot link") ||
                lower.contains("not found") ||
                lower.contains("no such file") ||
                lower.contains("syntax error") ||
                lower.contains("exec format") ||
                code != 0
            when {
                out.isEmpty() -> context.getString(R.string.ui_boot_no_output, code)
                failed -> context.getString(R.string.ui_boot_failed, code, "$out\n\n--- تشخيص ---\n${diagnose(context)}")
                else -> context.getString(R.string.ui_boot_ok_alpine, out)
            }
        } catch (e: Exception) {
            context.getString(R.string.ui_error_prefix, e.message)
        }
    }
}

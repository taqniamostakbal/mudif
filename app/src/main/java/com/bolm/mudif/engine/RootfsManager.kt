package com.bolm.mudif.engine

import android.content.Context
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

    fun alpineUrl(): String {
        val a = abi()
        return "$BASE/$a/alpine-minirootfs-$ALPINE_VERSION-$a.tar.gz"
    }

    fun rootfsDir(context: Context): File =
        File(context.filesDir, "rootfs").apply { mkdirs() }

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
            throw RuntimeException("التحميل فشل: HTTP ${conn.responseCode}")
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
            throw RuntimeException("التحميل ناقص: وصل ${out.length()} من $total بايت — أعد المحاولة")
        if (out.length() < 1024 * 1024)
            throw RuntimeException("ملف التحميل صغير بشكل مشبوه (${out.length()} بايت)")
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
                        throw RuntimeException("مسار مشبوه في الأرشيف: ${entry.name}")
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
                                throw RuntimeException("تعذر إنشاء الرابط ${entry.name}: ${e.message}")
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
            throw RuntimeException("الأرشيف ناقص بشكل مشبوه ($count مدخل فقط)")
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
            sb.appendLine("rootfs موجود: ${rootfs.exists()}")
            sb.appendLine("marker: ${File(rootfs, READY_MARKER).exists()}")
            val binCat = File(rootfs, "bin/cat")
            val isLink = try {
                java.nio.file.Files.isSymbolicLink(binCat.toPath())
            } catch (e: Exception) {
                null
            }
            sb.appendLine("bin/cat رابط؟: $isLink")
            if (isLink == true) {
                val target = try {
                    java.nio.file.Files.readSymbolicLink(binCat.toPath()).toString()
                } catch (e: Exception) {
                    "خطأ: ${e.message}"
                }
                sb.appendLine("bin/cat -> $target")
            }
            sb.appendLine("bin/cat يتحل؟: ${binCat.exists()}")
            sb.appendLine("bin/busybox موجود: ${File(rootfs, "bin/busybox").exists()}")
            val libDir = File(rootfs, "lib")
            val loaders = libDir.listFiles { f -> f.name.startsWith("ld-musl") }
                ?.map { it.name } ?: emptyList()
            sb.appendLine("ld-musl: $loaders")
            sb.appendLine("عدد ملفات bin: ${File(rootfs, "bin").listFiles()?.size}")
            sb.appendLine("عدد ملفات lib: ${libDir.listFiles()?.size}")
        } catch (e: Exception) {
            sb.appendLine("التشخيص فشل: ${e.message}")
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
     * P5.8 (Tiny Container rebuild): single source of truth for the proot
     * invocation, usable BOTH as a ProcessBuilder (terminal / firstBoot)
     * and as a shell command string (PTY boot script in ContainerSession).
     *
     * Returns argv + environment. All preparation side effects are the
     * same as prootBuilder() used to do inline (DNS, /tmp, XKB, fakelink).
     */
    fun prootCommandLine(context: Context, guestCommand: List<String>): Pair<List<String>, Map<String, String>>? {
        val bin = EngineCheck.prootPath(context) ?: return null
        EngineCheck.ensureLibs(context)
        val rootfs = rootfsDir(context)
        if (!isReady(context)) return null
        val loader = loaderPath(context) ?: return null
        val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }
        val prootTmp = File(context.filesDir, "proot_tmp").apply { mkdirs() }
        // DNS fix: the minirootfs ships without a working resolv.conf, so
        // name resolution fails inside proot ("DNS: transient error").
        // Write public DNS servers into the guest's resolv.conf.
        ensureDns(rootfs)
        // /tmp must be writable for X servers (lock files, sockets).
        ensureWritableTmp(rootfs)
        // /var/lib/xkb must be writable for XKB keymap compilation.
        ensureWritableXkb(rootfs)
        // libfakelink.so for X servers (SELinux blocks link()).
        ensureFakelink(context, rootfs)
        // X servers need a writable /tmp for lock files and sockets
        // (/tmp/.X0-lock, /tmp/.X11-unix/). The guest's /tmp is root-owned
        // and the app runs as a normal UID, so bind-mount the app's
        // private tmp dir over the guest's /tmp.
        // The minirootfs ships with an empty /dev; bind the host's basic
        // device nodes so /dev/null, /dev/zero, /dev/urandom exist.
        val argv = listOf(bin.absolutePath, "-0", "-r", rootfs.absolutePath,
            "-b", tmpDir.absolutePath + ":/tmp",
            "-b", "/dev/null:/dev/null",
            "-b", "/dev/zero:/dev/zero",
            "-b", "/dev/urandom:/dev/urandom",
            "-b", "/dev/random:/dev/random",
            "-w", "/root") + guestCommand
        val env = mapOf(
            "LD_LIBRARY_PATH" to EngineCheck.binDir(context).absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PROOT_TMP_DIR" to prootTmp.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            // Mandatory: our proot was built with PROOT_UNBUNDLE_LOADER
            // pointing at Termux's libexec (absent here); without
            // PROOT_LOADER every execve fails with ENOENT. Loader built
            // from the exact proot source.
            "PROOT_LOADER" to loader.absolutePath,
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )
        return argv to env
    }

    /** Shell-quote one argv element for the PTY boot script. */
    fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"

    /**
     * Builds a proot ProcessBuilder for running [guestCommand] inside the Alpine
     * rootfs. Returns null if the engine, rootfs, or loader is missing.
     * Shared by firstBoot() and TerminalSession — single source of truth for
     * the proot invocation (flags + environment).
     */
    fun prootBuilder(context: Context, guestCommand: List<String>): ProcessBuilder? {
        val (argv, env) = prootCommandLine(context, guestCommand) ?: return null
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment().putAll(env)
        return pb
    }

    /**
     * Ensures the guest can resolve DNS names. The Alpine minirootfs has no
     * usable /etc/resolv.conf, and Android's DNS is not reachable via
     * standard paths, so we write public resolvers into the guest.
     */
    private fun ensureDns(rootfs: File) {
        val resolvConf = File(rootfs, "etc/resolv.conf")
        val current = try {
            if (resolvConf.exists()) resolvConf.readText() else ""
        } catch (_: Exception) { "" }
        if (!current.contains("nameserver")) {
            try {
                resolvConf.parentFile?.mkdirs()
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            } catch (_: Exception) { /* non-fatal */ }
        }
    }

    /**
     * Makes the guest's /tmp writable (1777). The tarball's /tmp has
     * restrictive modes; X servers need to create lock files and sockets
     * there (/tmp/.X0-lock, /tmp/.X11-unix/). The app owns the extracted
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
     * Installs libfakelink.so into the guest's /usr/lib so X servers can
     * use LD_PRELOAD=/usr/lib/libfakelink.so to work around Android
     * SELinux blocking the link() syscall (X lock files).
     */
    private fun ensureFakelink(context: Context, rootfs: File) {
        try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val sub = if (abi.contains("x86_64")) "x86_64" else "arm64"
            val dest = File(rootfs, "usr/lib/libfakelink.so")
            // Always refresh: an older (glibc) build may already exist here.
            // The nolibc build is self-contained and works everywhere.
            if (dest.exists()) dest.delete()
            dest.parentFile?.mkdirs()
            context.assets.open("fakelink/$sub/libfakelink.so").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
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
                ?: return "المحرك غير موجود أو النظام غير مكتمل"
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
                out.isEmpty() -> "خرج بلا مخرجات (code=$code)"
                failed -> "الإقلاع فشل (code=$code):\n$out\n\n--- تشخيص ---\n${diagnose(context)}"
                else -> "Alpine خدام ✓\nالإصدار: $out"
            }
        } catch (e: Exception) {
            "خطأ: ${e.message}"
        }
    }
}

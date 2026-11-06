package com.bolm.mudif.engine

import android.content.Context
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * P6 — نظام مضيف: Debian 13.1 Trixie as THE system of the app.
 *
 * Source (proven live by the 2026-11-06 diagnostic, Q1–Q4):
 * proot-distro v4.29.0 trixie tarballs, both arches. The tarball has
 * ONE top-level dir (debian-trixie-<arch>/) — extraction strips it,
 * exactly like proot-distro does. The download is verified against
 * the published sha256 before any extraction.
 *
 * Shape (diagnostic Q3): the Debian guest boots with Mudif's EXACT
 * proot argv + boot env (RootfsManager.prootBuilderFor / bootArgvFor
 * with this rootfs), launched HOST-side — never nested inside the
 * Alpine guest (nested proot fails: the Android linker
 * /system/bin/linker64 does not exist inside the guest).
 *
 * No glycin here: Debian's gdk-pixbuf ships native PNG/JPEG loaders
 * and glycin is not in the XFCE install closure, so the P5.25
 * bwrap failure mode does not exist on this system.
 *
 * Same robustness contract as RootfsManager (P3): extract() always
 * starts from a wiped dest dir, symlinks are removed unconditionally
 * before creation, completion is atomic via the .mudif_ready marker.
 */
object DebianSystem {

    const val DEBIAN_VERSION = "13.1"
    private const val PROOT_DISTRO_VERSION = "4.29.0"
    private const val BASE =
        "https://github.com/termux/proot-distro/releases/download/v$PROOT_DISTRO_VERSION"
    private const val READY_MARKER = ".mudif_ready"

    // sha256 of the proot-distro v4.29.0 trixie tarballs, verified live
    // 2026-11-06 (diagnostic): x86_64 36,728,936 B; aarch64 ~34 MB.
    private const val SHA256_X86_64 =
        "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716"
    private const val SHA256_AARCH64 =
        "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"

    private fun abi(): String {
        val a = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return if (a.contains("x86_64")) "x86_64" else "aarch64"
    }

    fun debianUrl(): String {
        val a = abi()
        return "$BASE/debian-trixie-$a-pd-v$PROOT_DISTRO_VERSION.tar.xz"
    }

    fun expectedSha256(): String =
        if (abi() == "x86_64") SHA256_X86_64 else SHA256_AARCH64

    fun debianRootfsDir(context: Context): File =
        File(context.filesDir, "debian-rootfs").apply { mkdirs() }

    private fun tarball(context: Context): File =
        File(context.filesDir, "debian-trixie.tar.xz")

    /** True only after a COMPLETE verified extraction (marker file). */
    fun isReady(context: Context): Boolean =
        File(debianRootfsDir(context), READY_MARKER).exists()

    fun download(context: Context, onProgress: (done: Long, total: Long) -> Unit) {
        val out = tarball(context)
        if (out.exists()) out.delete()
        val conn = (URL(debianUrl()).openConnection() as HttpURLConnection).apply {
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
        // P6: verify the tarball against the proot-distro published
        // hash (diagnostic-verified). A corrupted 35 MB download must
        // never extract into the system.
        val actual = sha256Hex(out)
        if (!actual.equals(expectedSha256(), ignoreCase = true))
            throw RuntimeException("التحميل تالف: البصمة لا تطابق المصدر — أعد المحاولة")
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun extract(context: Context) {
        val dest = debianRootfsDir(context)
        // Radical: start from zero. Any partial state from a failed run dies here.
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath + File.separator
        var count = 0
        try {
            TarArchiveInputStream(
                XZCompressorInputStream(BufferedInputStream(tarball(context).inputStream()))
            ).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    // proot-distro tarballs have ONE top-level dir
                    // (debian-trixie-<arch>/): strip it, like proot-distro does.
                    val raw = entry.name.trimStart('/')
                    val stripped = raw.substringAfter('/', "")
                    if (stripped.isEmpty()) {
                        entry = tar.nextEntry
                        continue
                    }
                    val f = File(dest, stripped)
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
                        entry.isLink -> {
                            // Hard link: the target is another path in
                            // the archive (same top-level dir stripped).
                            val targetRaw = entry.linkName.trimStart('/')
                            val targetStripped = targetRaw.substringAfter('/', "")
                            if (targetStripped.isNotEmpty()) {
                                f.parentFile?.mkdirs()
                                try {
                                    android.system.Os.remove(f.absolutePath)
                                } catch (e: Exception) {
                                }
                                try {
                                    android.system.Os.link(
                                        File(dest, targetStripped).absolutePath,
                                        f.absolutePath
                                    )
                                } catch (e: Exception) {
                                    throw RuntimeException("تعذر إنشاء الرابط الصلب ${entry.name}: ${e.message}")
                                }
                            }
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
                            // proot-distro tarballs ship no device nodes
                            // (0 mknod lines in the diagnostic); skip any
                            // that appear — the app cannot mknod them.
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
        if (count < 1000)
            throw RuntimeException("الأرشيف ناقص بشكل مشبوه ($count مدخل فقط)")
        // Atomic completion: marker written ONLY after everything succeeded.
        File(dest, READY_MARKER).writeText(DEBIAN_VERSION)
        tarball(context).delete()
    }

    /** proot one-shot builder for the Debian rootfs (same shape as Alpine). */
    fun prootBuilder(context: Context, guestCommand: List<String>): ProcessBuilder? =
        RootfsManager.prootBuilderFor(
            context, debianRootfsDir(context), isReady(context), guestCommand
        )

    /** Boot script for the PTY session: exec <proot ... /bin/sh -l>. */
    fun writeBootScript(context: Context): File? =
        RootfsManager.writeBootScriptFor(
            context, debianRootfsDir(context), isReady(context), "boot_debian.sh"
        )

    /**
     * First real boot: /bin/cat /etc/debian_version inside the rootfs.
     * Returns "دبيان خدام ✓" + version on success, else the raw output.
     */
    fun firstBoot(context: Context): String {
        return try {
            val pb = prootBuilder(context, listOf("/bin/cat", "/etc/debian_version"))
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
                failed -> "الإقلاع فشل (code=$code):\n$out"
                else -> "دبيان خدام ✓\nالإصدار: $out"
            }
        } catch (e: Exception) {
            "خطأ: ${e.message}"
        }
    }
}

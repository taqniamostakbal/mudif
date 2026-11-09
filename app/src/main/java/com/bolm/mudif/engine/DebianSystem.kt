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
 * P17 — نظام مضيف: Debian 13.1 Trixie as THE system of the app,
 * shipped as a PRE-BUILT image (no on-device apt install anymore).
 *
 * Source: the Mudif image built by image-build/build-image.sh
 * (Debian trixie + the full LXQt set + dbus + libpam-systemd +
 * the Mudif look files + #593BFE palette + ar_MA.UTF-8), hosted
 * as 20 MB parts on Hugging Face (public dataset
 * 855teche/mudif-images). Phones download the aarch64 image
 * (20 parts), the rig downloads x86_64 (22 parts). The image has
 * ONE top-level dir (mudif-p17-<arch>/) — extraction strips it,
 * exactly like the old proot-distro tarballs did. The reassembled
 * file is verified against its sha256 before any extraction.
 *
 * Markers inside the image: /root/.mudif_image_version = "p17-v1",
 * /root/.mudif_desktop_ok = "DEBIAN_DESKTOP_OK" (proves the full
 * desktop set is installed — X11Session.ensureDebianDesktop checks
 * it instead of running apt). .mudif_ready is NOT baked: the app
 * writes it after its own verified extraction, as before.
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
 *
 * Why P17 exists: the on-device apt install (P6–P16.6) could never
 * finish on the borrowed Nova 9 — the app's own idle timeout killed
 * it mid-install (P16.5/P16.6, proven on the phone), and an
 * orphaned apt-get then held the dpkg lock. Shipping the desktop
 * pre-installed removes the install entirely.
 */
object DebianSystem {

    const val DEBIAN_VERSION = "13.1"
    private const val HF_BASE =
        "https://huggingface.co/datasets/855teche/mudif-images/resolve/main/"
    private const val IMAGE_VERSION = "p17-v1"
    private const val READY_MARKER = ".mudif_ready"

    // P17 pre-built image, hosted as 20 MB parts (every part is
    // 20,971,520 B except the last). Totals + sha256 verified
    // 2026-11-09 (builder + independent re-download of the parts):
    // x86_64 457,723,716 B (22 parts), aarch64 410,664,128 B (20 parts).
    private const val SHA256_X86_64 =
        "ea98803f5f7317d4ba492feba60a2f01ff75782c1ed6b7745ef67d8e6ecabe35"
    private const val SHA256_AARCH64 =
        "4c13f00582ccd3d83f1c8c3d767567dc4a0728c6362bacc9fbee5b841a7ad4ec"
    private const val TOTAL_BYTES_X86_64 = 457723716L
    private const val TOTAL_BYTES_AARCH64 = 410664128L
    private const val PART_COUNT_X86_64 = 22
    private const val PART_COUNT_AARCH64 = 20
    private const val PART_SIZE = 20971520L

    private fun abi(): String {
        val a = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return if (a.contains("x86_64")) "x86_64" else "aarch64"
    }

    /** Part file names for this arch, in download order. */
    fun partNames(): List<String> {
        val a = abi()
        val count = if (a == "x86_64") PART_COUNT_X86_64 else PART_COUNT_AARCH64
        val base = "mudif-image-$a-$IMAGE_VERSION.tar.xz"
        return (0 until count).map { i -> "$base.p20%02d".format(i) }
    }

    fun partUrls(): List<String> = partNames().map { "$HF_BASE$it" }

    fun expectedSha256(): String =
        if (abi() == "x86_64") SHA256_X86_64 else SHA256_AARCH64

    fun expectedTotalBytes(): Long =
        if (abi() == "x86_64") TOTAL_BYTES_X86_64 else TOTAL_BYTES_AARCH64

    fun debianRootfsDir(context: Context): File =
        File(context.filesDir, "debian-rootfs").apply { mkdirs() }

    private fun tarball(context: Context): File =
        File(context.filesDir, "mudif-image.tar.xz")

    /** True only after a COMPLETE verified extraction (marker file). */
    fun isReady(context: Context): Boolean =
        File(debianRootfsDir(context), READY_MARKER).exists()

    /**
     * P17: download the pre-built image as 20 MB parts from Hugging
     * Face, in order, concatenated into one tarball. Each part is
     * verified against its expected size as it lands; the reassembled
     * file is verified against its total size + sha256 before any
     * extraction. Progress reports cumulative bytes against the full
     * image size, so the existing progress dialog works unchanged.
     * A failed part deletes the partial tarball so a retry starts
     * clean (download() already deletes a stale tarball at entry).
     */
    fun download(context: Context, onProgress: (done: Long, total: Long) -> Unit) {
        val out = tarball(context)
        if (out.exists()) out.delete()
        val total = expectedTotalBytes()
        val urls = partUrls()
        var done = 0L
        try {
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                for ((index, url) in urls.withIndex()) {
                    // Expected size of this part: every part is
                    // PART_SIZE except the last, which holds the
                    // remainder (total - (count - 1) * PART_SIZE).
                    val expectedPartSize =
                        if (index == urls.size - 1) total - (urls.size - 1) * PART_SIZE
                        else PART_SIZE
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                    }
                    conn.connect()
                    if (conn.responseCode != HttpURLConnection.HTTP_OK)
                        throw RuntimeException("التحميل فشل (الجزء ${index + 1}/${urls.size}): HTTP ${conn.responseCode}")
                    var partDone = 0L
                    conn.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            partDone += n
                            done += n
                            onProgress(done, total)
                        }
                    }
                    conn.disconnect()
                    if (partDone != expectedPartSize)
                        throw RuntimeException("الجزء ${index + 1}/${urls.size} ناقص: وصل $partDone من $expectedPartSize بايت — أعد المحاولة")
                }
            }
        } catch (e: Exception) {
            try { out.delete() } catch (ignored: Exception) { }
            throw e
        }
        if (out.length() != total)
            throw RuntimeException("التحميل ناقص: وصل ${out.length()} من $total بايت — أعد المحاولة")
        if (out.length() < 1024 * 1024)
            throw RuntimeException("ملف التحميل صغير بشكل مشبوه (${out.length()} بايت)")
        // P17: verify the reassembled image against its published
        // hash. A corrupted download must never extract into the
        // system.
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
                    // P17 images have ONE top-level dir
                    // (mudif-p17-<arch>/): strip it, like the old
                    // proot-distro tarballs.
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
                            // P17 images ship no device nodes
                            // (verified by verify-image.sh); skip any
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

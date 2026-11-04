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
 * P3 step 2: Alpine minirootfs bootstrap.
 * Downloads the Alpine minirootfs matching the device ABI (~4MB),
 * extracts it into our private files dir, then runs the first real
 * command inside it: proot -r rootfs /bin/cat /etc/alpine-release.
 */
object RootfsManager {

    private const val ALPINE_VERSION = "3.24.0"
    private const val BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"

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

    fun isReady(context: Context): Boolean =
        File(rootfsDir(context), "bin/echo").exists()

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
    }

    fun extract(context: Context) {
        val dest = rootfsDir(context)
        val destPath = dest.canonicalPath + File.separator
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(tarball(context).inputStream()))
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val f = File(dest, entry.name)
                if (!f.canonicalPath.startsWith(destPath))
                    throw RuntimeException("مسار مشبوه في الأرشيف: ${entry.name}")
                when {
                    entry.isDirectory -> f.mkdirs()
                    entry.isSymbolicLink -> {
                        f.parentFile?.mkdirs()
                        if (f.exists()) f.delete()
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
                entry = tar.nextEntry
            }
        }
        tarball(context).delete()
    }

    /**
     * First real boot: runs /bin/cat /etc/alpine-release inside the rootfs.
     * Returns "Alpine خدام ✓" + version on success, else the raw output.
     */
    fun firstBoot(context: Context): String {
        return try {
            val bin = EngineCheck.prootPath(context)
                ?: return "المحرك غير موجود: libtiny_proot.so"
            EngineCheck.ensureLibs(context)
            val rootfs = rootfsDir(context)
            if (!File(rootfs, "bin/cat").exists())
                return "النظام غير مكتمل: أعد التحميل"
            val pb = ProcessBuilder(
                bin.absolutePath,
                "-r", rootfs.absolutePath,
                "/bin/cat", "/etc/alpine-release"
            ).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = EngineCheck.binDir(context).absolutePath
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
                else -> "Alpine خدام ✓\nالإصدار: $out"
            }
        } catch (e: Exception) {
            "خطأ: ${e.message}"
        }
    }
}

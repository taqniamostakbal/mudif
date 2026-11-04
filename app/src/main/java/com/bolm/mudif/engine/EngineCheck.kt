package com.bolm.mudif.engine

import android.content.Context
import java.io.File

/**
 * P3: engine bootstrap proof.
 * The proot binary is smuggled as libtiny_proot.so in jniLibs (useLegacyPackaging
 * keeps it extractable). The installer extracts it to nativeLibraryDir with
 * exec permissions, so we run it straight from there (Tiny Container pattern).
 * Copying it to our private dir breaks exec on some devices (EACCES).
 */
object EngineCheck {

    fun prootPath(context: Context): File? {
        val src = File(context.applicationInfo.nativeLibraryDir, "libtiny_proot.so")
        if (!src.exists()) return null
        if (src.canExecute()) return src
        // Fallback: private copy with explicit 0755
        return try {
            val binDir = File(context.filesDir, "bin").apply { mkdirs() }
            val bin = File(binDir, "proot")
            src.copyTo(bin, overwrite = true)
            try {
                android.system.Os.chmod(bin.absolutePath, 493) // 0755
            } catch (e: Exception) {
                bin.setExecutable(true)
            }
            bin
        } catch (e: Exception) {
            null
        }
    }

    fun runProotVersion(context: Context): String {
        return try {
            val bin = prootPath(context)
                ?: return "غير موجود: libtiny_proot.so ماشي فالمكتبة الأصلية"
            val proc = ProcessBuilder(bin.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            if (out.isEmpty()) "خرج بالكود $code بلا مخرجات (${bin.absolutePath})"
            else "proot خدام ✓\n$out"
        } catch (e: Exception) {
            "خطأ: ${e.message}"
        }
    }
}

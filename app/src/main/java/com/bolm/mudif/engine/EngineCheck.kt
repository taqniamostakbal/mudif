package com.bolm.mudif.engine

import android.content.Context
import java.io.File

/**
 * P3: engine bootstrap proof.
 * The proot binary is smuggled as libtiny_proot.so in jniLibs (useLegacyPackaging
 * keeps it extractable). At runtime we copy it to our private files dir,
 * make it executable and run it.
 */
object EngineCheck {

    fun prootPath(context: Context): File? {
        val src = File(context.applicationInfo.nativeLibraryDir, "libtiny_proot.so")
        if (!src.exists()) return null
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val bin = File(binDir, "proot")
        if (!bin.exists() || bin.length() != src.length()) {
            src.copyTo(bin, overwrite = true)
            bin.setExecutable(true)
        }
        return bin
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
            if (out.isEmpty()) "خرج بالكود $code بلا مخرجات"
            else "proot خدام ✓\n$out"
        } catch (e: Exception) {
            "خطأ: ${e.message}"
        }
    }
}

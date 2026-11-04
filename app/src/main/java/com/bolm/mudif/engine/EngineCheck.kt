package com.bolm.mudif.engine

import android.content.Context
import java.io.File

/**
 * P3: engine bootstrap proof.
 * proot is smuggled as libtiny_proot.so in jniLibs and runs straight from
 * nativeLibraryDir (installer gives it exec perms). Its dependency
 * libtalloc.so.2 ships in assets and is extracted to our private bin dir;
 * LD_LIBRARY_PATH points the linker at it.
 */
object EngineCheck {

    private fun binDir(context: Context): File =
        File(context.filesDir, "bin").apply { mkdirs() }

    private fun ensureTalloc(context: Context): File {
        val dir = binDir(context)
        val lib = File(dir, "libtalloc.so.2")
        if (!lib.exists()) {
            context.assets.open("talloc/libtalloc.so.2").use { input ->
                lib.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return lib
    }

    fun prootPath(context: Context): File? {
        val src = File(context.applicationInfo.nativeLibraryDir, "libtiny_proot.so")
        if (!src.exists()) return null
        if (src.canExecute()) return src
        return try {
            val bin = File(binDir(context), "proot")
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
            ensureTalloc(context)
            val pb = ProcessBuilder(bin.absolutePath, "--version")
                .redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = binDir(context).absolutePath
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            when {
                out.contains("CANNOT LINK", ignoreCase = true) ->
                    "مكتبة ناقصة:\n$out"
                out.isEmpty() ->
                    "خرج بلا مخرجات (${bin.absolutePath})"
                else ->
                    "proot خدام ✓\n$out"
            }
        } catch (e: Exception) {
            "خطأ: ${e.message}"
        }
    }
}

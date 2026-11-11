package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R
import android.os.Build
import java.io.File

/**
 * P3: engine bootstrap proof.
 * proot is smuggled as libtiny_proot.so in jniLibs (one per ABI:
 * arm64-v8a for real phones, x86_64 for emulators like BlueStacks)
 * and runs straight from nativeLibraryDir (installer gives it exec perms).
 * Its dependencies (libtalloc.so.2, libandroid-shmem.so) ship in
 * assets/talloc/<abi>/ and are extracted to our private bin dir;
 * LD_LIBRARY_PATH points the linker at them.
 */
object EngineCheck {

    /** "arm64" for real phones, "x86_64" for emulators. */
    private fun abiDir(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return if (abi.contains("x86_64")) "x86_64" else "arm64"
    }

    fun binDir(context: Context): File =
        File(context.filesDir, "bin").apply { mkdirs() }

    private val NEEDED_LIBS = listOf("libtalloc.so.2", "libandroid-shmem.so")

    fun ensureLibs(context: Context) {
        val dir = binDir(context)
        val sub = abiDir()
        for (name in NEEDED_LIBS) {
            val lib = File(dir, name)
            if (!lib.exists()) {
                context.assets.open("talloc/$sub/$name").use { input ->
                    lib.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
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
                ?: return context.getString(R.string.ui_engine_missing_lib)
            ensureLibs(context)
            val pb = ProcessBuilder(bin.absolutePath, "--version")
                .redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = binDir(context).absolutePath
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            val lower = out.lowercase()
            val failed = lower.contains("cannot link") ||
                lower.contains("not found") ||
                lower.contains("syntax error") ||
                lower.contains("no such file") ||
                lower.contains("exec format")
            val looksLikeProot = lower.contains("proot")
            when {
                out.isEmpty() -> context.getString(R.string.ui_engine_no_output, bin.absolutePath)
                failed || !looksLikeProot ->
                    context.getString(R.string.ui_engine_failed, abiDir(), out)
                else -> context.getString(R.string.ui_engine_ok, out)
            }
        } catch (e: Exception) {
            context.getString(R.string.ui_error_prefix, e.message)
        }
    }
}

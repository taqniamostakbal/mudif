package com.bolm.mudif.ui.store

import android.content.Context
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.TerminalResult
import com.bolm.mudif.engine.TerminalSession
import java.io.File

/** Live install state of one program, shown on its card. */
enum class ProgramState {
    UNKNOWN, NOT_INSTALLED, INSTALLED, CHECKING, INSTALLING, UNINSTALLING
}

/**
 * All guest-side store work (P10): verify, install, uninstall.
 * Every call runs one-shot proot commands through TerminalSession;
 * callers must run these on a background thread, never the UI thread.
 * A result is only a success when the exit code is 0 AND a follow-up
 * verify agrees; the fragment enforces that, this object just runs.
 */
object ProgramStore {

    /** Staging dir inside the guest for downloaded .debs and assets. */
    private const val STORE_TMP = "/tmp/mudif-store"

    /** Guest CPU architecture from uname -m, e.g. aarch64 or x86_64. */
    fun guestArch(context: Context): String {
        val r = TerminalSession.runCommandDebian(context, "uname -m", 30, 60)
        val arch = r.output.trim()
        return if (r.exitCode == 0 && arch.isNotEmpty()) arch else ""
    }

    fun isArm64(arch: String): Boolean = arch == "aarch64" || arch == "arm64"

    /**
     * The .deb URL for this guest. On arm64 the arm64 build is used;
     * null means this program ships no arm64 build (Jan) and the
     * install must not be attempted on a phone.
     */
    fun debUrlFor(program: StoreProgram, arch: String): String? =
        if (isArm64(arch)) program.debArm64 else program.deb

    /** Shell test that is true when the program is installed. */
    fun verifySnippet(program: StoreProgram): String = when (program.method) {
        "apt" -> "dpkg -s ${program.apt} >/dev/null 2>&1"
        "deb" -> "dpkg -s ${program.debPackage} >/dev/null 2>&1"
        "script" -> "command -v ${program.binary} >/dev/null 2>&1"
        "font" -> if (program.font != null) {
            "[ -f /usr/share/fonts/truetype/lifta/${program.font} ]"
        } else {
            "dpkg -s ${program.apt} >/dev/null 2>&1"
        }
        else -> "false"
    }

    /**
     * Installed state for many programs in ONE guest call. Each line
     * of output is "<id>=1" or "<id>=0". Programs missing from the
     * result keep their previous state.
     */
    fun batchStatus(context: Context, programs: List<StoreProgram>): Map<String, Boolean> {
        if (programs.isEmpty()) return emptyMap()
        val script = StringBuilder()
        for (p in programs) {
            script.append("if ").append(verifySnippet(p))
                .append("; then echo ").append(p.id)
                .append("=1; else echo ").append(p.id).append("=0; fi\n")
        }
        val r = TerminalSession.runCommandDebian(context, script.toString(), 60, 300)
        if (r.exitCode != 0) return emptyMap()
        val out = HashMap<String, Boolean>()
        for (line in r.output.lines()) {
            val parts = line.trim().split("=")
            if (parts.size == 2) out[parts[0]] = parts[1] == "1"
        }
        return out
    }

    /** One program, checked for real, with a short evidence line. */
    fun verify(context: Context, program: StoreProgram): Pair<Boolean, String> {
        return when (program.method) {
            "apt", "deb" -> {
                val pkg = if (program.method == "apt") program.apt!! else program.debPackage!!
                val r = TerminalSession.runCommandDebian(
                    context,
                    "dpkg-query -W -f='\${Status} \${Version}' $pkg 2>&1",
                    30, 60
                )
                val installed = r.output.contains("install ok installed")
                installed to if (installed) r.output.trim() else lastLine(r.output)
            }
            "script" -> {
                val r = TerminalSession.runCommandDebian(
                    context, "command -v ${program.binary}", 30, 60
                )
                val installed = r.exitCode == 0 && r.output.isNotBlank()
                installed to if (installed) r.output.trim() else lastLine(r.output)
            }
            "font" -> {
                if (program.font != null) {
                    val path = "/usr/share/fonts/truetype/lifta/${program.font}"
                    val r = TerminalSession.runCommandDebian(
                        context, "ls -l $path 2>&1", 30, 60
                    )
                    (r.exitCode == 0) to if (r.exitCode == 0) r.output.trim() else lastLine(r.output)
                } else {
                    val r = TerminalSession.runCommandDebian(
                        context,
                        "dpkg-query -W -f='\${Status} \${Version}' ${program.apt} 2>&1",
                        30, 60
                    )
                    val installed = r.output.contains("install ok installed")
                    installed to if (installed) r.output.trim() else lastLine(r.output)
                }
            }
            else -> false to ""
        }
    }

    /** Installs the program in the guest. Caller must post-verify. */
    fun install(context: Context, program: StoreProgram, arch: String): TerminalResult {
        return when (program.method) {
            "apt" -> TerminalSession.runCommandDebian(
                context,
                "(dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get update && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y ${program.apt}) 2>&1",
                180, 1800
            )
            "deb" -> {
                val url = debUrlFor(program, arch)
                    ?: return TerminalResult("لا توجد نسخة arm64 لهذا البرنامج", -1, false)
                val file = url.substringAfterLast('/')
                val cmd = """
                    mkdir -p $STORE_TMP
                    curl -fsSL -o $STORE_TMP/$file "$url" &
                    dl_pid=$!
                    while kill -0 "${'$'}dl_pid" 2>/dev/null; do echo "جار التحميل..."; sleep 20; done
                    wait "${'$'}dl_pid" || { echo "فشل تحميل الحزمة"; exit 1; }
                    dpkg -i $STORE_TMP/$file || { dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -f -y; }
                    dpkg -s ${program.debPackage} >/dev/null 2>&1
                """.trimIndent()
                TerminalSession.runCommandDebian(context, "($cmd) 2>&1", 180, 3600)
            }
            "script" -> {
                if (!stageAssetToGuest(context, program.script!!, "$STORE_TMP/${program.script}")) {
                    return TerminalResult("تعذر تجهيز سكربت التثبيت", -1, false)
                }
                TerminalSession.runCommandDebian(
                    context, "(sh $STORE_TMP/${program.script}) 2>&1", 180, 1800
                )
            }
            "font" -> {
                if (program.font != null) {
                    if (!stageAssetToGuest(context, "store-fonts/${program.font}", "$STORE_TMP/${program.font}")) {
                        return TerminalResult("تعذر تجهيز ملف الخط", -1, false)
                    }
                    TerminalSession.runCommandDebian(
                        context,
                        "(mkdir -p /usr/share/fonts/truetype/lifta && " +
                            "cp $STORE_TMP/${program.font} /usr/share/fonts/truetype/lifta/ && " +
                            "fc-cache -f >/dev/null 2>&1; fc-list | grep -i lifta) 2>&1",
                        60, 300
                    )
                } else {
                    TerminalSession.runCommandDebian(
                        context,
                        "(dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get update && " +
                            "DEBIAN_FRONTEND=noninteractive apt-get install -y ${program.apt}) 2>&1",
                        180, 1800
                    )
                }
            }
            else -> TerminalResult("طريقة تثبيت غير معروفة", -1, false)
        }
    }

    /** Removes the program from the guest. Caller must post-verify. */
    fun uninstall(context: Context, program: StoreProgram): TerminalResult {
        return when (program.method) {
            "apt" -> TerminalSession.runCommandDebian(
                context,
                "(dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get remove -y ${program.apt}) 2>&1",
                120, 900
            )
            "deb" -> TerminalSession.runCommandDebian(
                context,
                "(dpkg --configure -a; dpkg -r ${program.debPackage} || " +
                    "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${program.debPackage}) 2>&1",
                120, 900
            )
            "script" -> TerminalSession.runCommandDebian(
                context, "(rm -f /usr/local/bin/${program.binary}) 2>&1", 60, 300
            )
            "font" -> {
                if (program.font != null) {
                    TerminalSession.runCommandDebian(
                        context,
                        "(rm -f /usr/share/fonts/truetype/lifta/${program.font} && " +
                            "fc-cache -f >/dev/null 2>&1; echo done) 2>&1",
                        60, 300
                    )
                } else {
                    TerminalSession.runCommandDebian(
                        context,
                        "(dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get remove -y ${program.apt}) 2>&1",
                        120, 900
                    )
                }
            }
            else -> TerminalResult("طريقة إلغاء تثبيت غير معروفة", -1, false)
        }
    }

    /**
     * Copies an app asset into the Debian rootfs so the guest can use
     * it (the proot guest sees its own rootfs as /). Returns false
     * when the asset is missing or the copy is empty.
     */
    private fun stageAssetToGuest(context: Context, assetPath: String, guestPath: String): Boolean {
        return try {
            // P13: the guest's /tmp is bind-mounted over the app's
            // files/tmp (RootfsManager), so the rootfs's own /tmp
            // is shadowed and the guest never sees files staged
            // there (proven by the P12 Ollama UI failure: the
            // script existed in the rootfs but the guest reported
            // "No such file"). A guest /tmp/... path is therefore
            // staged into the bind target (files/tmp), so the guest
            // sees it at the same /tmp/... path; any other path is
            // staged into the rootfs as before.
            val dest = if (guestPath.startsWith("/tmp/")) {
                File(File(context.filesDir, "tmp"), guestPath.removePrefix("/tmp/"))
            } else {
                File(DebianSystem.debianRootfsDir(context), guestPath.trimStart('/'))
            }
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Last non-blank line of guest output, for honest failure reports. */
    fun lastLine(output: String): String {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return "(لا مخرجات)"
        return trimmed.lines().lastOrNull { it.isNotBlank() }?.trim() ?: trimmed
    }

    /** Last N non-blank lines of guest output, for failure dialogs. */
    fun lastLines(output: String, n: Int = 15): String {
        val lines = output.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return "(لا مخرجات)"
        return lines.takeLast(n).joinToString("\n")
    }
}

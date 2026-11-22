package com.bolm.mudif.ui.store

import android.content.Context
import com.bolm.mudif.R
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.TerminalResult
import com.bolm.mudif.engine.TerminalSession
import com.bolm.mudif.engine.UsageLog
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

    /** Guest path of the p68 download library (staged by LxqtLook). */
    private const val MUDIF_DL = "/root/.config/mudif/mudif-dl.sh"

    /** Guest dir for translated Windows programs (method "win32"). */
    private const val WIN32_DIR = "/usr/local/win32"

    /** Guest path of a translated Windows program, e.g.
     *  /usr/local/win32/notepad.exe. Blank when the program has no
     *  win32 key (a malformed entry); callers treat blank as absent. */
    fun win32ProgramPath(program: StoreProgram): String {
        val key = program.win32 ?: return ""
        return "$WIN32_DIR/$key.exe"
    }

    /** Guest shell command that launches a translated Windows
     *  program directly: the AOT binary IS the program (translated
     *  once on the build machine), so there is no translator at
     *  runtime. The program opens its own window on the desktop.
     *  PROVEN on the VM (qemu-aarch64 on Xvfb:99: window, typing,
     *  File menu, Save, clean Exit 0). NOT PROVEN on a phone or on
     *  the rig: launch runs only on arm64, the rig guest is x86_64. */
    fun win32LaunchCommand(program: StoreProgram): String =
        "${win32ProgramPath(program)} >/dev/null 2>&1 &"

    /**
     * Real preflight for a Windows program: guest arch and AOT
     * binary presence. The AOT binary is the translated program
     * itself (no translator runs at launch); the tap on a win32
     * entry runs this one guest call and reports the findings
     * honestly before installing.
     */
    fun win32Preflight(context: Context, program: StoreProgram): Pair<String, Boolean> {
        val r = TerminalSession.runCommandDebian(
            context,
            "uname -m; [ -f ${win32ProgramPath(program)} ] && echo PROGRAM_YES || echo PROGRAM_NO",
            30, 60
        )
        val noOutput = context.getString(R.string.ui_no_output)
        val lines = r.output.lines().map { it.trim() }
        val arch = lines.firstOrNull()?.takeIf {
            it.isNotBlank() && it != noOutput && !it.startsWith("PROGRAM_")
        } ?: ""
        return Pair(arch, lines.contains("PROGRAM_YES"))
    }

    /** The honest message for a win32 tap: the fixed explanation
     *  plus the two preflight findings, one per line. */
    fun win32InfoMessage(context: Context, preflight: Pair<String, Boolean>): String {
        val (arch, program) = preflight
        return context.getString(R.string.win32_not_ready) + "\n\n" +
            context.getString(R.string.win32_check_arch, arch.ifBlank { "؟" }) + "\n" +
            context.getString(if (program) R.string.win32_check_program_yes else R.string.win32_check_program_no)
    }

    /**
     * p68: pause handle for a running store download. The dialog's
     * Pause button calls [pause]; it kills the guest command, the
     * partial file stays (aria2c control file beside it), and the
     * next Install resumes from the offset or from the cache.
     * A pause during the dpkg phase is possible too; the next
     * Install re-runs dpkg -i on the cached file, which repairs it.
     */
    class DownloadControl {
        @Volatile
        var command: TerminalSession.GuestCommand? = null
        @Volatile
        var paused = false
        fun pause() {
            paused = true
            command?.cancel()
        }
    }

    /** Guest CPU architecture from uname -m, e.g. aarch64 or x86_64.
     *  p70: cached once known. Every list open used to spawn a full
     *  proot process (dozens of binds + DNS/tmp setup) just to run
     *  uname -m, before the list could appear. The arch never
     *  changes on a device, so a non-empty result is cached for the
     *  life of the process; an empty result (guest not ready) is
     *  never cached, so a later install is still detected. */
    @Volatile
    private var cachedArch: String? = null

    fun guestArch(context: Context): String {
        cachedArch?.let { return it }
        val r = TerminalSession.runCommandDebian(context, "uname -m", 30, 60)
        val arch = r.output.trim()
        val result = if (r.exitCode == 0 && arch.isNotEmpty()) arch else ""
        if (result.isNotEmpty()) cachedArch = result
        return result
    }

    fun isArm64(arch: String): Boolean = arch == "aarch64" || arch == "arm64"

    /** Slice 4 (killer build): true when the guest has the
     *  user-mode AArch64 translator the rig needs to run a
     *  translated Windows binary on an x86_64 guest. On arm64
     *  the binary runs directly and this is never called.
     *  Same caching shape as guestArch: one one-shot, cached
     *  for the life of the process; a failed result (non-zero
     *  exit) is never cached. */
    @Volatile
    private var cachedQemu: Boolean? = null

    fun hasQemuAarch64(context: Context): Boolean {
        cachedQemu?.let { return it }
        val r = TerminalSession.runCommandDebian(
            context,
            "test -x /usr/bin/qemu-aarch64-static && echo QEMU_YES || echo QEMU_NO",
            30, 60
        )
        val result = r.exitCode == 0 && r.output.trim() == "QEMU_YES"
        if (r.exitCode == 0) cachedQemu = result
        return result
    }

    /**
     * The .deb URL for this guest. On arm64 the arm64 build is used;
     * null means this program ships no arm64 build (Jan) and the
     * install must not be attempted on a phone.
     */
    fun debUrlFor(program: StoreProgram, arch: String): String? =
        if (isArm64(arch)) program.debArm64 else program.deb

    /**
     * Installed state for many programs in ONE guest call.
     *
     * p70: the old implementation built ONE shell script with one
     * dpkg-query process PER program (1,139 checks = a 153,054-byte
     * command string) and passed it as a single argv element to
     * /bin/sh -c. Linux refuses any argv element over 131,072 bytes
     * (MAX_ARG_STRLEN, E2BIG; proven: 131,071 works, 131,072 fails),
     * so ProcessBuilder.start() threw on EVERY full-list check, the
     * runner returned exit -1, and this function returned an empty
     * map, which every caller read as "nothing installed". The store
     * (1,226 checks, 166,733 bytes) failed the same way; only small
     * category pages (at most 242 checks, about 33 KB) ever worked.
     * The new implementation asks the guest ONE question per data
     * source (the whole dpkg database in one dpkg-query, one
     * command -v loop for the 6 script binaries, one file test for
     * the font), so the script stays under 2 KB, runs in about a
     * second, and agrees with verify() exactly: a package counts as
     * installed only when its Status is "install ok installed".
     *
     * Returns null when the guest check itself fails (guest
     * unreachable, timeout). Callers must NOT read null as "nothing
     * installed": the store keeps UNKNOWN, the Apps tab shows a
     * check-failed state with a retry.
     */
    fun batchStatus(context: Context, programs: List<StoreProgram>): Map<String, Boolean>? {
        if (programs.isEmpty()) return emptyMap()
        val script = StringBuilder()
        script.append("dpkg-query -W -f='\${Package} \${Status}\\n' 2>/dev/null || echo 'DPKG_FAILED'\n")
        val scriptBins = programs.filter { it.method == "script" }
            .mapNotNull { it.binary }.distinct()
        for (bin in scriptBins) {
            val q = "'" + bin.replace("'", "'\\''") + "'"
            script.append("command -v ").append(q)
                .append(" >/dev/null 2>&1 && echo 'BIN:").append(bin).append("'\n")
        }
        val fontPrograms = programs.filter { it.method == "font" && it.font != null }
        for (p in fontPrograms) {
            val path = "/usr/share/fonts/truetype/lifta/${p.font}"
            val q = "'" + path.replace("'", "'\\''") + "'"
            script.append("[ -f ").append(q).append(" ] && echo 'FONT:")
                .append(p.id).append("'\n")
        }
        // Win32 programs: installed means the translated program
        // file is present in the guest (same one-test-per-program
        // shape as the font check above).
        val win32Programs = programs.filter { it.method == "win32" && it.win32 != null }
        for (p in win32Programs) {
            val path = win32ProgramPath(p)
            val q = "'" + path.replace("'", "'\\''") + "'"
            script.append("[ -f ").append(q).append(" ] && echo 'WIN32:")
                .append(p.id).append("'\n")
        }
        // The last line decides the shell exit code, and a missing
        // binary or font is a normal "not installed", not a failure:
        // without this, a missing lifta font made the whole script
        // exit 1 and batchStatus reported "check failed" even
        // though the dpkg data was fine. A real dpkg failure is
        // signalled by the DPKG_FAILED line above, not the code.
        script.append("true\n")
        val r = TerminalSession.runCommandDebian(context, script.toString(), 30, 120)
        if (r.exitCode != 0) return null
        val installedPkgs = HashSet<String>()
        val foundBins = HashSet<String>()
        val foundFonts = HashSet<String>()
        val foundWin32 = HashSet<String>()
        for (line in r.output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "DPKG_FAILED" -> return null
                trimmed.endsWith("install ok installed") ->
                    installedPkgs.add(trimmed.substringBefore(' '))
                trimmed.startsWith("BIN:") ->
                    foundBins.add(trimmed.removePrefix("BIN:").trim())
                trimmed.startsWith("FONT:") ->
                    foundFonts.add(trimmed.removePrefix("FONT:").trim())
                trimmed.startsWith("WIN32:") ->
                    foundWin32.add(trimmed.removePrefix("WIN32:").trim())
            }
        }
        val out = HashMap<String, Boolean>()
        for (p in programs) {
            out[p.id] = when (p.method) {
                "apt" -> p.apt != null && installedPkgs.contains(p.apt)
                "deb" -> p.debPackage != null && installedPkgs.contains(p.debPackage)
                "script" -> p.binary != null && foundBins.contains(p.binary)
                "font" -> if (p.font != null) foundFonts.contains(p.id)
                    else p.apt != null && installedPkgs.contains(p.apt)
                "win32" -> p.win32 != null && foundWin32.contains(p.id)
                else -> false
            }
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
                installed to if (installed) r.output.trim() else lastLine(context, r.output)
            }
            "script" -> {
                val r = TerminalSession.runCommandDebian(
                    context, "command -v ${program.binary}", 30, 60
                )
                val installed = r.exitCode == 0 && r.output.isNotBlank()
                installed to if (installed) r.output.trim() else lastLine(context, r.output)
            }
            "font" -> {
                if (program.font != null) {
                    val path = "/usr/share/fonts/truetype/lifta/${program.font}"
                    val r = TerminalSession.runCommandDebian(
                        context, "ls -l $path 2>&1", 30, 60
                    )
                    (r.exitCode == 0) to if (r.exitCode == 0) r.output.trim() else lastLine(context, r.output)
                } else {
                    val r = TerminalSession.runCommandDebian(
                        context,
                        "dpkg-query -W -f='\${Status} \${Version}' ${program.apt} 2>&1",
                        30, 60
                    )
                    val installed = r.output.contains("install ok installed")
                    installed to if (installed) r.output.trim() else lastLine(context, r.output)
                }
            }
            "win32" -> {
                // Installed means the AOT binary is present in the
                // guest; the store ships it as an asset, so this
                // reads NOT_INSTALLED until it is installed.
                val path = win32ProgramPath(program)
                if (path.isBlank()) {
                    false to ""
                } else {
                    val r = TerminalSession.runCommandDebian(
                        context, "[ -f $path ] && echo ${program.win32} || echo missing", 30, 60
                    )
                    val installed = r.output.lines().any { it.trim() == program.win32 }
                    installed to if (installed) r.output.trim() else lastLine(context, r.output)
                }
            }
            else -> false to ""
        }
    }

    /**
     * Runs a guest command, translating MUDIF_STAGE: / MUDIF_PROGRESS:
     * / MUDIF_SPEED: / MUDIF_CACHE_HIT marker lines into [onProgress]
     * / [onSpeed] calls and stripping the markers from the returned
     * output, so failure diagnostics show the real error instead of
     * progress noise. A null [onProgress] runs the command exactly
     * as before (no streaming, no stripping).
     * Stage text is Arabic (shown in the progress dialog as-is);
     * percent is 0..100 when the install reports a real one,
     * null when it does not (the dialog stays indeterminate).
     * [control] (p68) runs the command through a cancelable handle;
     * null keeps the old one-shot path.
     */
    private fun runWithProgress(
        context: Context,
        command: String,
        idleTimeoutSec: Long,
        maxTimeoutSec: Long,
        onProgress: ((stage: String, percent: Int?) -> Unit)?,
        onSpeed: ((mbps: Double, etaSec: Long?) -> Unit)? = null,
        control: DownloadControl? = null
    ): TerminalResult {
        if (onProgress == null) {
            return TerminalSession.runCommandDebian(context, command, idleTimeoutSec, maxTimeoutSec)
        }
        val onLine: (String) -> Unit = { line ->
            when {
                line.startsWith("MUDIF_STAGE:") -> {
                    onProgress(line.removePrefix("MUDIF_STAGE:"), null)
                }
                line.startsWith("MUDIF_PROGRESS:") -> {
                    val pct = line.removePrefix("MUDIF_PROGRESS:").trim().toIntOrNull()
                    if (pct != null) onProgress(context.getString(R.string.downloading), pct.coerceIn(0, 100))
                }
                line.startsWith("MUDIF_SPEED:") -> {
                    val parts = line.removePrefix("MUDIF_SPEED:").trim().split(":")
                    val mbps = parts.getOrNull(0)?.toDoubleOrNull()
                    val eta = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
                    if (mbps != null) onSpeed?.invoke(mbps, eta)
                }
                line.startsWith("MUDIF_CACHE_HIT") -> {
                    onProgress(context.getString(R.string.store_dl_from_cache), null)
                }
                else -> {
                    // apt's own download progress, when it prints one:
                    // a trailing [NN%]. Absent in piped output, so the
                    // dialog stays indeterminate on the stage instead.
                    val m = Regex("""\[(\d{1,3})%\]\s*$""").find(line)
                    val pct = m?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (pct != null) onProgress(context.getString(R.string.downloading), pct.coerceIn(0, 100))
                }
            }
        }
        val result = if (control != null) {
            val guest = TerminalSession.startCommandDebian(context, command, idleTimeoutSec, maxTimeoutSec, onLine)
                ?: return TerminalResult(context.getString(R.string.ui_term_not_ready_mudif), -1, false)
            control.command = guest
            guest.await()
        } else {
            TerminalSession.runCommandDebian(context, command, idleTimeoutSec, maxTimeoutSec, onLine)
        }
        val clean = result.output.lines()
            .filter { !it.startsWith("MUDIF_STAGE:") && !it.startsWith("MUDIF_PROGRESS:") && !it.startsWith("MUDIF_SPEED:") && !it.startsWith("MUDIF_CACHE_HIT") }
            .joinToString("\n").trim()
        return if (clean.isEmpty()) result else result.copy(output = clean)
    }

    /** Installs the program in the guest. Caller must post-verify.
     * [onProgress] (optional) reports the real stage and, when the
     * install can measure it, a real 0..100 percentage. Nothing is
     * faked: a stage without a measurable percentage reports null.
     * [onSpeed] (p68) reports the real download speed in MB/s and
     * the ETA in seconds (null when unknown). [control] (p68) is the
     * pause handle; null keeps the old non-cancelable path. */
    fun install(
        context: Context,
        program: StoreProgram,
        arch: String,
        onProgress: ((stage: String, percent: Int?) -> Unit)? = null,
        onSpeed: ((mbps: Double, etaSec: Long?) -> Unit)? = null,
        control: DownloadControl? = null
    ): TerminalResult {
        val result = when (program.method) {
            "apt" -> {
                // Same command as before (dpkg --configure -a, then
                // update && install), with stage markers at the real
                // phase boundaries. The && keeps "install only runs
                // if update succeeded". p68: mirror-pick runs first
                // (once per device, marker-gated, no-op after that)
                // and measures the Debian mirrors before apt fetches.
                val cmd = """
                    sh $MUDIF_DL mirror-pick
                    dpkg --configure -a
                    echo "MUDIF_STAGE:${context.getString(R.string.store_stage_update)}"
                    DEBIAN_FRONTEND=noninteractive apt-get update && echo "MUDIF_STAGE:${context.getString(R.string.store_stage_install)}" && DEBIAN_FRONTEND=noninteractive apt-get install -y ${program.apt}
                """.trimIndent()
                runWithProgress(context, "($cmd) 2>&1", 180, 1800, onProgress, onSpeed, control)
            }
            "deb" -> {
                val url = debUrlFor(program, arch)
                    ?: return TerminalResult(context.getString(R.string.ui_store_no_arm64_error), -1, false)
                val file = url.substringAfterLast('/')
                if (url.isBlank() || file.isBlank())
                    return TerminalResult(context.getString(R.string.ui_store_bad_url), -1, false)
                // Real download percentage: Content-Length from a HEAD
                // request, then the downloaded file size polled every
                // 2 s. No Content-Length (or a failed HEAD) falls back
                // to the old "جار التحميل..." heartbeat, indeterminate.
                // The three shell variables are assigned here from the
                // Kotlin values (single-quoted: URLs can contain & ? =).
                // The old code used $STORE_TMP / $url / $file without
                // ever assigning them, so EVERY deb install failed
                // with "mkdir: missing operand" + curl errors.
                // p68: the download itself goes through the library
                // (segmented aria2c + resume + on-device cache) instead
                // of a single curl stream. The loop also reports the
                // real speed (MB/s, exponential-ish average of the
                // last samples) and the ETA in seconds.
                val urlQ = "'" + url.replace("'", "'\\''") + "'"
                val fileQ = "'" + file.replace("'", "'\\''") + "'"
                val cmd = """
                    STORE_TMP="$STORE_TMP"
                    url=$urlQ
                    file=$fileQ
                    mkdir -p ${'$'}STORE_TMP
                    echo "MUDIF_STAGE:${context.getString(R.string.store_stage_download)}"
                    total=${'$'}(curl -fsSLI "${'$'}url" | grep -i '^content-length:' | tail -1 | awk '{print ${'$'}2}' | tr -d '\r')
                    sh $MUDIF_DL dl "${'$'}url" "${'$'}STORE_TMP/${'$'}file" &
                    dl_pid=${'$'}!
                    prev_got=0; prev_t=${'$'}(date +%s); ema=0
                    while kill -0 "${'$'}dl_pid" 2>/dev/null; do
                      got=${'$'}(stat -c%s ${'$'}STORE_TMP/${'$'}file 2>/dev/null || echo 0)
                      now=${'$'}(date +%s)
                      dt=${'$'}((now - prev_t))
                      if [ "${'$'}dt" -ge 1 ]; then
                        inst=${'$'}(((got - prev_got) / dt))
                        if [ "${'$'}ema" -eq 0 ]; then ema=${'$'}inst; else ema=${'$'}(((ema + inst) / 2)); fi
                        prev_got=${'$'}got; prev_t=${'$'}now
                        if [ -n "${'$'}total" ] && [ "${'$'}total" -gt 0 ] && [ "${'$'}ema" -gt 0 ]; then
                          mbps=${'$'}(awk -v b=${'$'}ema 'BEGIN { printf "%.1f", b/1048576 }')
                          eta=${'$'}(((total - got) / ema))
                          echo "MUDIF_SPEED:${'$'}mbps:${'$'}eta"
                        fi
                      fi
                      if [ -n "${'$'}total" ] && [ "${'$'}total" -gt 0 ]; then
                        echo "MUDIF_PROGRESS:${'$'}((got*100/total))"
                      else
                        echo "${context.getString(R.string.store_stage_download)}"
                      fi
                      sleep 2
                    done
                    wait "${'$'}dl_pid" || { echo "${context.getString(R.string.store_download_failed)}"; exit 1; }
                    echo "MUDIF_STAGE:${context.getString(R.string.store_stage_install)}"
                    dpkg -i ${'$'}STORE_TMP/${'$'}file || { dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -f -y; }
                    dpkg-query -W -f='${'$'}{Status}' ${program.debPackage} 2>/dev/null | grep -q "install ok installed"
                """.trimIndent()
                runWithProgress(context, "($cmd) 2>&1", 180, 3600, onProgress, onSpeed, control)
            }
            "script" -> {
                if (!stageAssetToGuest(context, program.script!!, "$STORE_TMP/${program.script}")) {
                    return TerminalResult(context.getString(R.string.ui_store_script_failed), -1, false)
                }
                runWithProgress(
                    context,
                    "(echo \"MUDIF_STAGE:${context.getString(R.string.store_stage_install)}\"; sh $STORE_TMP/${program.script}) 2>&1",
                    180, 1800, onProgress
                )
            }
            "font" -> {
                if (program.font != null) {
                    if (!stageAssetToGuest(context, "store-fonts/${program.font}", "$STORE_TMP/${program.font}")) {
                        return TerminalResult(context.getString(R.string.ui_store_font_failed), -1, false)
                    }
                    runWithProgress(
                        context,
                        "(echo \"MUDIF_STAGE:${context.getString(R.string.store_stage_install)}\"; mkdir -p /usr/share/fonts/truetype/lifta && " +
                            "cp $STORE_TMP/${program.font} /usr/share/fonts/truetype/lifta/ && " +
                            "fc-cache -f >/dev/null 2>&1; fc-list | grep -i lifta) 2>&1",
                        60, 300, onProgress
                    )
                } else {
                    val cmd = """
                        dpkg --configure -a
                        echo "MUDIF_STAGE:${context.getString(R.string.store_stage_update)}"
                        DEBIAN_FRONTEND=noninteractive apt-get update && echo "MUDIF_STAGE:${context.getString(R.string.store_stage_install)}" && DEBIAN_FRONTEND=noninteractive apt-get install -y ${program.apt}
                    """.trimIndent()
                    runWithProgress(context, "($cmd) 2>&1", 180, 1800, onProgress)
                }
            }
            "win32" -> {
                // The AOT binary ships as an app asset
                // (win32/<key>-aot). Stage it, then place it in the
                // guest at /usr/local/win32/<key>.exe with the exec
                // bit. Slice 1 (killer build): the install also
                // registers the program as a real application, so
                // the desktop menu lists it: the real icon staged
                // to /usr/share/pixmaps/<key>.png and a .desktop
                // file staged to /usr/share/applications/<key>.desktop.
                // No translator runs at install or at launch.
                val key = program.win32
                val path = win32ProgramPath(program)
                if (key.isNullOrBlank() || path.isBlank()) {
                    TerminalResult(context.getString(R.string.ui_store_unknown_method), -1, false)
                } else if (!stageAssetToGuest(context, "win32/$key-aot", "$STORE_TMP/$key-aot")) {
                    TerminalResult(context.getString(R.string.win32_install_stage_failed), -1, false)
                } else if (!stageAssetToGuest(context, "win32/$key.desktop", "$STORE_TMP/$key.desktop")) {
                    TerminalResult(context.getString(R.string.win32_install_stage_failed), -1, false)
                } else if (!stageAssetToGuest(context, "store-icons/${program.icon}", "$STORE_TMP/${program.icon}")) {
                    TerminalResult(context.getString(R.string.win32_install_stage_failed), -1, false)
                } else {
                    TerminalSession.runCommandDebian(
                        context,
                        "(mkdir -p $WIN32_DIR /usr/share/applications /usr/share/pixmaps && cp $STORE_TMP/$key-aot $path && chmod +x $path && cp $STORE_TMP/${program.icon} /usr/share/pixmaps/$key.png && cp $STORE_TMP/$key.desktop /usr/share/applications/$key.desktop && test -f $path && test -f /usr/share/applications/$key.desktop) 2>&1",
                        60, 300
                    )
                }
            }
            else -> TerminalResult(context.getString(R.string.ui_store_unknown_method), -1, false)
        }
        // Slice 1 (agent-as-OS): one log line per finished store
        // install, whichever path asked for it (agent bridge, Store
        // tab, category, games). ok is the install command's own
        // exit code; the bridge's separate dpkg verification still
        // decides what the agent reports as installed.
        UsageLog.append(context, "store_install", program.id, result.exitCode == 0)
        return result
    }

    /**
     * Runs a guest setup script shipped as an app asset (e.g. the
     * Arcade station setup, setup-arcade.sh). Stages it exactly like
     * an install script (stageAssetToGuest) and runs it with sh.
     * Returns the raw TerminalResult; the caller reports success or
     * failure honestly from exitCode + output. This stages and runs
     * the script only, it never edits the LXQt panel config (the
     * panel rewrites panel.conf on exit, so a guest-script edit
     * would be silently lost; see the Arcade wiring report).
     */
    fun runSetupScript(context: Context, assetName: String): TerminalResult {
        if (!stageAssetToGuest(context, assetName, "$STORE_TMP/$assetName")) {
            return TerminalResult(context.getString(R.string.ui_store_script_failed), -1, false)
        }
        return TerminalSession.runCommandDebian(
            context, "(sh $STORE_TMP/$assetName) 2>&1", 60, 600
        )
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
            "win32" -> {
                // Removes the translated program file, its menu
                // entry, and its icon when present; with nothing
                // installed this is a no-op that still exits 0,
                // and the post-verify agrees. A stale .desktop
                // file would leave a dead menu entry, so it is
                // removed here, not left behind.
                val path = win32ProgramPath(program)
                val key = program.win32
                if (path.isBlank() || key.isNullOrBlank()) {
                    TerminalResult(context.getString(R.string.ui_store_unknown_uninstall), -1, false)
                } else {
                    TerminalSession.runCommandDebian(
                        context, "(rm -f $path /usr/share/applications/$key.desktop /usr/share/pixmaps/$key.png) 2>&1", 60, 300
                    )
                }
            }
            else -> TerminalResult(context.getString(R.string.ui_store_unknown_uninstall), -1, false)
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

    /** Last non-blank line of guest output, for honest failure reports.
     *  The empty-output sentinel is the machine-compared "(لا مخرجات)"
     *  (AppsFragment compares against it); its resource value is kept
     *  byte-identical in every locale, so resolving it here is safe. */
    fun lastLine(context: Context, output: String): String {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return context.getString(R.string.ui_no_output)
        return trimmed.lines().lastOrNull { it.isNotBlank() }?.trim() ?: trimmed
    }

    /** Last N non-blank lines of guest output, for failure dialogs. */
    fun lastLines(context: Context, output: String, n: Int = 15): String {
        val lines = output.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return context.getString(R.string.ui_no_output)
        return lines.takeLast(n).joinToString("\n")
    }
}

package com.bolm.mudif.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import com.airbnb.lottie.LottieCompositionCache
import com.bolm.mudif.ui.store.StoreIconLoader
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * p74: the spontaneous app deaths, and the memory pressure behind
 * them.
 *
 * The pattern (p44-p53 rig rounds, 11 deaths): the process dies
 * with no Java FATAL, no native Fatal signal, no tombstone line,
 * and restarts cleanly with chat history intact. That is a SIGKILL
 * from outside the process, not an exception inside it. The deaths
 * cluster in rounds where the device is under memory pressure
 * (p53: turns 65-87 s where p52 answered the same turns in 2.7 s;
 * p47: a 1.4 GB model download in progress; p44: Ollama holding
 * a ~4 GB model resident on an 8 GB box, death while idle on the
 * agent tab), and the rig logcat is flooded by lowmemorykiller
 * spam. The hypothesis is H1: Android's lowmemorykiller kills the
 * app under device-wide memory pressure. The app had no response
 * to memory pressure at all: no Application class, no
 * onTrimMemory anywhere, no cache shedding, so when the system
 * warned, the app kept its full footprint and stayed a prime
 * kill candidate.
 *
 * This object does two things, and nothing else:
 *
 * 1. Shed on pressure. [onMemoryPressure] is called from
 *    MudifApplication.onTrimMemory / onLowMemory. It evicts the
 *    store icon bitmap cache (12 MB) and the Lottie composition
 *    cache, and records the trim level with a timestamp, so the
 *    next death record shows whether the system warned us first.
 *    The caches rebuild on demand (icons re-decode, Lottie
 *    re-parses its small JSONs), so shedding can never break a
 *    screen.
 *
 * 2. Prove the next death's cause. A heartbeat state file
 *    (filesDir/tmp/mudif-state.json) records the last known tab,
 *    action, foreground/background state, and memory numbers
 *    (device avail/total/threshold/lowMemory plus the app's own
 *    PSS) at most every 5 s, and on every turn send, stream
 *    update, and reply. On the next process start, [recordStart]
 *    reads the previous state: if it was never marked a clean
 *    exit, the previous process died, and the last state is
 *    appended to filesDir/tmp/mudif-deaths.jsonl. Both files live
 *    in filesDir/tmp, which the guest reads as /tmp (same
 *    bind-mount as the usage log), so the rig round reads them
 *    from the guest with no run-as. A death whose last heartbeat
 *    shows ample free memory with lowMemory=false falsifies H1
 *    and moves the suspect to a native crash or a system kill;
 *    a death whose last heartbeat shows availMem near the
 *    threshold with lowMemory=true confirms it.
 *
 * A death recorded with foreground=false is a routine background
 * reclaim (Android kills backgrounded apps for memory all the
 * time); only foreground=true deaths are the defect. The state
 * file is written atomically (temp file + rename) so a death
 * mid-write cannot corrupt it. All file IO runs on one daemon
 * thread and never throws: detection can never break the app.
 */
object AppDeathDetector {

    private const val TAG = "AppDeaths"
    private const val STATE_FILE = "mudif-state.json"
    private const val DEATHS_FILE = "mudif-deaths.jsonl"
    private const val MAX_DEATHS = 100
    private const val MIN_HEARTBEAT_MS = 5_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mudif-death-detector").apply { isDaemon = true }
    }
    private val lock = Any()

    @Volatile private var sessionId: String? = null
    @Volatile private var startedTs = 0L
    @Volatile private var lastHeartbeatMs = 0L
    @Volatile private var lastTab = "start"
    @Volatile private var lastAction = "start"
    @Volatile private var lastForeground = true
    @Volatile private var lastTrimLevel: String? = null
    @Volatile private var lastTrimTs = 0L

    /**
     * Call once per process start, from Application.onCreate,
     * before any activity exists. If the previous state file was
     * never marked a clean exit, the previous process died: its
     * last state is appended to the deaths log. A fresh state
     * file is then written for this session. Submissions run in
     * order on the single IO thread, so no heartbeat can
     * overwrite the previous state before it is read.
     */
    fun recordStart(context: Context) {
        val appCtx = context.applicationContext
        sessionId = UUID.randomUUID().toString()
        startedTs = System.currentTimeMillis()
        lastTab = "start"
        lastAction = "start"
        lastForeground = true
        lastTrimLevel = null
        lastTrimTs = 0L
        io.execute {
            try {
                val dir = stateDir(appCtx)
                val stateFile = File(dir, STATE_FILE)
                if (stateFile.exists()) {
                    val raw = try { stateFile.readText() } catch (_: Exception) { "" }
                    val prev = try { JSONObject(raw) } catch (_: Exception) { null }
                    val wasClean = prev?.optBoolean("clean", false) ?: false
                    val prevSession = prev?.optString("session", "")
                    if (!wasClean && prevSession != sessionId) {
                        appendDeathLocked(appCtx, prev)
                    }
                }
                writeStateLocked(appCtx, force = true)
            } catch (e: Exception) {
                Log.w(TAG, "recordStart failed (best-effort)", e)
            }
        }
    }

    /**
     * Record where the app is and how much memory the device has.
     * Throttled to one write per 5 s unless [force] is true (turn
     * send / reply always force, so a death mid-turn leaves a
     * snapshot no older than the turn itself). Cheap: one small
     * JSON write on a background thread.
     */
    fun heartbeat(
        context: Context,
        tab: String,
        action: String,
        force: Boolean = false,
        foreground: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHeartbeatMs < MIN_HEARTBEAT_MS) return
        lastHeartbeatMs = now
        lastTab = tab
        lastAction = action
        lastForeground = foreground
        val appCtx = context.applicationContext
        io.execute {
            try {
                writeStateLocked(appCtx, force = true)
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat failed (best-effort)", e)
            }
        }
    }

    /** Mark this session as exited cleanly (user BACK out of the
     *  last activity). A later start then does NOT count a death.
     *  Only call when the activity is really finishing: a process
     *  kill never runs onDestroy, so an unmarked state always
     *  means the process died. */
    fun markCleanExit(context: Context) {
        val appCtx = context.applicationContext
        io.execute {
            try {
                val dir = stateDir(appCtx)
                val stateFile = File(dir, STATE_FILE)
                if (!stateFile.exists()) return@execute
                val prev = try { JSONObject(stateFile.readText()) } catch (_: Exception) { return@execute }
                if (prev.optString("session", "") != sessionId) return@execute
                prev.put("clean", true)
                prev.put("ts", System.currentTimeMillis())
                writeAtomically(stateFile, prev.toString())
            } catch (e: Exception) {
                Log.w(TAG, "markCleanExit failed (best-effort)", e)
            }
        }
    }

    /**
     * The system says memory is tight. Shed every rebuildable
     * cache now (icon bitmaps re-decode, Lottie re-parses its
     * small JSONs), record the level and time in the state file,
     * and heartbeat so the record is fresh. Called on the main
     * thread from Application.onTrimMemory / onLowMemory; the
     * evictions are hash-map clears, a few milliseconds at most.
     */
    fun onMemoryPressure(context: Context, level: Int) {
        lastTrimLevel = levelName(level)
        lastTrimTs = System.currentTimeMillis()
        try {
            StoreIconLoader.evictAll()
        } catch (e: Exception) {
            Log.w(TAG, "icon cache evict failed", e)
        }
        try {
            LottieCompositionCache.getInstance().clear()
        } catch (e: Exception) {
            Log.w(TAG, "lottie cache clear failed", e)
        }
        heartbeat(context, tab = lastTab, action = "trim_${levelName(level)}", force = true, foreground = lastForeground)
    }

    private fun levelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "LEVEL_$level"
    }

    private fun stateDir(appCtx: Context): File =
        File(appCtx.filesDir, "tmp").apply { mkdirs() }

    private fun memorySnapshot(appCtx: Context): JSONObject {
        val out = JSONObject()
        try {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            out.put("availMemKb", info.availMem / 1024)
            out.put("totalMemKb", info.totalMem / 1024)
            out.put("thresholdKb", info.threshold / 1024)
            out.put("lowMemory", info.lowMemory)
        } catch (_: Exception) {
        }
        try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            out.put("appPssKb", memInfo.totalPss)
            out.put("appJavaPssKb", memInfo.dalvikPss)
            out.put("appNativePssKb", memInfo.nativePss)
        } catch (_: Exception) {
        }
        return out
    }

    private fun buildState(appCtx: Context): JSONObject {
        return JSONObject().apply {
            put("session", sessionId)
            put("pid", Process.myPid())
            put("startedTs", startedTs)
            put("ts", System.currentTimeMillis())
            put("tab", lastTab)
            put("action", lastAction)
            put("foreground", lastForeground)
            put("clean", false)
            put("memory", memorySnapshot(appCtx))
            put("lastTrimLevel", lastTrimLevel)
            put("lastTrimTs", lastTrimTs)
        }
    }

    private fun writeStateLocked(appCtx: Context, force: Boolean) {
        val dir = stateDir(appCtx)
        val stateFile = File(dir, STATE_FILE)
        writeAtomically(stateFile, buildState(appCtx).toString())
    }

    private fun appendDeathLocked(appCtx: Context, lastState: JSONObject?) {
        try {
            val dir = stateDir(appCtx)
            val file = File(dir, DEATHS_FILE)
            val line = JSONObject().apply {
                put("detectedTs", System.currentTimeMillis())
                put("lastState", lastState ?: JSONObject().put("raw", "unparseable"))
            }.toString()
            synchronized(lock) {
                file.appendText(line + "\n")
                val lines = try { file.readLines() } catch (_: Exception) { return }
                if (lines.size > MAX_DEATHS) {
                    file.writeText(lines.takeLast(MAX_DEATHS).joinToString("\n") + "\n")
                }
            }
            Log.i(TAG, "previous process died, last state recorded: ${lastState?.toString()}")
        } catch (e: Exception) {
            Log.w(TAG, "appendDeath failed (best-effort)", e)
        }
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
        }
    }
}

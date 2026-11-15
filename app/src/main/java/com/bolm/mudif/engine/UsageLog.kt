package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * Slice 1 of agent-as-OS (agent-as-os/DESIGN.md section 5): one
 * JSONL line per action the app already knows about, into
 * filesDir/tmp, which the guest reads as /tmp/usage-log.jsonl
 * (same bind-mount pattern as the bridge token file in
 * MudifBridge). The writer is app code only: no MCP tool can
 * write, change, or delete the log. Capped at 500 lines. Never
 * throws: logging can never break a turn, an install, or a launch.
 */
object UsageLog {

    private const val TAG = "UsageLog"
    private const val MAX_LINES = 500
    private const val FILE_NAME = "usage-log.jsonl"

    private val lock = Any()

    /** CLI verbs (arabic-cli.sh) that a chat turn can start with. */
    private val CLI_VERBS = setOf(
        "ثبت", "افتح", "ابحث", "حدّث-النظام", "نظف-الكاش",
        "اعرض", "اقرأ", "اقرأ-صفحة", "ابحث-عن-ملف",
        "ابحث-في-الملف", "احص", "اعرض-الشجرة", "نظف"
    )

    /** First word of a chat text when it is a known CLI verb, else "chat". */
    fun verbOrChat(text: String): String {
        val first = text.trim().substringBefore(' ').substringBefore('\t')
        return if (first in CLI_VERBS) first else "chat"
    }

    fun append(context: Context, kind: String, id: String, ok: Boolean) {
        try {
            val appCtx = context.applicationContext
            val dir = File(appCtx.filesDir, "tmp").apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            val line = JSONObject().apply {
                put("ts", System.currentTimeMillis() / 1000)
                put("kind", kind)
                put("id", id)
                put("ok", ok)
            }.toString()
            synchronized(lock) {
                file.appendText(line + "\n")
                trimLocked(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "append failed (best-effort)", e)
        }
    }

    private fun trimLocked(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                val kept = lines.takeLast(MAX_LINES)
                file.writeText(kept.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "trim failed (best-effort)", e)
        }
    }
}

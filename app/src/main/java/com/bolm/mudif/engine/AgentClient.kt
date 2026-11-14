package com.bolm.mudif.engine

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 5: the OpenCode server HTTP client (stdlib only:
 * HttpURLConnection + org.json, no new dependency).
 *
 * API shapes verified against the public OpenCode docs on 2026-11-11
 * (opencode.ai server docs + the @opencode-ai/sdk surface, cross-checked
 * in three independent write-ups). They MUST be re-verified against the
 * rig's live /doc (OpenAPI 3.1) before the chat is called working:
 * - POST /session {} -> {"id": ...}
 * - GET /event -> SSE, one JSON envelope per `data:` line:
 *   {"id","type","properties"}; the SSE `event:` line is always
 *   "message", so the code switches on the JSON `type`.
 * - POST /session/{id}/prompt_async {parts:[{type:"text",text}], system}
 *   -> 204, results arrive on the SSE stream.
 * - POST /session/{id}/message (same body) -> blocking turn; the
 *   response is the assistant message JSON. Used only when
 *   prompt_async is absent in the installed server version.
 * - POST /session/{id}/abort -> aborts the running turn.
 * - GET /session/{id}/message -> [{info, parts}] history, pollable.
 *
 * Streaming model (chosen to avoid double-counting text):
 * - The SSE stream is the CONTROL channel: session.idle (turn done),
 *   session.error (failure), message.part.updated with a tool part
 *   (show the tool name while it runs). Text comes from
 *   message.part.updated (full part text, SET semantics) AND from
 *   polling GET /session/{id}/message every 1.5 s, so the bubble
 *   converges even if an SSE text event is missed. `.delta` events
 *   are deliberately ignored: mixing set and delta updates would
 *   duplicate text.
 * - The turn ends on session.idle, on session.error, on a 300 s cap
 *   (the rig's slowest brain turn), or when the user taps Stop.
 *   onDone/onError fire exactly once.
 */
object AgentClient {
    private const val TAG = "AgentClient"
    private const val BASE_URL = AgentServer.AGENT_SERVER_URL

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val TURN_TIMEOUT_MS = 300_000L
    private const val POLL_INTERVAL_MS = 1_500L

    /** Callbacks run on background threads; the caller posts to UI. */
    interface TurnCallbacks {
        /** Full current reply text (SET, not append). */
        fun onText(fullText: String)
        /** A tool started running (name as the server reports it). */
        fun onTool(tool: String)
        /** Turn finished; finalText is the full reply (may be empty). */
        fun onDone(finalText: String)
        /** Turn failed; message is the real reason, never invented. */
        fun onError(message: String)
    }

    class TurnHandle internal constructor(
        private val sessionId: String
    ) {
        @Volatile var cancelled: Boolean = false
            private set
        @Volatile internal var sseConnection: HttpURLConnection? = null

        /** Stops the turn: closes the SSE stream and asks the server
         *  to abort. Best-effort; the server may already be done. */
        fun cancel() {
            cancelled = true
            try {
                sseConnection?.disconnect()
            } catch (_: Exception) {
            }
            Thread { AgentClient.abort(sessionId) }.start()
        }
    }

    /** Creates a session on the server. Null on any failure (the
     *  caller reports it honestly instead of inventing an id). */
    fun createSession(): String? {
        return try {
            val conn = openConnection("$BASE_URL/session", "POST")
            conn.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "createSession: HTTP $code")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            JSONObject(body).optString("id", "").ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "createSession failed", e)
            null
        }
    }

    /**
     * Sends one turn and streams the reply. Returns the handle at
     * once; the reply arrives through [callbacks]. The caller must
     * keep the handle to let the user stop the turn.
     */
    fun sendPrompt(
        sessionId: String,
        text: String,
        systemPrompt: String,
        callbacks: TurnCallbacks
    ): TurnHandle {
        val handle = TurnHandle(sessionId)
        val finished = AtomicBoolean(false)
        val lastText = java.util.concurrent.atomic.AtomicReference("")
        val turnDone = AtomicBoolean(false)

        fun done(finalText: String) {
            if (finished.compareAndSet(false, true)) {
                turnDone.set(true)
                try {
                    handle.sseConnection?.disconnect()
                } catch (_: Exception) {
                }
                callbacks.onDone(finalText)
            }
        }
        fun failed(message: String) {
            if (finished.compareAndSet(false, true)) {
                turnDone.set(true)
                try {
                    handle.sseConnection?.disconnect()
                } catch (_: Exception) {
                }
                callbacks.onError(message)
            }
        }

        // 1) SSE first: the stream is not replayable, so opening it
        // before the prompt is the only way not to miss early events.
        Thread {
            try {
                val conn = openConnection("$BASE_URL/event", "GET")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.readTimeout = 0
                handle.sseConnection = conn
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "SSE: HTTP $code")
                    conn.disconnect()
                    return@Thread
                }
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (!handle.cancelled && !turnDone.get()) {
                        line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val json = line.removePrefix("data:").trim()
                        if (json.isEmpty()) continue
                        try {
                            val event = JSONObject(json)
                            val type = event.optString("type", "")
                            val props = event.optJSONObject("properties") ?: JSONObject()
                            val eventSession = props.optString("sessionID", "")
                            if (eventSession.isNotEmpty() && eventSession != sessionId) continue
                            when (type) {
                                "message.part.updated" -> {
                                    val part = props.optJSONObject("part") ?: continue
                                    when (part.optString("type", "")) {
                                        "text" -> {
                                            val t = part.optString("text", "")
                                            if (t.isNotEmpty()) {
                                                lastText.set(t)
                                                callbacks.onText(t)
                                            }
                                        }
                                        "tool" -> {
                                            val tool = part.optString("tool", "")
                                            if (tool.isNotEmpty()) callbacks.onTool(tool)
                                        }
                                        else -> { /* reasoning / steps: not shown */ }
                                    }
                                }
                                "session.idle" -> done(lastText.get())
                                "session.error" -> {
                                    val err = props.opt("error")
                                    val msg = when (err) {
                                        is JSONObject -> err.optString("message", err.toString())
                                        is String -> err
                                        else -> "session error"
                                    }
                                    failed(msg)
                                }
                                else -> { /* heartbeat, connected, ... */ }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "SSE: unparsable event: ${json.take(200)}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!handle.cancelled && !turnDone.get()) {
                    Log.w(TAG, "SSE stream failed", e)
                }
            }
        }.apply { isDaemon = true }.start()

        // 2) Send the prompt, then poll the message history for the
        // live text until the SSE stream signals idle/error.
        Thread {
            try {
                val body = JSONObject().apply {
                    put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("type", "text").put("text", text)
                        )
                    )
                    put("system", systemPrompt)
                }.toString()
                val asyncCode = postPromptAsync(sessionId, body)
                if (asyncCode == 204) {
                    val deadline = System.currentTimeMillis() + TURN_TIMEOUT_MS
                    var lastPollText = ""
                    var unchangedPolls = 0
                    while (!handle.cancelled && !turnDone.get() &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(POLL_INTERVAL_MS)
                        if (turnDone.get() || handle.cancelled) break
                        val polled = pollAssistantText(sessionId)
                        if (polled != null && polled != lastPollText) {
                            lastPollText = polled
                            lastText.set(polled)
                            unchangedPolls = 0
                            callbacks.onText(polled)
                        } else {
                            unchangedPolls++
                        }
                        // The idle event may be missed if the SSE
                        // stream dropped; a finished message with no
                        // new text for 2 polls ends the turn too.
                        if (unchangedPolls >= 2 && pollMessageFinished(sessionId)) {
                            done(lastText.get())
                            return@Thread
                        }
                    }
                    if (handle.cancelled) {
                        done(lastText.get())
                    } else if (!turnDone.get()) {
                        failed("timeout: no reply within 300 s")
                    }
                    return@Thread
                }
                // prompt_async absent in this server version: fall
                // back to the blocking message endpoint.
                if (asyncCode == 404 || asyncCode == 405) {
                    val finalText = postMessageBlocking(sessionId, body)
                    if (handle.cancelled) done(lastText.get()) else done(finalText ?: lastText.get())
                    return@Thread
                }
                failed("server HTTP $asyncCode")
            } catch (e: Exception) {
                if (!handle.cancelled && !turnDone.get()) {
                    Log.w(TAG, "sendPrompt failed", e)
                    failed(e.message ?: "request failed")
                }
            }
        }.apply { isDaemon = true }.start()

        return handle
    }

    /** Asks the server to abort a turn. Best-effort, never throws. */
    fun abort(sessionId: String) {
        try {
            val conn = openConnection("$BASE_URL/session/$sessionId/abort", "POST")
            conn.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "abort failed (best-effort)", e)
        }
    }

    // ------------------------------------------------------------ HTTP

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = TURN_TIMEOUT_MS.toInt()
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        if (method == "POST") conn.doOutput = true
        return conn
    }

    /** POST prompt_async. Returns the HTTP code (204 = accepted). */
    private fun postPromptAsync(sessionId: String, body: String): Int {
        val conn = openConnection("$BASE_URL/session/$sessionId/prompt_async", "POST")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            // Drain the body so the connection can be reused/closed.
            try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            } catch (_: Exception) {
            }
            code
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking POST /session/{id}/message. Returns the reply text,
     *  or null when the response cannot be read as a reply. */
    private fun postMessageBlocking(sessionId: String, body: String): String? {
        val conn = openConnection("$BASE_URL/session/$sessionId/message", "POST")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "postMessageBlocking: HTTP $code")
                return null
            }
            val contentType = conn.getHeaderField("Content-Type") ?: ""
            val raw = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (contentType.contains("text/event-stream")) {
                // SSE body: take the last full text part seen.
                var last: String? = null
                for (line in raw.lines()) {
                    if (!line.startsWith("data:")) continue
                    try {
                        val event = JSONObject(line.removePrefix("data:").trim())
                        val props = event.optJSONObject("properties") ?: continue
                        val part = props.optJSONObject("part") ?: continue
                        if (part.optString("type") == "text") {
                            last = part.optString("text", "")
                        }
                    } catch (_: Exception) {
                    }
                }
                last
            } else {
                extractTextFromMessageJson(JSONObject(raw))
            }
        } catch (e: Exception) {
            Log.w(TAG, "postMessageBlocking failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Latest assistant text from GET /session/{id}/message, or null
     *  when the history cannot be read. */
    private fun pollAssistantText(sessionId: String): String? {
        return try {
            val conn = openConnection("$BASE_URL/session/$sessionId/message", "GET")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val raw = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val messages = JSONArray(raw)
            for (i in messages.length() - 1 downTo 0) {
                val item = messages.optJSONObject(i) ?: continue
                val info = item.optJSONObject("info") ?: continue
                if (info.optString("role", "") != "assistant") continue
                val text = extractTextFromMessageJson(item)
                if (text.isNotEmpty()) return text
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "pollAssistantText failed", e)
            null
        }
    }

    /** True when the latest assistant message reports a finish state
     *  (used as the fallback end-of-turn signal if the SSE stream
     *  dropped the idle event). */
    private fun pollMessageFinished(sessionId: String): Boolean {
        return try {
            val conn = openConnection("$BASE_URL/session/$sessionId/message", "GET")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return false
            }
            val raw = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val messages = JSONArray(raw)
            for (i in messages.length() - 1 downTo 0) {
                val item = messages.optJSONObject(i) ?: continue
                val info = item.optJSONObject("info") ?: continue
                if (info.optString("role", "") != "assistant") continue
                return info.optString("finish", "").isNotEmpty()
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Concatenates the text parts of one message JSON object
     *  ({info, parts} or a bare message with "parts"). */
    private fun extractTextFromMessageJson(obj: JSONObject): String {
        val parts = obj.optJSONArray("parts") ?: return ""
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optString("type", "") == "text") {
                out.append(part.optString("text", ""))
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------ prompts

    /** Per-message system prompt: identity + reply language + the
     *  safety rules (the user's standing 4-click rule). Short on
     *  purpose: the small local brain fails in long contexts (rig
     *  rounds 1-2). `replyLanguage` is "fusha" / "darija" / "en". */
    fun systemPrompt(replyLanguage: String): String {
        val safetyFusha =
            "لا تحذف أي شيء، ولا ترسل أي رسالة، ولا تدفع أي مبلغ، ولا تؤكد أي هوية نيابة عن المستخدم إلا بعد موافقته الصريحة."
        val safetyDarija =
            "ما تحذف والو، ما تسيفط حتى رسالة، ما تخلص والو، وما تأكد حتى هوية فبلاصة المستخدم حتى يوافق هو صراحة."
        val safetyEn =
            "Never delete anything, send any message, pay anything, or confirm any identity on the user's behalf without his explicit approval."
        return when (replyLanguage) {
            "darija" -> "أنت مساعد مضيف، المساعد العربي داخل نظام مضيف (ديبيان + LXQt على الهاتف). " +
                "جاوب بالدارجة المغربية وباختصار. نفّذ المهام بالأدوات المتاحة، وقل الحقيقة فقط: " +
                "إلا ما عرفتيش شي حاجة قول ما عرفتش، وما تخترعش نتيجة. " +
                "عطي النتيجة هي اللولة، وما تطلبش توضيح إلا إلا كان السؤال ناقص بالصح. " +
                "للحساب، بدا الجواب ديالك بالنتيجة الرقمية بوحدها فسطر مستقل وما تكتب حتى حاجة قبلها. مثال: «15% من 200» الجواب ديالك يبدا بـ «30». " +
                "وما تعتبر حتى سؤال عنوان ويب، وما تحاولش تفتح حتى رابط إلا إلا المستخدم لصق رابط. $safetyDarija"
            "en" -> "You are Mudif Assistant, the Arabic assistant inside the Mudif system " +
                "(Debian + LXQt on the phone). Answer in English, briefly. Carry out tasks with " +
                "the tools available, and tell only the truth: if you do not know something, say " +
                "so, never invent a result. Give the result first; only ask for clarification " +
                "if the question is genuinely incomplete. " +
                "For a calculation, start your reply with the numeric result alone on its own line and write nothing before it. " +
                "Example: '15% of 200' — your reply starts with '30'. " +
                "Never treat a question as a web address and never try to fetch " +
                "a URL unless the user pasted a link. $safetyEn"
            else -> "أنت مساعد مضيف، المساعد العربي داخل نظام مضيف (ديبيان + LXQt على الهاتف). " +
                "أجب بالعربية الفصحى وباختصار. نفّذ المهام بالأدوات المتاحة، وقل الحقيقة فقط: " +
                "إن لم تعرف شيئا قل لا أعرف، ولا تخترع نتيجة. " +
                "أعطِ النتيجة أولا، ولا تطلب توضيحا إلا إذا كان السؤال ناقصا فعلا. " +
                "للحساب، ابدأ ردك بالنتيجة الرقمية وحدها في سطر مستقل ولا تكتب أي شيء قبلها. " +
                "مثال: «15% من 200» يبدأ ردك بـ «30». " +
                "ولا تعتبر أي سؤال عنوان ويب، ولا تحاول فتح أي رابط إلا إذا ألصق المستخدم رابطا. $safetyFusha"
        }
    }
}

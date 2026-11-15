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
    private const val TAG = "MudifAgent"
    private const val BASE_URL = AgentServer.AGENT_SERVER_URL

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val TURN_TIMEOUT_MS = 300_000L
    private const val POLL_INTERVAL_MS = 1_500L
    // Brain fix: one poll read may wait at most 30 s, so the 300 s
    // turn deadline is checked after every poll, never much later.
    private const val POLL_READ_TIMEOUT_MS = 30_000

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
        private var sessionId: String,
        // Phase 3: stops the local model when the turn ends by
        // timeout, error, or Stop. Null for cloud turns (there is
        // no local model to stop).
        private val localStop: (() -> String)? = null
    ) {
        @Volatile var cancelled: Boolean = false
            private set
        @Volatile internal var sseConnection: HttpURLConnection? = null

        /** Phase 4: the turn re-created its session (stale id), so
         *  Stop/abort must target the live id, not the stale one. */
        internal fun retargetSession(id: String) {
            sessionId = id
        }

        /** Stops the turn: closes the SSE stream and asks the server
         *  to abort. Best-effort; the server may already be done. */
        @Volatile var stoppedByUser: Boolean = false
            private set

        /** User Stop: the turn ends now and its watchdog stays
         *  silent. Only stopCurrentTurn calls this. */
        fun cancel() {
            stoppedByUser = true
            tearDown()
        }

        /** View destroyed (rotation, tab switch) while the turn
         *  never ended: same teardown as cancel(), but the turn
         *  was NOT stopped by the user, so its own watchdog
         *  still fires failed() at its own deadline instead of
         *  vanishing with the view. Only onDestroyView calls
         *  this; see the rearm fragment spec for the call site. */
        fun supersede() {
            tearDown()
        }

        private fun tearDown() {
            cancelled = true
            try {
                sseConnection?.disconnect()
            } catch (_: Exception) {
            }
            Thread { AgentClient.abort(sessionId) }.start()
            // Phase 3: the OpenCode abort does not stop a local
            // model; a cancelled local turn keeps generating until
            // Ollama is told to stop it.
            localStop?.let { stop ->
                Thread {
                    val outcome = try { stop() } catch (e: Exception) { "error: ${e.message}" }
                    Log.i(TAG, "local model stop: $outcome")
                }.start()
            }
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
            val id = JSONObject(body).optString("id", "").ifEmpty { null }
            Log.i(TAG, "createSession: HTTP $code id=${id ?: "null"}")
            id
        } catch (e: Exception) {
            Log.w(TAG, "createSession failed", e)
            null
        }
    }

    /**
     * Phase 4: true when GET /session/{id}/message answers 2xx, so
     * the server still knows this session. A live session answers
     * 2xx (empty list when fresh); an unknown id answers 404.
     * False on any failure: the caller re-creates instead of
     * trusting a possibly dead id. Uses the existing history
     * endpoint, no new protocol.
     */
    private fun sessionKnown(sessionId: String): Boolean {
        return try {
            val conn = openConnection("$BASE_URL/session/$sessionId/message", "GET", 5_000)
            val code = conn.responseCode
            conn.disconnect()
            val known = code in 200..299
            Log.i(TAG, "sessionKnown: HTTP $code known=$known")
            known
        } catch (e: Exception) {
            Log.i(TAG, "sessionKnown failed: ${e.message}")
            false
        }
    }

    /**
     * Sends one turn and streams the reply. Returns the handle at
     * once; the reply arrives through [callbacks]. The caller must
     * keep the handle to let the user stop the turn.
     *
     * [screenContext]: vision increment 1. When non-null, the turn
     * carries a SECOND text part with what the agent saw on the
     * screen, worded in the turn's reply language (see
     * [visionPrefix]), as observed context, never as an instruction
     * and never as a system-prompt edit. Null (the default) sends
     * exactly the one text part as before.
     *
     * [testSilentTurn]: test-only hook (item 4). When true, the
     * turn parks after the watchdog is armed and performs zero
     * network I/O, so the watchdog is the only thing that can
     * end it (or a user Stop). Default false; no UI or string
     * reaches it.
     *
     * [replyLanguage]: "fusha", "darija" or "en" (same values as
     * [systemPrompt]). Used only to word the vision context part
     * in the turn's language; the system prompts themselves are
     * untouched. Default "fusha", so older call sites are unchanged.
     */
    fun sendPrompt(
        sessionId: String,
        text: String,
        systemPrompt: String,
        callbacks: TurnCallbacks,
        screenContext: String? = null,
        // Phase 3: stops the local model on timeout, error, or Stop.
        // The caller supplies it only for local-brain turns; cloud
        // turns pass null (no local model to stop).
        localStop: (() -> String)? = null,
        // Consolidated fix, item 4: test-only silent-turn hook.
        // Default false, so the single call site is unchanged.
        testSilentTurn: Boolean = false,
        replyLanguage: String = "fusha"
    ): TurnHandle {
        val handle = TurnHandle(sessionId, localStop)
        val finished = AtomicBoolean(false)
        val lastText = java.util.concurrent.atomic.AtomicReference("")
        val turnDone = AtomicBoolean(false)
        val assistantMessageId = java.util.concurrent.atomic.AtomicReference<String?>(null)
        // Phase 4, fix 2: the deadline is armed HERE, when the turn
        // starts, not after the prompt is posted. The watchdog below
        // fires it even when the prompt never reaches a server.
        val turnDeadline = System.currentTimeMillis() + TURN_TIMEOUT_MS
        // Phase 4, fix 1: the id this turn actually uses. Re-created
        // below when blank or unknown to the server.
        var activeSessionId = sessionId
        Log.i(TAG, "sendPrompt entry local=${localStop != null} session=${if (sessionId.isBlank()) "blank" else "supplied"}")

        fun failed(message: String) {
            if (finished.compareAndSet(false, true)) {
                turnDone.set(true)
                Log.i(TAG, "failed: $message")
                try {
                    handle.sseConnection?.disconnect()
                } catch (_: Exception) {
                }
                // Brain fix: abort the turn server-side, or a timed-out
                // turn keeps computing and slows the retried turn.
                // Phase 4: abort the live id, not a stale one.
                Thread { abort(activeSessionId) }.start()
                // Phase 3: the OpenCode abort does not stop the
                // local model; a timed-out local turn keeps
                // generating until Ollama is told to stop it.
                localStop?.let { stop ->
                    Thread {
                        val outcome = try { stop() } catch (e: Exception) { "error: ${e.message}" }
                        Log.i(TAG, "local model stop: $outcome")
                    }.start()
                }
                callbacks.onError(message)
            }
        }
        fun done(finalText: String) {
            // Consolidated fix, item 7b: the final model text is
            // sanitized here, at the source, before it is stored
            // or spoken. Verdict strings never pass through done().
            val cleanText = sanitizeModelText(finalText)
            // p34g fix 2: a turn with zero agent tokens ends only
            // through failed(), never as an empty success. The
            // phase-2 Nemotron empty finish ended here as done(""),
            // and the view-gone onDone path drops that silently
            // with no stored verdict and no Retry.
            if (cleanText.isEmpty()) {
                failed(if (handle.cancelled) "stopped before any reply" else "empty reply: server finished with no text")
                return
            }
            if (finished.compareAndSet(false, true)) {
                turnDone.set(true)
                Log.i(TAG, "done: final text len=${cleanText.length}")
                try {
                    handle.sseConnection?.disconnect()
                } catch (_: Exception) {
                }
                callbacks.onDone(cleanText)
            }
        }

        // Phase 4, fix 2: watchdog. Sleeps exactly TURN_TIMEOUT_MS
        // from the send, then fails the turn unless it already
        // ended. failed() is idempotent, so a late wake-up after a
        // normal end is a no-op. Covers createSession, the
        // session check, and a postPromptAsync that never returns.
        Log.i(TAG, "timeout armed 300 s")
        Thread {
            val interrupted = try {
                Thread.sleep(TURN_TIMEOUT_MS)
                false
            } catch (_: InterruptedException) {
                true
            }
            // Consolidated fix, item 4b: the watchdog answers only
            // to its own turn. A supersede (view destroyed) tears
            // the network down but must not silence this timer;
            // only a user Stop does that, via stoppedByUser.
            if (!handle.stoppedByUser && !turnDone.get()) {
                Log.i(TAG, "timeout firing interrupted=$interrupted superseded=${handle.cancelled}")
                failed(if (interrupted) "watchdog interrupted before reply" else "timeout: no reply within 300 s")
            }
        }.apply { isDaemon = true }.start()

        // Consolidated fix, item 4: test-only silent-turn hook.
        // Placement is the whole point: after the watchdog is
        // armed above, before any session check below. The turn
        // parks here with zero network I/O (no session check,
        // no session creation, no SSE subscription, no post),
        // so the watchdog is the only thing that can end it,
        // or a user Stop through the returned handle.
        if (testSilentTurn) {
            Log.i(TAG, "test hook: silent turn armed, parking until watchdog")
            Thread {
                while (!turnDone.get() && !handle.cancelled) {
                    try {
                        Thread.sleep(1_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }.apply { isDaemon = true }.start()
            return handle
        }

        // Phase 4, fix 1: a live session must exist before the
        // prompt is posted. Blank id, or an id the server no longer
        // knows (restart, stale prefs), gets a fresh session here.
        // A failed re-create fails the turn honestly instead of
        // sending into a dead session.
        if (activeSessionId.isBlank() || !sessionKnown(activeSessionId)) {
            val fresh = createSession()
            if (fresh.isNullOrBlank()) {
                failed("could not create session")
                return handle
            }
            activeSessionId = fresh
            handle.retargetSession(fresh)
            Log.i(TAG, "session re-created id=$fresh")
        }
        if (turnDone.get()) return handle

        // Brain fix phase 3: the poll path replayed the previous
        // turn's reply because it returned the last assistant message
        // with no turn baseline. Snapshot the last assistant message
        // id BEFORE the prompt is posted; the poll and the SSE gate
        // only accept a DIFFERENT id. Null means no baseline (fresh
        // session or unreadable history): the old behavior.
        // Phase 4: snapshotted on the live id, after re-creation.
        var pollBaselineId = lastAssistantMessageId(activeSessionId)
        if (turnDone.get()) return handle

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
                            if (eventSession.isNotEmpty() && eventSession != activeSessionId) continue
                            when (type) {
                                "message.updated" -> {
                                    val info = props.optJSONObject("info") ?: continue
                                    if (info.optString("role", "") == "assistant") {
                                        val id = info.optString("id", "").ifEmpty { null }
                                        // Phase 3: a replayed update for
                                        // the PREVIOUS turn's message
                                        // must not become the new id.
                                        if (id != null && id != pollBaselineId) {
                                            assistantMessageId.set(id)
                                        }
                                    }
                                }
                                "message.part.updated" -> {
                                    val part = props.optJSONObject("part") ?: continue
                                    // Brain fix: part events are session-wide; the
                                    // user message's own parts arrive here too and
                                    // would be shown as the agent's reply (echo).
                                    // Only parts of the known assistant message
                                    // may become bubble text (the poll path is
                                    // the fallback if the id is never learned).
                                    val partMessageId = part.optString("messageID", "")
                                    if (assistantMessageId.get() == null) continue
                                    if (partMessageId.isNotEmpty() && partMessageId != assistantMessageId.get()) continue
                                    when (part.optString("type", "")) {
                                        "text" -> {
                                            val t = part.optString("text", "")
                                            if (t.isNotEmpty()) {
                                                lastText.set(t)
                                                callbacks.onText(sanitizeModelText(t))
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
                val parts = JSONArray().put(
                    JSONObject().put("type", "text").put("text", text)
                )
                if (screenContext != null) {
                    parts.put(
                        JSONObject().put("type", "text")
                            .put("text", visionPrefix(replyLanguage, screenContext))
                    )
                }
                val body = JSONObject().apply {
                    put("parts", parts)
                    put("system", systemPrompt)
                }.toString()
                var asyncCode = postPromptAsync(activeSessionId, body)
                Log.i(TAG, "prompt_async code=$asyncCode")
                // Phase 4, fix 1: 404 means the server does not know
                // this session (it died between the check and the
                // post). One fresh session and one re-post; the code
                // below then decides on the retried code.
                if (asyncCode == 404) {
                    val fresh = createSession()
                    if (fresh.isNullOrBlank()) {
                        // p34g fix 1: the session is dead and no
                        // fresh one exists. Fail now instead of
                        // trying the blocking endpoint on a session
                        // the server already rejected.
                        Log.i(TAG, "prompt_async 404 re-create failed")
                        failed("could not create session")
                        return@Thread
                    }
                    activeSessionId = fresh
                    handle.retargetSession(fresh)
                    pollBaselineId = lastAssistantMessageId(activeSessionId)
                    asyncCode = postPromptAsync(activeSessionId, body)
                    Log.i(TAG, "prompt_async retry code=$asyncCode")
                }
                // The watchdog may have fired while posting.
                if (turnDone.get()) return@Thread
                if (asyncCode == 204) {
                    // Phase 4, fix 2: the deadline is the one armed
                    // at send start, not a fresh 300 s from here.
                    val deadline = turnDeadline
                    var lastPollText = ""
                    var unchangedPolls = 0
                    while (!handle.cancelled && !turnDone.get() &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(POLL_INTERVAL_MS)
                        if (turnDone.get() || handle.cancelled) break
                        val polled = pollAssistantText(activeSessionId, pollBaselineId)
                        if (polled != null && polled != lastPollText) {
                            lastPollText = polled
                            lastText.set(polled)
                            unchangedPolls = 0
                            callbacks.onText(sanitizeModelText(polled))
                        } else {
                            unchangedPolls++
                        }
                        // Brain fix: the deadline is checked right after
                        // each poll returns, so a slow poll cannot push
                        // the timeout past one 30 s read.
                        if (!turnDone.get() && System.currentTimeMillis() >= deadline) {
                            failed("timeout: no reply within 300 s")
                            return@Thread
                        }
                        // The idle event may be missed if the SSE
                        // stream dropped; a finished message with no
                        // new text for 2 polls ends the turn too.
                        if (unchangedPolls >= 2 && pollMessageFinished(activeSessionId, pollBaselineId)) {
                            Log.i(TAG, "poll finished end len=${lastText.get().length}")
                            done(lastText.get())
                            return@Thread
                        }
                    }
                    // Consolidated fix, item 4b: only a user Stop
                    // turns a cancel into the prompt stopped event.
                    // A superseded turn exits silently here, so its
                    // own watchdog stays the sole terminator.
                    if (handle.stoppedByUser) {
                        done(lastText.get())
                    } else if (!handle.cancelled && !turnDone.get()) {
                        failed("timeout: no reply within 300 s")
                    }
                    return@Thread
                }
                // prompt_async absent in this server version: fall
                // back to the blocking message endpoint.
                if (asyncCode == 404 || asyncCode == 405) {
                    if (turnDone.get()) return@Thread
                    val finalText = postMessageBlocking(activeSessionId, body)
                    Log.i(TAG, "blocking reply len=${finalText?.length ?: -1}")
                    if (turnDone.get()) return@Thread
                    // Consolidated fix, item 4b: same rule as the
                    // poll tail above. A superseded turn exits
                    // silently; only a user Stop ends it here.
                    if (handle.stoppedByUser) done(lastText.get()) else if (handle.cancelled) {
                        return@Thread
                    } else {
                        val replyText = finalText ?: lastText.get()
                        // Phase 4: a null blocking reply used to end
                        // the turn as an empty success. Fail honestly
                        // instead, with the code that sent us here.
                        if (replyText.isEmpty()) failed("server HTTP $asyncCode") else done(replyText)
                    }
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

    private fun openConnection(url: String, method: String, readTimeoutMs: Int = TURN_TIMEOUT_MS.toInt()): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        if (method == "POST") conn.doOutput = true
        return conn
    }

    /** POST prompt_async. Returns the HTTP code (204 = accepted). */
    private fun postPromptAsync(sessionId: String, body: String): Int {
        // Phase 3, prompt-delivery check (code side only): log the
        // system prompt's length + first 80 chars so the rig can
        // prove whether the prompt v2 rule actually reaches the
        // server. NEVER user text: only the "system" field is read,
        // never "parts". The rule is NOT moved without rig proof.
        try {
            val system = JSONObject(body).optString("system", "")
            if (system.isNotEmpty()) {
                Log.i(TAG, "prompt_async system: len=${system.length} head=${system.take(80)}")
            }
        } catch (_: Exception) {
        }
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
        val conn = openConnection("$BASE_URL/session/$sessionId/message", "POST", POLL_READ_TIMEOUT_MS)
        return try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "postMessageBlocking: HTTP $code")
                return null
            }
            val contentType = conn.getHeaderField("Content-Type") ?: ""
            // Brain fix: the blocking fallback used to read the whole
            // body with readText(), and a trickling SSE stream resets
            // the per-read timeout on every heartbeat, so it could
            // block forever. Read line by line with a 300 s total
            // deadline and keep what arrived.
            val startedAt = System.currentTimeMillis()
            val rawBuilder = StringBuilder()
            try {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    var line: String?
                    while (System.currentTimeMillis() - startedAt < TURN_TIMEOUT_MS) {
                        line = try {
                            reader.readLine()
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        } ?: break
                        rawBuilder.append(line).append('\n')
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "postMessageBlocking read failed", e)
            }
            val raw = rawBuilder.toString()
            if (raw.isEmpty()) return null
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

    /** Newest assistant message in GET /session/{id}/message, or
     *  null when the history cannot be read or has none. One read,
     *  shared by the baseline snapshot and both poll functions. */
    private fun latestAssistantMessage(
        sessionId: String,
        readTimeoutMs: Int = POLL_READ_TIMEOUT_MS
    ): JSONObject? {
        return try {
            val conn = openConnection("$BASE_URL/session/$sessionId/message", "GET", readTimeoutMs)
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
                return item
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "latestAssistantMessage failed", e)
            null
        }
    }

    /** Id of the newest assistant message, or null. Short read
     *  timeout: the turn must not wait on it. */
    private fun lastAssistantMessageId(sessionId: String): String? {
        val msg = latestAssistantMessage(sessionId, 5_000) ?: return null
        return msg.optJSONObject("info")?.optString("id", "")?.ifEmpty { null }
    }

    /** Text of the CURRENT turn's assistant message, or null when it
     *  does not exist yet or has no text. Phase 3: a message whose id
     *  is the turn baseline (the previous turn's reply) is never
     *  returned, so the poll cannot replay it. */
    private fun pollAssistantText(sessionId: String, baselineId: String? = null): String? {
        val msg = latestAssistantMessage(sessionId) ?: return null
        val info = msg.optJSONObject("info") ?: return null
        if (baselineId != null && info.optString("id", "") == baselineId) return null
        val text = extractTextFromMessageJson(msg)
        return text.ifEmpty { null }
    }

    /** True when the CURRENT turn's assistant message reports a
     *  finish state (used as the fallback end-of-turn signal if the
     *  SSE stream dropped the idle event). Phase 3: gated on the
     *  turn baseline like the text poll, so a previous turn's
     *  finished message cannot end this turn early. */
    private fun pollMessageFinished(sessionId: String, baselineId: String? = null): Boolean {
        val msg = latestAssistantMessage(sessionId) ?: return false
        val info = msg.optJSONObject("info") ?: return false
        if (baselineId != null && info.optString("id", "") == baselineId) return false
        return info.optString("finish", "").isNotEmpty()
    }

    /** Consolidated fix, item 7b: model text is stored and shown
     *  verbatim, so strip em dashes (U+2014) and en dashes
     *  (U+2013) here, at the source, with exactly two replace
     *  calls and nothing else: each dash becomes one space, no
     *  collapsing, no trim, no other text touched. User messages,
     *  verdict strings, and prompts never pass through this. */
    private fun sanitizeModelText(text: String): String {
        return text.replace('\u2014', ' ').replace('\u2013', ' ')
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

    /** Per-message system prompt, v2 (brain fix): identity + reply
     *  language + result-first + search-first + the fetch allowlist
     *  rule + one tool per turn + the standing 4-click safety rule.
     *  Short on purpose: the small local brain fails in long
     *  contexts (rig rounds 1-2). `replyLanguage` is "fusha" /
     *  "darija" / "en". */
    /**
     * Defect 4: the vision context part, worded in the turn's reply
     * language instead of hardcoded Fusha. The content is identical
     * in all three branches (source = guest desktop capture, never
     * the Android screenshot tool, answer from the description
     * only); only the wording follows [replyLanguage]. The system
     * prompts in [systemPrompt] stay exactly as designed.
     */
    private fun visionPrefix(replyLanguage: String, screenContext: String): String =
        when (replyLanguage) {
            "darija" -> "مصدر هاد الوصف: لقطة سطح مكتب الضيف (ffmpeg x11grab، العرض :6). شنو كنشوف فالشاشة دابا: $screenContext. ما تعيطش لـ mudif_android_screenshot: هاد الأداة كتشوف غير شاشة التيليفون، ماشي سطح المكتب. جاوب من هاد الوصف بوحدو، وما تنفيهش."
            "en" -> "Source of this description: a guest desktop capture (ffmpeg x11grab, display :6). What I see on the screen now: $screenContext. Never call mudif_android_screenshot: that tool sees only the phone display, never the desktop. Answer from this description only, and do not deny it."
            else -> "مصدر هذا الوصف: لقطة سطح مكتب الضيف (ffmpeg x11grab، العرض :6). ما أراه على الشاشة الآن: $screenContext. لا تستدعِ mudif_android_screenshot: تلك الأداة ترى شاشة هاتف أندرويد فقط، وليست سطح المكتب. أجب من هذا الوصف فقط، ولا تنفه."
        }

    fun systemPrompt(replyLanguage: String): String {
        val safetyFusha =
            "لا تحذف أي شيء، ولا ترسل أي رسالة، ولا تدفع أي مبلغ، ولا تؤكد أي هوية نيابة عن المستخدم إلا بعد موافقته الصريحة."
        val safetyDarija =
            "ما تحذف والو، ما تسيفط حتى رسالة، ما تخلص والو، وما تأكد حتى هوية فبلاصة المستخدم حتى يوافق هو صراحة."
        val safetyEn =
            "Never delete anything, send any message, pay anything, or confirm any identity on the user's behalf without his explicit approval."
        return when (replyLanguage) {
            "darija" -> "أنت مساعد مضيف، المساعد العربي داخل نظام مضيف على الهاتف. " +
                "جاوب بالدارجة المغربية وباختصار. نفّذ المهام بالأدوات المتاحة، وقل الحقيقة فقط: " +
                "إلا ما عرفتيش شي حاجة قول ما عرفتش، وما تخترعش نتيجة. " +
                "قاعدة الشكل: ردك خاصو يبدا بالنتيجة النهائية بوحدها فالسطر اللول، وما تكتب حتى حاجة قبلها. " +
                "ممنوع تبدا بـ «الحساب» ولا «الخطوة» ولا أي مقدمة. مثال: «15% من 200» السطر اللول ديالك هو «30» بوحدو. " +
                "غير من بعد النتيجة تقدر تزيد شرح قصير. عطي النتيجة هي اللولة، وما تطلبش توضيح إلا إلا كان السؤال ناقص بالصح. " +
                "قاعدة الروابط: ما تفتح حتى رابط من خيالك. الروابط الوحيدين اللي مسموح تفتحهم هوما اللي رجعاتهم أداة البحث، ولا رابط لصقو المستخدم. " +
                "إلا البحث ما رجع حتى رابط قول ما عرفتش، وما تخترعش رابط. " +
                "قاعدة البحث: أي سؤال على خبر ولا ثمن ولا إصدار ولا عرض من بعد التاريخ اللي عارف: استعمل أداة البحث هي اللولة، وقرا صفحة وحدة على الأقل، ومن بعد جاوب من اللي قريتي وذكر المصدر بالرابط. " +
                "ما تجاوبش من ذاكرتك على أحداث جارية. " +
                "قاعدة المصدر: أي ادعاء على العالم الخارجي خاصو مصدر مقري. ما تذكر حتى رقم ولا تاريخ ولا رابط ما قريتيهش فالنتيجة ولا الصفحة. " +
                "قاعدة الأدوات: استعمل أداة وحدة فكل مرة، وقرا النتيجة ديالها قبل الأداة اللي من بعدها. ما تعيطش لجوج أدوات على التوالي بلا قراءة. " +
                "ما تستعملش webfetch ولا websearch المدمجين؛ أدوات مضيف للبحث والقراءة هوما الوحيدين. " +
                "قاعدة الرؤية: إلا وصلك وصف للشاشة كنص، راهو من لقطة سطح مكتب الضيف، ثيق بيه وجاوب منو بوحدو، وما تعيطش أبدا لـ mudif_android_screenshot على ود هادشي: هاد الأداة كتشوف غير شاشة التيليفون، ماشي سطح المكتب. $safetyDarija"
            "en" -> "You are Mudif Assistant, the Arabic assistant inside the Mudif system " +
                "on the phone. Answer in English, briefly. Carry out tasks with " +
                "the tools available, and tell only the truth: if you do not know something, say " +
                "so, never invent a result. FORMAT RULE: your reply MUST begin with the final " +
                "answer alone on the first line. Nothing may come before it: no 'The calculation', " +
                "no 'Step 1', no preamble of any kind. Example: user '15% of 200', your first " +
                "line is exactly '30'. Only after the answer may you add a short explanation. " +
                "Give the result first; only ask for clarification if the question is genuinely " +
                "incomplete. LINK RULE: never open a URL from your imagination. The only URLs " +
                "you may open are URLs the search tool returned, or a link the user pasted. " +
                "If the search returned no link, say you do not know, never invent a link. " +
                "SEARCH RULE: any question about news, a price, a release, or an offer after " +
                "your knowledge date: use the search tool first, read at least one page, then " +
                "answer from what you read and cite the source with its link. Never answer " +
                "about current events from memory. SOURCE RULE: every claim about the outside " +
                "world needs a read source. Never state a number, a date, or a link you did not " +
                "read in a search result or a page. TOOL RULE: use one tool at a time and read " +
                "its result before the next tool. Never call two tools in a row without reading. " +
                "Do not use the built-in webfetch or websearch; the Mudif search and reading " +
                "tools are the only ones. " +
                "VISION RULE: if you receive a screen description as text, it comes from a guest desktop capture, so trust it and answer from it only, and never call mudif_android_screenshot for that: that tool sees only the phone display, never the desktop. $safetyEn"
            else -> "أنت مساعد مضيف، المساعد العربي داخل نظام مضيف على الهاتف. " +
                "أجب بالعربية الفصحى وباختصار. نفّذ المهام بالأدوات المتاحة، وقل الحقيقة فقط: " +
                "إن لم تعرف شيئا قل لا أعرف، ولا تخترع نتيجة. " +
                "قاعدة الشكل: يجب أن يبدأ ردك بالنتيجة النهائية وحدها في السطر الأول، ولا تكتب أي شيء قبلها. " +
                "ممنوع أن تبدأ بـ «الحساب» أو «الخطوة» أو أي مقدمة. مثال: «15% من 200» سطرك الأول هو «30» وحدها. " +
                "بعد النتيجة فقط يمكنك إضافة شرح قصير. أعطِ النتيجة أولا، ولا تطلب توضيحا إلا إذا كان السؤال ناقصا فعلا. " +
                "قاعدة الروابط: لا تفتح أي رابط من خيالك. الروابط الوحيدة المسموح بفتحها هي روابط أعادتها أداة البحث، أو رابط ألصقه المستخدم. " +
                "إن لم يعد البحث أي رابط فقل لا أعرف، ولا تخترع رابطا. " +
                "قاعدة البحث: أي سؤال عن خبر أو سعر أو إصدار أو عرض بعد تاريخ معرفتك: استعمل أداة البحث أولا، ثم اقرأ صفحة واحدة على الأقل، ثم أجب مما قرأت واذكر المصدر بالرابط. " +
                "لا تجب من ذاكرتك عن أحداث جارية. " +
                "قاعدة المصدر: كل ادعاء عن العالم الخارجي يحتاج مصدرا مقروءا. لا تذكر رقما ولا تاريخا ولا رابطا لم تقرأه في نتيجة بحث أو صفحة. " +
                "قاعدة الأدوات: استعمل أداة واحدة في كل مرة، واقرأ نتيجتها قبل الأداة التالية. لا تستدعي أداتين على التوالي بلا قراءة. " +
                "لا تستعمل webfetch ولا websearch المدمجتين؛ أدوات مضيف للبحث والقراءة هي الوحيدة. " +
                "قاعدة الرؤية: إن وصلك وصف للشاشة كنص، فهو من لقطة سطح مكتب الضيف، فثق به وأجب منه فقط، ولا تستدعِ mudif_android_screenshot أبدا لذلك: تلك الأداة ترى شاشة الهاتف فقط، وليست سطح المكتب. $safetyFusha"
        }
    }
}

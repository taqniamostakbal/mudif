package com.bolm.mudif.engine

import android.util.Base64
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Vision increment 1 ("see and describe", local only): asks the
 * local Ollama vision model to describe one desktop screenshot in
 * words. Talks directly to Ollama on loopback (127.0.0.1:11434),
 * bypassing OpenCode entirely; the pixels never leave the phone.
 *
 * API shape per Ollama docs/api.md: POST /api/chat accepts a base64
 * `images` array on a message for multimodal models. The description
 * is TEXT; the reasoning brain receives it as observed context, the
 * vision model never does tool calls.
 *
 * The prompt is short and fixed on purpose (the vision model
 * reads one frame in one pass) and forbids reading passwords / codes aloud.
 */
object VisionClient {
    private const val TAG = "VisionClient"

    private const val OLLAMA_CHAT_URL = "http://127.0.0.1:11434/api/chat"
    private const val CONNECT_TIMEOUT_MS = 5_000
    /** A 3B vision model on a phone CPU is slow: allow 5 minutes. */
    private const val READ_TIMEOUT_MS = 300_000

    /** Capture source this description always comes from: the
     *  guest desktop frame from VisionCapture, never the Android
     *  display. Logged on every successful read (see describe). */
    const val SOURCE_LABEL = "guest_desktop_x11grab"

    const val VISION_PROMPT =
        "Describe this desktop screenshot briefly: open windows, the focused window, " +
            "any error dialog or notification, and all visible text exactly as shown. " +
            "If you cannot read a word, say so, do not guess. " +
            "If a password or code is visible, say only that a code is visible, never repeat it."

    /** Describes the JPEG bytes, or null on any failure (the caller
     *  reports it honestly instead of inventing a description).
     *  Never called on the UI thread. [question] is the user's own
     *  question in their reply language; when null the fixed
     *  VISION_PROMPT is used (back-compat). The question and the
     *  image travel in ONE user message with the mudif-vision system
     *  prompt (p51: probes A/B proved that shape; a split message
     *  is blind). */
    fun describe(jpegBytes: ByteArray, question: String? = null): String? {
        return try {
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("model", AgentServer.VISION_MODEL)
                put("stream", false)
                put(
                    "messages",
                    org.json.JSONArray()
                        .put(
                            JSONObject().apply {
                                put("role", "system")
                                put("content", AgentServer.SYSTEM_PROMPT_VISION)
                            }
                        )
                        .put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", question ?: VISION_PROMPT)
                                put("images", org.json.JSONArray().put(base64))
                            }
                        )
                )
            }.toString()
            val conn = URL(OLLAMA_CHAT_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "describe: source=$SOURCE_LABEL model=${AgentServer.VISION_MODEL} HTTP $code")
                conn.disconnect()
                return null
            }
            val raw = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val replyJson = JSONObject(raw)
            val description = replyJson
                .optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (description != null) {
                // p51: the token counts prove the image really
                // reached the model (~1220 prompt tokens); under
                // ~1000 is a failed vision turn even if the text
                // looks plausible.
                val promptEvalCount = replyJson.optInt("prompt_eval_count", -1)
                val evalCount = replyJson.optInt("eval_count", -1)
                MudifLog.i(TAG, "describe: source=$SOURCE_LABEL model=${AgentServer.VISION_MODEL} imageBytes=${jpegBytes.size} descriptionChars=${description.length} promptEvalCount=$promptEvalCount evalCount=$evalCount")
            }
            // Strip em/en dashes at the source so the stored description is dash-free.
            description?.replace('\u2014', ' ')?.replace('\u2013', ' ')
        } catch (e: Exception) {
            Log.w(TAG, "describe failed", e)
            null
        }
    }
}

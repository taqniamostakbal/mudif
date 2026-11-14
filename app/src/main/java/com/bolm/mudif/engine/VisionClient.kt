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
 * The prompt is short and fixed on purpose (moondream has a 2K
 * context) and forbids reading passwords / codes aloud.
 */
object VisionClient {
    private const val TAG = "VisionClient"

    private const val OLLAMA_CHAT_URL = "http://127.0.0.1:11434/api/chat"
    private const val CONNECT_TIMEOUT_MS = 5_000
    /** A 1.7B vision model on a phone CPU is slow: allow 5 minutes. */
    private const val READ_TIMEOUT_MS = 300_000

    const val VISION_PROMPT =
        "Describe this desktop screenshot briefly: open windows, the focused window, " +
            "any error dialog or notification, and all visible text exactly as shown. " +
            "If you cannot read a word, say so, do not guess. " +
            "If a password or code is visible, say only that a code is visible, never repeat it."

    /** Describes the JPEG bytes, or null on any failure (the caller
     *  reports it honestly instead of inventing a description).
     *  Never called on the UI thread. */
    fun describe(jpegBytes: ByteArray): String? {
        return try {
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("model", AgentServer.VISION_MODEL)
                put("stream", false)
                put(
                    "messages",
                    org.json.JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", VISION_PROMPT)
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
                Log.w(TAG, "describe: HTTP $code")
                conn.disconnect()
                return null
            }
            val raw = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            JSONObject(raw)
                .optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "describe failed", e)
            null
        }
    }
}

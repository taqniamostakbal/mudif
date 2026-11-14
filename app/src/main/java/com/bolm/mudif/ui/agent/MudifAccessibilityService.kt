package com.bolm.mudif.ui.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 6, the agent's hands and eyes on the Android layer.
 *
 * What this service can access, stated plainly (this is also what
 * the user sees in Android Settings -> Accessibility, via
 * `accessibility_service_description`):
 *
 * - Only the ACTIVE window's UI tree, and only when the agent asks
 *   for it ([getTreeJson]): class names, visible text, content
 *   descriptions, bounds, and whether a node is clickable/editable.
 * - Gestures the agent requests: tap, swipe, and typing into the
 *   currently focused editable field ([tap], [swipe], [type]).
 * - One still screenshot of the display ([screenshot], API 30+),
 *   returned to the agent as a JPEG for verification.
 *
 * What it can NEVER do:
 *
 * - It never reads password fields: [type] refuses any node where
 *   [AccessibilityNodeInfo.isPassword] is true, and [getTreeJson]
 *   omits the text and content description of password nodes.
 * - It never logs keystrokes: it does not listen for key events
 *   and [onAccessibilityEvent] is a deliberate no-op. It acts only
 *   when the bridge asks it to.
 * - Nothing leaves the phone: the only caller is [com.bolm.mudif.engine.MudifBridge],
 *   a loopback-only (127.0.0.1) server inside this app. No tree,
 *   screenshot, or typed text is sent to any network host.
 *
 * The user enables this service once in Android Settings ->
 * Accessibility -> Mudif Assistant. The app cannot enable it
 * itself; when it is off, the bridge answers 503 instead of
 * faking a tree or a screenshot.
 */
class MudifAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MudifA11y"

        /** The live service, or null when it is not enabled/connected. */
        @Volatile
        var instance: MudifAccessibilityService? = null
            private set

        /** Tree walk limits: depth cap and total node cap. */
        const val MAX_DEPTH = 12
        const val MAX_NODES = 500

        private const val GESTURE_TIMEOUT_MS = 5000L
        private const val SCREENSHOT_TIMEOUT_MS = 10_000L
        private const val TAP_DURATION_MS = 100L
        private const val SWIPE_DURATION_MS = 400L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Deliberate no-op: this service never reacts to accessibility
     * events on its own (no keystroke logging, no background
     * watching). It only acts when the bridge calls one of the
     * methods below. Events are still subscribed in the config so
     * the system keeps the service bound and the tree fresh.
     */
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    /** Error JSON: {ok:false, error:<machine key>, detail:<human text>}. */
    private fun err(error: String, detail: String): JSONObject =
        JSONObject().apply {
            put("ok", false)
            put("error", error)
            put("detail", detail)
        }

    /**
     * The active window's UI tree as JSON.
     * Each node: className, text, contentDescription, bounds
     * {left,top,right,bottom}, clickable, editable, isPassword,
     * childCount, children[]. Password nodes carry no text.
     * Depth is capped at [MAX_DEPTH], total nodes at [MAX_NODES];
     * when the cap is hit the walk stops and `truncated:true` is set.
     */
    fun getTreeJson(): JSONObject {
        val root = rootInActiveWindow
            ?: return err("no_active_window", "No active window: the app has no window in the foreground.")
        val counter = intArrayOf(0)
        val truncated = booleanArrayOf(false)
        val tree = walk(root, 0, counter, truncated)
        return JSONObject().apply {
            put("ok", true)
            put("truncated", truncated[0])
            put("nodes", counter[0])
            put("tree", tree)
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
        truncated: BooleanArray
    ): JSONObject {
        val out = JSONObject()
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) {
            truncated[0] = true
            out.put("truncated", true)
            return out
        }
        counter[0]++
        out.put("className", node.className?.toString() ?: "")
        // Password fields never expose their text, here or to typing.
        if (!node.isPassword) {
            out.put("text", node.text?.toString() ?: "")
            out.put("contentDescription", node.contentDescription?.toString() ?: "")
        } else {
            out.put("text", "")
            out.put("contentDescription", "")
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        out.put("bounds", JSONObject().apply {
            put("left", bounds.left)
            put("top", bounds.top)
            put("right", bounds.right)
            put("bottom", bounds.bottom)
        })
        out.put("clickable", node.isClickable)
        out.put("editable", node.isEditable)
        out.put("isPassword", node.isPassword)
        out.put("childCount", node.childCount)
        if (depth < MAX_DEPTH) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                children.put(walk(child, depth + 1, counter, truncated))
                if (counter[0] >= MAX_NODES) {
                    truncated[0] = true
                    break
                }
            }
            out.put("children", children)
        }
        return out
    }

    /** Tap at screen coordinates [x],[y]. Returns {ok, performed}. */
    fun tap(x: Int, y: Int): JSONObject {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return runGesture(gesture, "tap")
    }

    /** Swipe from ([x1],[y1]) to ([x2],[y2]). Returns {ok, performed}. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): JSONObject {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()
        return runGesture(gesture, "swipe")
    }

    private fun runGesture(gesture: GestureDescription, what: String): JSONObject {
        val latch = CountDownLatch(1)
        val done = booleanArrayOf(false)
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done[0] = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done[0] = false
                latch.countDown()
            }
        }, null)
        if (!ok) return err("gesture_rejected", "The system refused the $what gesture (dispatchGesture returned false).")
        val finished = try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) return err("gesture_timeout", "The $what gesture did not finish within ${GESTURE_TIMEOUT_MS}ms.")
        if (!done[0]) return err("gesture_cancelled", "The $what gesture was cancelled by the system.")
        return JSONObject().apply {
            put("ok", true)
            put("performed", true)
        }
    }

    /**
     * Types [text] into the currently focused editable field.
     * Refuses password fields outright ([AccessibilityNodeInfo.isPassword])
     * and refuses when nothing editable has focus. Never types into
     * a field the user did not focus.
     */
    fun type(text: String): JSONObject {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return err("no_focused_field", "No editable field has focus: tap the field first, then type.")
        if (node.isPassword) {
            return err("password_refused", "Refused: the focused field is a password field. The agent never types passwords.")
        }
        if (!node.isEditable) {
            return err("not_editable", "The focused field is not editable.")
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!performed) {
            return err("type_failed", "ACTION_SET_TEXT was rejected by the focused field.")
        }
        return JSONObject().apply {
            put("ok", true)
            put("performed", true)
        }
    }

    /**
     * One still screenshot of the default display as
     * {ok:true, format:"jpeg", data:<base64>}. API 30+ only; older
     * Android returns {ok:false, error:"unsupported_api"}.
     * The hardware buffer is closed after encoding; nothing is
     * written to disk.
     */
    fun screenshot(): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return err("unsupported_api", "Screenshots need Android 11 (API 30) or newer; this device is API ${Build.VERSION.SDK_INT}.")
        }
        val latch = CountDownLatch(1)
        var result: JSONObject? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    result = encodeScreenshot(screenshot)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    result = err(
                        "screenshot_failed",
                        "takeScreenshot failed (error code $errorCode). The service may be rate-limited: Android allows about one screenshot per second."
                    )
                    latch.countDown()
                }
            }
        )
        val finished = try {
            latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) return err("screenshot_timeout", "takeScreenshot did not answer within ${SCREENSHOT_TIMEOUT_MS}ms.")
        return result ?: err("screenshot_failed", "takeScreenshot returned no result.")
    }

    private fun encodeScreenshot(screenshot: ScreenshotResult): JSONObject {
        // onSuccess already means success: ScreenshotResult has no status
        // field and no STATUS_SUCCESS constant; a null buffer is the only
        // failure mode left here.
        val buffer = screenshot.hardwareBuffer
            ?: return err("screenshot_failed", "takeScreenshot returned no image buffer.")
        return try {
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                ?: return err("screenshot_failed", "Could not decode the screenshot buffer.")
            val out = ByteArrayOutputStream()
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (!compressed) {
                return err("screenshot_failed", "JPEG encoding of the screenshot failed.")
            }
            JSONObject().apply {
                put("ok", true)
                put("format", "jpeg")
                put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            Log.w(TAG, "encodeScreenshot failed", e)
            err("screenshot_failed", "Could not encode the screenshot: ${e.message}")
        } finally {
            try {
                buffer.close()
            } catch (_: Exception) {
            }
        }
    }
}

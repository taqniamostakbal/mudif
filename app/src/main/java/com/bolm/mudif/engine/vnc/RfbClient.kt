package com.bolm.mudif.engine.vnc

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import android.graphics.Bitmap
import android.graphics.Color

/**
 * Minimal RFB (VNC) client for Mudif P5.2.
 * Connects to localhost:5900 (x11vnc), displays the Linux desktop.
 *
 * Supports: RFB 3.8, NoAuth, Raw encoding, 32-bit true color.
 * This is a minimal implementation - just enough to see the desktop.
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5900
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var desktopName: String = ""
        private set

    var framebuffer: Bitmap? = null
        private set

    var onFramebufferUpdate: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile
    private var running = false

    /**
     * Connect and perform RFB handshake. Returns true on success.
     */
    fun connect(): Boolean {
        try {
            socket = Socket(host, port)
            socket!!.tcpNoDelay = true
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())

            // 1. Version handshake
            val serverVer = ByteArray(12)
            input!!.readFully(serverVer)
            // Expect "RFB 003.008\n"
            output!!.write("RFB 003.008\n".toByteArray())
            output!!.flush()

            // 2. Security: server sends number of types, then types
            val numTypes = input!!.readUnsignedByte()
            if (numTypes == 0) {
                val len = input!!.readInt()
                val reason = ByteArray(len)
                input!!.readFully(reason)
                onError?.invoke("Security failed: ${String(reason)}")
                return false
            }
            val types = ByteArray(numTypes)
            input!!.readFully(types)
            // Choose None (1) if available
            if (!types.contains(1.toByte())) {
                onError?.invoke("No 'None' auth available")
                return false
            }
            output!!.writeByte(1)
            output!!.flush()

            // 3. SecurityResult
            val secResult = input!!.readInt()
            if (secResult != 0) {
                onError?.invoke("Auth failed: $secResult")
                return false
            }

            // 4. ClientInit (shared = 1)
            output!!.writeByte(1)
            output!!.flush()

            // 5. ServerInit
            width = input!!.readUnsignedShort()
            height = input!!.readUnsignedShort()

            // Pixel format (16 bytes) - we read but use our own
            val pixFmt = ByteArray(16)
            input!!.readFully(pixFmt)

            val nameLen = input!!.readInt()
            val nameBytes = ByteArray(nameLen)
            input!!.readFully(nameBytes)
            desktopName = String(nameBytes)

            // Create framebuffer
            framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // 6. SetEncodings: Raw only
            output!!.writeByte(2) // SetEncodings
            output!!.writeByte(0) // padding
            output!!.writeShort(1) // 1 encoding
            output!!.writeInt(0) // Raw
            output!!.flush()

            // 7. SetPixelFormat: 32-bit true color, little endian
            output!!.writeByte(0) // SetPixelFormat
            output!!.writeByte(0); output!!.writeByte(0); output!!.writeByte(0) // padding
            output!!.writeByte(32) // bits-per-pixel
            output!!.writeByte(24) // depth
            output!!.writeByte(0) // big-endian = false
            output!!.writeByte(1) // true-color = true
            output!!.writeShort(255) // red-max
            output!!.writeShort(255) // green-max
            output!!.writeShort(255) // blue-max
            output!!.writeByte(16) // red-shift
            output!!.writeByte(8) // green-shift
            output!!.writeByte(0) // blue-shift
            output!!.writeByte(0); output!!.writeByte(0); output!!.writeByte(0) // padding
            output!!.flush()

            return true
        } catch (e: Exception) {
            onError?.invoke("Connect failed: ${e.message}")
            return false
        }
    }

    /**
     * Start the update loop. Requests full framebuffer, then incremental.
     */
    fun start() {
        running = true
        Thread {
            try {
                // Initial full update
                requestUpdate(0, 0, width, height, false)
                while (running) {
                    readServerMessage()
                }
            } catch (e: Exception) {
                if (running) onError?.invoke("Update loop: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun requestUpdate(x: Int, y: Int, w: Int, h: Int, incremental: Boolean) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(3) // FramebufferUpdateRequest
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(x)
            out.writeShort(y)
            out.writeShort(w)
            out.writeShort(h)
            out.flush()
        }
    }

    private fun readServerMessage() {
        val inp = input ?: return
        val msgType = inp.readUnsignedByte()
        when (msgType) {
            0 -> { // FramebufferUpdate
                inp.readUnsignedByte() // padding
                val numRects = inp.readUnsignedShort()
                val fb = framebuffer ?: return
                val pixels = IntArray(width * height)
                fb.getPixels(pixels, 0, width, 0, 0, width, height)

                repeat(numRects) {
                    val x = inp.readUnsignedShort()
                    val y = inp.readUnsignedShort()
                    val w = inp.readUnsignedShort()
                    val h = inp.readUnsignedShort()
                    val encoding = inp.readInt()
                    if (encoding == 0) { // Raw
                        val count = w * h
                        val rectPixels = IntArray(count)
                        repeat(count) { i ->
                            val b = inp.readUnsignedByte()
                            val g = inp.readUnsignedByte()
                            val r = inp.readUnsignedByte()
                            inp.readUnsignedByte() // padding/alpha
                            rectPixels[i] = Color.rgb(r, g, b)
                        }
                        // Copy into framebuffer
                        for (ry in 0 until h) {
                            val srcOff = ry * w
                            val dstOff = (y + ry) * width + x
                            System.arraycopy(rectPixels, srcOff, pixels, dstOff, w)
                        }
                    } else {
                        onError?.invoke("Unsupported encoding: $encoding")
                        return
                    }
                }
                fb.setPixels(pixels, 0, width, 0, 0, width, height)
                onFramebufferUpdate?.invoke(fb)
                // Request next incremental update
                requestUpdate(0, 0, width, height, true)
            }
            2 -> { // Bell - ignore
            }
            3 -> { // ServerCutText - ignore
                inp.skipBytes(3) // padding
                val len = inp.readInt()
                inp.skipBytes(len)
            }
            else -> {
                onError?.invoke("Unknown message: $msgType")
            }
        }
    }

    /**
     * Send pointer (mouse) event.
     */
    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        synchronized(out) {
            try {
                out.writeByte(5) // PointerEvent
                out.writeByte(buttonMask)
                out.writeShort(x)
                out.writeShort(y)
                out.flush()
            } catch (_: Exception) {}
        }
    }

    /**
     * Send key event.
     */
    fun sendKey(keysym: Int, down: Boolean) {
        val out = output ?: return
        synchronized(out) {
            try {
                out.writeByte(4) // KeyEvent
                out.writeByte(if (down) 1 else 0)
                out.writeByte(0); out.writeByte(0) // padding
                out.writeInt(keysym)
                out.flush()
            } catch (_: Exception) {}
        }
    }
}

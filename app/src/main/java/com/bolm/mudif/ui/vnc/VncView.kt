package com.bolm.mudif.ui.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.bolm.mudif.engine.vnc.RfbClient

/**
 * Displays the VNC framebuffer and forwards touch as mouse events.
 */
class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
    private val srcRect = Rect()
    private val dstRect = Rect()

    var rfb: RfbClient? = null

    fun updateFramebuffer(bmp: Bitmap) {
        bitmap = bmp
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: run {
            // Draw placeholder
            canvas.drawColor(0xFF1A1A2E.toInt())
            return
        }
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val client = rfb ?: return false
        val bmp = bitmap ?: return false

        // Map touch to framebuffer coordinates
        val fbX = ((event.x / width) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val fbY = ((event.y / height) * bmp.height).toInt().coerceIn(0, bmp.height - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                client.sendPointer(fbX, fbY, 1) // left button down
            }
            MotionEvent.ACTION_MOVE -> {
                client.sendPointer(fbX, fbY, 1) // drag
            }
            MotionEvent.ACTION_UP -> {
                client.sendPointer(fbX, fbY, 0) // button up
            }
        }
        return true
    }
}

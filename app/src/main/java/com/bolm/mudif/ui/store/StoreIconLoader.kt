package com.bolm.mudif.ui.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One shared icon loader for the store, games, and apps lists.
 * Defect 2 fix: the adapters used to open the asset and fully decode
 * the PNG on the main thread inside onBindViewHolder, which froze the
 * app (ANR) while scrolling. All asset IO and decoding now runs on a
 * small background pool; the bitmap is posted to the ImageView only
 * if the view is still bound to the same icon (tag check), so a
 * recycled holder never shows a stale icon. The placeholder the
 * caller drew stays visible until the bitmap arrives.
 *
 * Cache rules: decoded bitmaps live in a non-null map, and failed
 * opens are recorded once in [missingIcons]. A missing icon therefore
 * costs one failed open ever, not one per bind (the old nullable
 * getOrPut cache re-ran the failing open on every rebind, because a
 * stored null reads back as absent).
 */
object StoreIconLoader {

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val missingIcons = ConcurrentHashMap.newKeySet<String>()

    private val pendingLock = Any()
    private val pending = HashMap<String, MutableList<Pending>>()

    private data class Pending(
        val view: ImageView,
        val onResult: (Bitmap?) -> Unit
    )

    /**
     * Load [iconName] from assets/store-icons/ downsampled to about
     * [targetDp] on a side, and deliver it on the main thread.
     * A cached bitmap or a known-missing icon answers at once on the
     * calling (main) thread. [onResult] runs only while
     * [imageView] is still tagged with [iconName]; pass null and the
     * caller keeps its placeholder.
     */
    fun load(
        context: Context,
        iconName: String,
        targetDp: Int,
        imageView: ImageView,
        onResult: (Bitmap?) -> Unit
    ) {
        imageView.tag = iconName
        cache[iconName]?.let { onResult(it); return }
        if (missingIcons.contains(iconName)) {
            onResult(null)
            return
        }
        val appContext = context.applicationContext
        synchronized(pendingLock) {
            val list = pending.getOrPut(iconName) { mutableListOf() }
            list.add(Pending(imageView, onResult))
            if (list.size > 1) return
        }
        executor.execute {
            val targetPx = (targetDp * appContext.resources.displayMetrics.density + 0.5f).toInt()
            val bitmap = decodeSampled(appContext, iconName, targetPx)
            mainHandler.post {
                val waiting: List<Pending>
                synchronized(pendingLock) {
                    waiting = pending.remove(iconName) ?: emptyList()
                }
                if (bitmap != null) {
                    cache[iconName] = bitmap
                } else {
                    missingIcons.add(iconName)
                }
                for (item in waiting) {
                    if (item.view.tag == iconName) item.onResult(bitmap)
                }
            }
        }
    }

    /** Decode the PNG downsampled so neither side exceeds [targetPx]. */
    private fun decodeSampled(context: Context, iconName: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.assets.open("store-icons/$iconName").use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return try {
            context.assets.open("store-icons/$iconName").use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Largest power of two keeping both sides at or above [targetPx]. */
    private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }
}

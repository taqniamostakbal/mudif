package com.bolm.mudif.ui.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
 * Cache rules: decoded bitmaps live in a bounded 12 MB LRU (p70:
 * the old unbounded map kept every icon forever, 32.8 MB for the
 * full catalog), and failed opens are recorded once in
 * [missingIcons]. A missing icon therefore costs one failed open
 * ever, not one per bind (the old nullable getOrPut cache re-ran
 * the failing open on every rebind, because a stored null reads
 * back as absent). Decodes for recycled rows are cancelled by
 * [cancel], so a fling no longer queues work for off-screen rows.
 */
object StoreIconLoader {

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * p70: bounded LRU cache, 12 MB. The old cache was an unbounded
     * map that kept every decoded icon forever: the real catalog
     * decodes to 32.8 MB at 56dp (934 icons x 36 KB), retained for
     * the life of the process, and a fling filled it to the top
     * while the decode queue behind it kept growing. 12 MB holds
     * about 340 icons, far more than any visible window plus
     * scrollback; evicted bitmaps are simply decoded again.
     * LruCache is thread-safe, so the pool thread (put) and the main
     * thread (get) share it directly.
     */
    private const val MAX_CACHE_KB = 12 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            maxOf(1, value.byteCount / 1024)
    }
    private val missingIcons = ConcurrentHashMap.newKeySet<String>()

    private val pendingLock = Any()
    private val pending = HashMap<String, MutableList<Pending>>()
    private val running = HashMap<String, Future<*>>()

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
            running[iconName] = executor.submit {
                val targetPx = (targetDp * appContext.resources.displayMetrics.density + 0.5f).toInt()
                val bitmap = decodeSampled(appContext, iconName, targetPx)
                mainHandler.post {
                    val waiting: List<Pending>
                    synchronized(pendingLock) {
                        waiting = pending.remove(iconName) ?: emptyList()
                        running.remove(iconName)
                    }
                    if (bitmap != null) {
                        cache.put(iconName, bitmap)
                    } else {
                        missingIcons.add(iconName)
                    }
                    for (item in waiting) {
                        if (item.view.tag == iconName) item.onResult(bitmap)
                    }
                }
            }
        }
    }

    /**
     * Drop a recycled view's pending deliveries (p70). A fling used
     * to queue icon decodes for rows that had already scrolled off
     * screen, and the callbacks held the recycled ImageViews; the
     * tag check only skipped the final apply. Adapters call this
     * from onViewRecycled: the view leaves every waiting list, and
     * an icon left with no waiters has its queued decode cancelled
     * before it runs. A decode already running finishes harmlessly:
     * its waiting list is empty, so the bitmap is only cached.
     */
    fun cancel(imageView: ImageView) {
        synchronized(pendingLock) {
            val emptied = ArrayList<String>()
            for ((icon, list) in pending) {
                list.removeAll { it.view === imageView }
                if (list.isEmpty()) emptied.add(icon)
            }
            for (icon in emptied) {
                pending.remove(icon)
                running.remove(icon)?.cancel(false)
            }
        }
        imageView.tag = null
    }

    /**
     * p74: drop every cached bitmap now (memory-pressure shed).
     * Called from AppDeathDetector.onMemoryPressure when the
     * system says memory is tight. Evicted bitmaps are simply
     * decoded again on the next bind; the missing-icon set is
     * kept, so known-missing icons are not re-probed. Pending
     * decodes are untouched: their results land in the (now
     * empty) cache as usual.
     */
    fun evictAll() {
        cache.evictAll()
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

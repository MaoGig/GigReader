package com.maogig.gigreader.core.pdf.render

import android.graphics.Bitmap

/**
 * Reuses ARGB_8888 bitmaps of identical dimensions (tiles are all the same size, and pages of a
 * document usually are too), avoiding a native allocation + GC pressure per rendered tile.
 * Bounded by [maxBytes]; excess bitmaps are simply dropped.
 *
 * Bitmaps are never `recycle()`d explicitly: the UI thread may still be recording a draw of a bitmap
 * the render worker just evicted, and drawing a recycled bitmap crashes. Pixel memory is native on
 * API 26+ and is freed as soon as the Bitmap object is collected.
 */
class BitmapPool(private val maxBytes: Long) {
    private val lock = Any()
    private val buckets = HashMap<Long, ArrayDeque<Bitmap>>()
    private var bytes = 0L

    val sizeBytes: Long get() = synchronized(lock) { bytes }

    fun obtain(width: Int, height: Int): Bitmap {
        synchronized(lock) {
            val deque = buckets[key(width, height)]
            while (deque != null && deque.isNotEmpty()) {
                val b = deque.removeLast()
                bytes -= b.allocationByteCount
                if (!b.isRecycled) return b
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled || !bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) return
        val size = bitmap.allocationByteCount.toLong()
        synchronized(lock) {
            if (bytes + size <= maxBytes) {
                buckets.getOrPut(key(bitmap.width, bitmap.height)) { ArrayDeque() }.addLast(bitmap)
                bytes += size
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            buckets.clear()
            bytes = 0
        }
    }

    private fun key(w: Int, h: Int): Long = (w.toLong() shl 32) or h.toLong()
}

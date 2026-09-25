package com.maogig.gigreader.core.pdf.render

import android.graphics.Bitmap
import android.os.Build
import com.maogig.gigreader.core.pdf.engine.PdfDocument
import com.maogig.gigreader.core.pdf.engine.RenderJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders small, compressed first-page covers for the library. Done once per document (at import)
 * so browsing the library only decodes a ~20 KB image per visible item and never opens a PDF.
 */
object CoverRenderer {
    /** Renders page [page] at [widthPx] wide and writes it as lossy WebP to [target] (atomic replace). */
    suspend fun renderTo(document: PdfDocument, target: File, widthPx: Int, page: Int = 0, quality: Int = 80) {
        if (document.pageCount == 0) return
        val size = document.pageSize(page)
        val heightPx = max(1, (widthPx * size.height / size.width).roundToInt()).coerceAtMost(widthPx * 3)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            document.render(page, listOf(RenderJob(bitmap, widthPx, heightPx)))
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                FileOutputStream(tmp).use { out ->
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    bitmap.compress(format, quality, out)
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                }
            }
        } finally {
            bitmap.recycle() // never shown on screen, so recycling here is safe
        }
    }
}

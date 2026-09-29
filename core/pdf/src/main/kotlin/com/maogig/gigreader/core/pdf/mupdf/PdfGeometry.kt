package com.maogig.gigreader.core.pdf.mupdf

/** An axis-aligned box in PDF user space or page space. */
internal data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = !(width > 0f && height > 0f)

    /** Same box with corners ordered (PDF allows any two opposite corners). */
    fun normalized(): Box = Box(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

    fun intersect(o: Box): Box = Box(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))
}

/** Page size math that mirrors what MuPDF's page bounds are made of, so sizes can be read without loading a page. */
internal object PdfGeometry {
    /** MuPDF's default when a page has no usable MediaBox (US Letter). */
    val DEFAULT_MEDIA_BOX = Box(0f, 0f, 612f, 792f)

    /**
     * Size in points, rotation applied, of a page with [mediaBox] and [cropBox] (raw values from the
     * page tree, `null` if absent), [rotate] degrees (any integer; only multiples of 90 count) and
     * [userUnit] (values <= 0 or NaN are ignored). Follows the PDF rules: the visible box is the
     * CropBox clipped to the MediaBox, and an empty result falls back to the MediaBox.
     */
    fun pageSize(mediaBox: Box?, cropBox: Box?, rotate: Int, userUnit: Float?): Pair<Float, Float> {
        var media = mediaBox?.normalized()
        if (media == null || media.isEmpty) media = DEFAULT_MEDIA_BOX
        var visible = media
        if (cropBox != null) {
            val clipped = cropBox.normalized().intersect(media)
            if (!clipped.isEmpty) visible = clipped
        }
        val unit = if (userUnit != null && userUnit > 0f && !userUnit.isNaN() && !userUnit.isInfinite()) userUnit else 1f
        var w = visible.width * unit
        var h = visible.height * unit
        if (quarterTurns(rotate) % 2 == 1) {
            val t = w
            w = h
            h = t
        }
        return w to h
    }

    /** 0..3 quarter turns; rotations that are not a multiple of 90 count as 0, like MuPDF. */
    fun quarterTurns(rotate: Int): Int {
        val r = ((rotate % 360) + 360) % 360
        return if (r % 90 == 0) r / 90 else 0
    }

    /** `[a b c d e f]` of the matrix that maps a page (with [bounds]) onto a [pageWidthPx] x [pageHeightPx] area. */
    fun tileMatrix(
        bounds: Box,
        pageWidthPx: Int,
        pageHeightPx: Int,
        offsetX: Int,
        offsetY: Int,
    ): FloatArray {
        val w = bounds.width
        val h = bounds.height
        val sx = if (w > 0f) pageWidthPx / w else 1f
        val sy = if (h > 0f) pageHeightPx / h else 1f
        return floatArrayOf(sx, 0f, 0f, sy, 0f - bounds.left * sx - offsetX, 0f - bounds.top * sy - offsetY)
    }

    /** Vertical position of a destination as a fraction of the page height, or `null` when unknown. */
    fun yFraction(y: Float, pageHeight: Float): Float? {
        if (y.isNaN() || y.isInfinite() || !(pageHeight > 0f)) return null
        return (y / pageHeight).coerceIn(0f, 1f)
    }

    /** Normalizes [b] (page space, bounds [page]) to 0..1; the result is clamped and never inverted. */
    fun normalize(b: Box, page: Box): NormRect {
        val w = if (page.width > 0f) page.width else 1f
        val h = if (page.height > 0f) page.height else 1f
        val n = b.normalized()
        fun cx(v: Float) = ((v - page.left) / w).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
        fun cy(v: Float) = ((v - page.top) / h).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
        return NormRect(cx(n.left), cy(n.top), cx(n.right), cy(n.bottom))
    }
}

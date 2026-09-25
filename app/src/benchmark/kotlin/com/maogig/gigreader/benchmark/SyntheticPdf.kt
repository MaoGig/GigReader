package com.maogig.gigreader.benchmark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * Writes a text-heavy, paper-like PDF (A4, 595×842 pt): running head, title block and abstract on
 * the first page, numbered section headings, justified-looking paragraphs and simple vector figures
 * (bar charts, line plots, scatter plots) with captions. Content is pseudo-random per title and page,
 * so pages differ from each other and documents with different titles have different bytes (the
 * importer de-duplicates by content hash).
 *
 * Memory: one [PdfDocument] and pages written one at a time (startPage → draw → finishPage). Each
 * finished page is kept by the framework as a small native picture until [PdfDocument.writeTo], so
 * the Java heap stays flat even for thousands of pages.
 */
internal object SyntheticPdf {
    fun write(file: File, title: String, pageCount: Int) {
        val painter = PagePainter(title, pageCount)
        val document = PdfDocument()
        try {
            for (index in 0 until pageCount) {
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(info)
                try {
                    painter.paint(page.canvas, index)
                } finally {
                    document.finishPage(page)
                }
            }
            file.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(file)).use { out -> document.writeTo(out) }
        } finally {
            document.close()
        }
    }
}

private class PagePainter(private val title: String, private val pageCount: Int) {
    private val bodyPaint = textPaint(BODY_TEXT_SIZE, Typeface.SERIF, Color.rgb(28, 28, 30))
    private val titlePaint = textPaint(20f, Typeface.create(Typeface.SERIF, Typeface.BOLD), Color.BLACK)
    private val headingPaint = textPaint(12.5f, Typeface.create(Typeface.SERIF, Typeface.BOLD), Color.BLACK)
    private val metaPaint = textPaint(10f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), Color.DKGRAY)
    private val captionPaint = textPaint(9f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), Color.DKGRAY)
    private val smallPaint = textPaint(8f, Typeface.SANS_SERIF, Color.GRAY)
    private val rulePaint = strokePaint(0.6f, Color.GRAY)
    private val axisPaint = strokePaint(0.9f, Color.BLACK)
    private val plotPaint = strokePaint(1.4f, Color.rgb(40, 70, 140))
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(170, 185, 210)
    }

    // Measured once: laying out a line is then just additions.
    private val wordWidths = FloatArray(WORDS.size) { bodyPaint.measureText(WORDS[it]) }
    private val spaceWidth = bodyPaint.measureText(" ")
    private val line = StringBuilder(160)
    private var sentenceStart = true

    fun paint(canvas: Canvas, index: Int) {
        val random = Random(title.hashCode() * 1_000_003L + index)
        drawRunningHead(canvas, index)
        var y = CONTENT_TOP
        var figures = 0
        var sections = 0
        sentenceStart = true
        if (index == 0) y = drawTitleBlock(canvas, random, y)
        while (y + LINE_HEIGHT <= CONTENT_BOTTOM) {
            val roll = random.nextInt(10)
            y = if (roll < 2 && y + FIGURE_MAX_SPACE <= CONTENT_BOTTOM) {
                figures++
                drawFigure(canvas, random, y, index, figures)
            } else if (roll < 4 && y + HEADING_SPACE + 3 * LINE_HEIGHT <= CONTENT_BOTTOM) {
                sections++
                drawHeading(canvas, random, y, index, sections)
            } else {
                drawParagraph(canvas, random, y, 3 + random.nextInt(7))
            }
        }
        val number = (index + 1).toString()
        canvas.drawText(number, (PAGE_WIDTH - smallPaint.measureText(number)) / 2f, PAGE_HEIGHT - 30f, smallPaint)
    }

    private fun drawRunningHead(canvas: Canvas, index: Int) {
        canvas.drawText(title, MARGIN, 36f, smallPaint)
        val position = "${index + 1} / $pageCount"
        canvas.drawText(position, PAGE_WIDTH - MARGIN - smallPaint.measureText(position), 36f, smallPaint)
        canvas.drawLine(MARGIN, 42f, PAGE_WIDTH - MARGIN, 42f, rulePaint)
    }

    private fun drawTitleBlock(canvas: Canvas, random: Random, top: Float): Float {
        var y = top + 28f
        drawCentered(canvas, title, titlePaint, y)
        y += 20f
        drawCentered(canvas, SUBTITLES[random.nextInt(SUBTITLES.size)], metaPaint, y)
        y += 18f
        drawCentered(canvas, AUTHORS[random.nextInt(AUTHORS.size)], metaPaint, y)
        y += 14f
        drawCentered(canvas, AFFILIATIONS[random.nextInt(AFFILIATIONS.size)], metaPaint, y)
        y += 30f
        canvas.drawText("Abstract", MARGIN, y, headingPaint)
        y = drawParagraph(canvas, random, y + 2f, 7)
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
        return y + 6f
    }

    private fun drawHeading(canvas: Canvas, random: Random, top: Float, pageIndex: Int, number: Int): Float {
        val baseline = top + 18f
        val text = "${pageIndex + 1}.$number  ${HEADINGS[random.nextInt(HEADINGS.size)]}"
        canvas.drawText(text, MARGIN, baseline, headingPaint)
        sentenceStart = true
        return baseline + 4f
    }

    /** Draws up to [lines] lines; returns the top of the next block. Always advances. */
    private fun drawParagraph(canvas: Canvas, random: Random, top: Float, lines: Int): Float {
        var baseline = top + LINE_HEIGHT
        var drawn = 0
        while (drawn < lines && baseline <= CONTENT_BOTTOM) {
            val indent = if (drawn == 0) PARAGRAPH_INDENT else 0f
            val isLast = drawn == lines - 1
            val maxWidth = if (isLast) TEXT_WIDTH * (0.3f + random.nextFloat() * 0.55f) else TEXT_WIDTH - indent
            fillLine(random, maxWidth)
            if (isLast) {
                val end = line.length - 1
                if (end >= 0 && (line[end] == '.' || line[end] == ',')) line.setLength(end)
                line.append('.')
                sentenceStart = true
            }
            canvas.drawText(line.toString(), MARGIN + indent, baseline, bodyPaint)
            baseline += LINE_HEIGHT
            drawn++
        }
        return baseline - LINE_HEIGHT + PARAGRAPH_GAP
    }

    /** Fills [line] with random words up to [maxWidth] (a trailing period may overhang slightly). */
    private fun fillLine(random: Random, maxWidth: Float) {
        line.setLength(0)
        var width = 0f
        while (true) {
            val word = random.nextInt(WORDS.size)
            val added = if (line.isEmpty()) wordWidths[word] else spaceWidth + wordWidths[word]
            if (width + added > maxWidth) break
            if (line.isNotEmpty()) line.append(' ')
            val text = WORDS[word]
            if (sentenceStart) {
                line.append(text[0].uppercaseChar()).append(text.substring(1))
                sentenceStart = false
            } else {
                line.append(text)
            }
            width += added
            if (random.nextInt(11) == 0) {
                line.append(if (random.nextBoolean()) '.' else ',')
                sentenceStart = line[line.length - 1] == '.'
            }
        }
    }

    private fun drawFigure(canvas: Canvas, random: Random, top: Float, pageIndex: Int, number: Int): Float {
        val left = MARGIN + 24f
        val right = PAGE_WIDTH - MARGIN - 24f
        val boxTop = top + 10f
        val bottom = boxTop + FIGURE_MIN_HEIGHT + random.nextInt(FIGURE_EXTRA_HEIGHT)
        canvas.drawRect(left, boxTop, right, bottom, rulePaint)

        val originX = left + 30f
        val originY = bottom - 20f
        val plotTop = boxTop + 12f
        val plotRight = right - 14f
        val plotHeight = originY - plotTop
        canvas.drawLine(originX, plotTop, originX, originY, axisPaint)
        canvas.drawLine(originX, originY, plotRight, originY, axisPaint)
        for (tick in 1..4) {
            val ty = originY - plotHeight * tick / 4f
            canvas.drawLine(originX - 3f, ty, originX, ty, axisPaint)
        }

        when (random.nextInt(3)) {
            0 -> {
                val bars = 5 + random.nextInt(8)
                val slot = (plotRight - originX) / bars
                for (bar in 0 until bars) {
                    val height = plotHeight * (0.15f + random.nextFloat() * 0.8f)
                    val barLeft = originX + bar * slot + slot * 0.2f
                    canvas.drawRect(barLeft, originY - height, barLeft + slot * 0.6f, originY, fillPaint)
                }
            }
            1 -> {
                val steps = 24
                val dx = (plotRight - originX) / steps
                var px = originX
                var py = originY - plotHeight * random.nextFloat()
                for (step in 1..steps) {
                    val nx = originX + step * dx
                    val ny = (py + (random.nextFloat() - 0.45f) * 36f).coerceIn(plotTop, originY)
                    canvas.drawLine(px, py, nx, ny, plotPaint)
                    px = nx
                    py = ny
                }
            }
            else -> {
                repeat(40) {
                    val sx = originX + 4f + random.nextFloat() * (plotRight - originX - 8f)
                    val sy = plotTop + 4f + random.nextFloat() * (plotHeight - 8f)
                    canvas.drawRect(sx - 1.5f, sy - 1.5f, sx + 1.5f, sy + 1.5f, fillPaint)
                }
                canvas.drawLine(originX, originY - 10f, plotRight, plotTop + 10f, plotPaint)
            }
        }

        val caption = buildString {
            append("Figure ").append(pageIndex + 1).append('.').append(number).append(": ")
            repeat(5 + random.nextInt(6)) { i ->
                if (i > 0) append(' ')
                append(WORDS[random.nextInt(WORDS.size)])
            }
            append('.')
        }
        canvas.drawText(caption, left, bottom + 14f, captionPaint)
        sentenceStart = true
        return bottom + 24f
    }

    private fun drawCentered(canvas: Canvas, text: String, paint: Paint, baseline: Float) {
        canvas.drawText(text, (PAGE_WIDTH - paint.measureText(text)) / 2f, baseline, paint)
    }
}

private fun textPaint(size: Float, face: Typeface, textColor: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        color = textColor
    }

private fun strokePaint(width: Float, strokeColor: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = strokeColor
    }

// A4 in PostScript points.
private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 56f
private const val TEXT_WIDTH = 483f // PAGE_WIDTH - 2 * MARGIN
private const val CONTENT_TOP = 52f
private const val CONTENT_BOTTOM = 790f
private const val BODY_TEXT_SIZE = 10.5f
private const val LINE_HEIGHT = 14f
private const val PARAGRAPH_GAP = 7f
private const val PARAGRAPH_INDENT = 14f
private const val HEADING_SPACE = 22f
private const val FIGURE_MIN_HEIGHT = 110f
private const val FIGURE_EXTRA_HEIGHT = 70
private const val FIGURE_MAX_SPACE = 214f // 10 + FIGURE_MIN_HEIGHT + FIGURE_EXTRA_HEIGHT + 24

private val WORDS = arrayOf(
    "the", "of", "and", "a", "in", "to", "is", "for", "that", "with", "we", "as", "by", "on", "this",
    "are", "be", "from", "which", "our", "these", "each", "all", "can", "may", "not", "also", "between",
    "under", "over", "where", "when", "model", "data", "results", "method", "analysis", "approach",
    "system", "parameters", "equation", "boundary", "solution", "numerical", "estimate", "error",
    "inverse", "problem", "regularization", "matrix", "operator", "domain", "linear", "nonlinear",
    "stability", "convergence", "sample", "measurement", "noise", "signal", "function", "distribution",
    "variance", "posterior", "prior", "likelihood", "gradient", "iteration", "residual", "norm", "space",
    "finite", "element", "discretization", "mesh", "time", "step", "diffusion", "transport", "flow",
    "field", "observed", "proposed", "significant", "respectively", "however", "therefore", "moreover",
    "section", "figure", "table", "shown", "obtained", "compared", "reference", "experiment",
    "simulation", "accuracy", "performance", "robust", "efficient", "framework", "condition",
    "constraint", "optimal", "Bayesian", "Gaussian", "Fourier", "spectral", "kernel", "reconstruction",
    "inversion", "uncertainty", "quantification", "sensitivity", "coefficient", "physical", "empirical",
    "theoretical", "consistent", "estimator", "algorithm", "computational", "cost", "scale", "order",
    "higher", "lower", "adjoint", "forward", "sparse", "dense", "preconditioner", "eigenvalue",
)

private val HEADINGS = arrayOf(
    "Introduction", "Related work", "Problem formulation", "Numerical method", "Discretization",
    "Regularization strategy", "Experimental setup", "Results", "Discussion", "Sensitivity analysis",
    "Uncertainty quantification", "Limitations", "Implementation details", "Convergence analysis",
    "Conclusions",
)

private val SUBTITLES = arrayOf(
    "A synthetic document for reader benchmarks",
    "Regularized inversion of diffusion models",
    "Spectral methods for sparse reconstruction",
    "Uncertainty quantification in transport problems",
)

private val AUTHORS = arrayOf(
    "A. Author, B. Author and C. Author",
    "D. Author and E. Author",
    "F. Author, G. Author, H. Author and I. Author",
)

private val AFFILIATIONS = arrayOf(
    "Institute of Computational Modelling",
    "Department of Applied Mathematics",
    "Laboratory of Scientific Computing",
)

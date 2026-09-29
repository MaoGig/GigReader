package com.maogig.gigreader.feature.reader.viewport

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.common.render.DocumentLayout
import com.maogig.gigreader.core.common.render.PagePosition
import com.maogig.gigreader.core.common.render.RenderPlanner
import com.maogig.gigreader.core.common.render.ViewportTransform
import com.maogig.gigreader.core.model.ReaderPageBackground
import com.maogig.gigreader.core.pdf.render.RenderPipeline
import com.maogig.gigreader.feature.reader.nav.LinkHits
import com.maogig.gigreader.feature.reader.nav.LinkRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val KEY_ZOOM_STEP = 1.25f
private const val MAX_ANIMATED_JUMP_SCREENS = 3f

/** A tap this close (dp) to a link box still hits it: fingers are bigger than link rectangles. */
private const val LINK_TAP_SLOP_DP = 10f

private data class PlanInputs(
    val layout: DocumentLayout?,
    val transform: ViewportTransform,
    val width: Float,
    val height: Float,
    val zooming: Boolean,
)

/**
 * Imperative motion for a [PdfViewportState]: fling, animated zoom and animated page jumps, used by
 * gestures and by toolbar actions. Only one motion runs at a time; a new touch cancels it.
 * Holds no snapshot state.
 */
class ViewportController internal constructor(
    private val state: PdfViewportState,
    private val scope: CoroutineScope,
    private val decay: DecayAnimationSpec<Float>,
) {
    internal var animationsEnabled: Boolean = true
    private var job: Job? = null

    internal fun cancel() {
        job?.cancel()
        job = null
    }

    /** Zooms around [focus] (screen px), or around the viewport center when `null`. */
    fun zoomTo(target: Float, focus: Offset? = null) {
        cancel()
        // Clamped up front so an animation never spends frames pinned at the limit.
        val clamped = target.coerceIn(state.math.minZoom, state.math.maxZoom)
        val start = state.transform
        val fx = focus?.x ?: (state.viewportWidth / 2f)
        val fy = focus?.y ?: (state.viewportHeight / 2f)
        if (!animationsEnabled) {
            state.onZoomStart()
            state.zoomFrom(start, clamped, fx, fy)
            state.onZoomSettled()
            return
        }
        job = scope.launch {
            state.onZoomStart()
            try {
                animate(start.zoom, clamped, animationSpec = tween(durationMillis = 220)) { value, _ ->
                    state.zoomFrom(start, value, fx, fy)
                }
            } finally {
                state.onZoomSettled()
            }
        }
    }

    fun goToPage(page: Int) = goToPosition(PagePosition(page, 0f))

    /** Scrolls so that [position] (page + fraction of it) is at the top edge; animated when it is near. */
    fun goToPosition(position: PagePosition) {
        cancel()
        val target = state.offsetForPosition(position) ?: return
        val start = state.transform.offsetY
        // Long jumps are instant: animating across many pages would plan (and start rendering)
        // every page flown over, only to throw that work away a frame later.
        val screen = state.viewportHeight / state.transform.zoom
        if (!animationsEnabled || abs(target - start) > screen * MAX_ANIMATED_JUMP_SCREENS) {
            state.scrollToOffsetY(target)
            return
        }
        job = scope.launch {
            animate(start, target, animationSpec = tween(durationMillis = 200)) { value, _ -> state.scrollToOffsetY(value) }
        }
    }

    internal fun fling(vx: Float, vy: Float) {
        val speed = hypot(vx, vy)
        if (speed < 50f) return
        cancel()
        job = scope.launch {
            var last = 0f
            AnimationState(initialValue = 0f, initialVelocity = speed).animateDecay(decay) {
                val delta = value - last
                last = value
                // The first frame is at play time 0 (delta == 0): it must not count as "hit an edge".
                // Afterwards, stop as soon as an edge is reached: no frames are produced for nothing.
                if (delta != 0f && !state.panBy(delta * vx / speed, delta * vy / speed)) cancelAnimation()
            }
        }
    }
}

@Composable
fun rememberViewportController(state: PdfViewportState, animationsEnabled: Boolean): ViewportController {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val controller = remember(state) { ViewportController(state, scope, decay) }
    controller.animationsEnabled = animationsEnabled
    return controller
}

/**
 * The document viewport: one Canvas drawing only visible pages/tiles, custom gestures (pan, fling,
 * pinch, double tap, mouse wheel, keyboard) and render planning driven by snapshot changes.
 * Scrolling and zooming never recompose this composable; they only invalidate its draw.
 */
@Composable
fun PdfViewport(
    state: PdfViewportState,
    controller: ViewportController,
    pageSizes: PageSizes,
    pipeline: RenderPipeline,
    planner: RenderPlanner,
    background: ReaderPageBackground,
    pageGap: Dp,
    maxContentWidth: Dp,
    pageDescription: (page: Int, pageCount: Int) -> String,
    nextPageLabel: String,
    previousPageLabel: String,
    toggleControlsLabel: String,
    onTap: () -> Unit,
    onPositionChanged: (ViewportPosition) -> Unit,
    modifier: Modifier = Modifier,
    linkRegions: (page: Int) -> List<LinkRegion>? = { null },
    onLinkTap: (LinkRegion) -> Unit = {},
    onVisiblePagesChanged: (IntRange) -> Unit = {},
    onUserScroll: () -> Unit = {},
) {
    val density = LocalDensity.current
    val motion = controller
    val drawer = remember { PageDrawer() }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentLinkRegions by rememberUpdatedState(linkRegions)
    val currentOnLinkTap by rememberUpdatedState(onLinkTap)
    val currentOnVisiblePages by rememberUpdatedState(onVisiblePagesChanged)
    val currentOnUserScroll by rememberUpdatedState(onUserScroll)
    val focusRequester = remember { FocusRequester() }
    // Snapshot view of the pipeline's version: read only inside drawBehind, so a finished render
    // invalidates the draw phase and nothing else.
    val renderVersion = pipeline.version.collectAsState()

    val pageCount = pageSizes.count
    val currentPage by remember(state) { derivedStateOf { state.currentPage } }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val gapPx = with(density) { pageGap.toPx() }
        val maxContentPx = with(density) { maxContentWidth.toPx() }
        // Readable line length on wide screens: pages are centered instead of filling a tablet.
        val horizontalPadding = max(gapPx, (widthPx - maxContentPx) / 2f)
        val layout = remember(pageSizes, widthPx, gapPx, horizontalPadding) {
            if (widthPx <= 0f) null else DocumentLayout.build(pageSizes.widths, pageSizes.heights, widthPx, horizontalPadding, gapPx)
        }
        LaunchedEffect(layout, heightPx) {
            if (layout != null && heightPx > 0f) state.updateLayout(layout, widthPx, heightPx)
        }

        // Render planning + position reporting: runs only when the transform/layout changes.
        LaunchedEffect(state, pipeline, planner) {
            var lastVisible: IntRange? = null
            snapshotFlow { PlanInputs(state.layout, state.transform, state.viewportWidth, state.viewportHeight, state.isZooming) }
                .distinctUntilChanged()
                .collect { inputs ->
                    val l = inputs.layout ?: return@collect
                    if (inputs.width <= 0f || inputs.height <= 0f) return@collect
                    pipeline.request(planner.plan(l, inputs.transform, inputs.width, inputs.height, includeTiles = !inputs.zooming))
                    currentOnPositionChanged(state.position())
                    // Links are fetched only for pages on screen: report the range when it changes.
                    val t = inputs.transform
                    val visible = l.pagesIn(t.offsetY, t.offsetY + inputs.height / t.zoom)
                    if (visible != lastVisible) {
                        lastVisible = visible
                        currentOnVisiblePages(visible)
                    }
                }
        }

        val description = pageDescription(currentPage + 1, pageCount)
        Box(
            Modifier
                .fillMaxSize()
                .testTag("reader_viewport")
                .semantics {
                    contentDescription = description
                    // What a tap does (show/hide the reader chrome), so TalkBack users can bring the
                    // hidden controls back.
                    onClick(label = toggleControlsLabel) {
                        currentOnTap()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(nextPageLabel) {
                            motion.goToPage((state.currentPage + 1).coerceAtMost(pageCount - 1))
                            true
                        },
                        CustomAccessibilityAction(previousPageLabel) {
                            motion.goToPage((state.currentPage - 1).coerceAtLeast(0))
                            true
                        },
                    )
                }
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event -> handleKey(event, state, motion, pageCount).also { if (it) currentOnUserScroll() } }
                // Taps: a tap on a link follows it, any other single tap toggles the reader chrome;
                // double tap toggles fit ↔ zoomed (also over a link).
                .pointerInput(state) {
                    val slopPx = LINK_TAP_SLOP_DP.dp.toPx()
                    detectTapGestures(
                        onTap = { offset ->
                            val link = linkAt(state, offset, slopPx, currentLinkRegions)
                            if (link != null) currentOnLinkTap(link) else currentOnTap()
                        },
                        onDoubleTap = { offset ->
                            val target = if (state.transform.zoom < 1.5f) DOUBLE_TAP_ZOOM else 1f
                            motion.zoomTo(target, offset)
                        },
                    )
                }
                // Mouse wheel / trackpad scroll (no button pressed, so it never reaches awaitFirstDown).
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val scroll = change.scrollDelta
                            // The wheel takes over from a running fling or animated jump/zoom.
                            motion.cancel()
                            currentOnUserScroll()
                            if (event.keyboardModifiers.isPointerCtrlPressed) {
                                state.onZoomStart()
                                state.zoomBy(if (scroll.y < 0f) 1.1f else 1f / 1.1f, change.position.x, change.position.y)
                                state.onZoomSettled()
                            } else {
                                val step = 64.dp.toPx()
                                state.panBy(-scroll.x * step, -scroll.y * step)
                            }
                            change.consume()
                        }
                    }
                }
                // Pan, fling and pinch in one detector so they never fight each other.
                .pointerInput(state) {
                    val touchSlop = viewConfiguration.touchSlop
                    val maxFlingVelocity = viewConfiguration.maximumFlingVelocity
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        motion.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var pastSlop = false
                        var zoomAccumulated = 1f
                        var panAccumulated = Offset.Zero
                        var zoomedThisGesture = false
                        var maxPointers = 1
                        var reportedScroll = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) break
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            maxPointers = max(maxPointers, pressed)
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastSlop) {
                                zoomAccumulated *= zoomChange
                                panAccumulated += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1f - zoomAccumulated) * centroidSize
                                if (zoomMotion > touchSlop || panAccumulated.getDistance() > touchSlop) pastSlop = true
                            }
                            if (pastSlop) {
                                if (!reportedScroll) {
                                    reportedScroll = true
                                    currentOnUserScroll()
                                }
                                if (zoomChange != 1f && pressed > 1) {
                                    if (!zoomedThisGesture) state.onZoomStart()
                                    zoomedThisGesture = true
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    state.zoomBy(zoomChange, centroid.x, centroid.y)
                                }
                                if (panChange != Offset.Zero) state.panBy(panChange.x, panChange.y)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        }
                        if (zoomedThisGesture) state.onZoomSettled()
                        if (pastSlop && maxPointers == 1) {
                            val velocity = tracker.calculateVelocity()
                            motion.fling(
                                velocity.x.coerceIn(-maxFlingVelocity, maxFlingVelocity),
                                velocity.y.coerceIn(-maxFlingVelocity, maxFlingVelocity),
                            )
                        }
                    }
                }
                .drawBehind {
                    // Applied here, not in composition: the lambda captures [background], so a change
                    // replaces it and invalidates this draw (a composition-time paint change would not).
                    drawer.setBackground(background)
                    val l = state.layout ?: return@drawBehind
                    // Reading these states here (draw phase) invalidates only drawing.
                    val t = state.transform
                    val bucket = state.tileBucket
                    renderVersion.value
                    drawIntoCanvas { canvas ->
                        drawer.draw(canvas.nativeCanvas, l, t, state.viewportWidth, state.viewportHeight, pipeline, planner, bucket)
                    }
                },
        )
        LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }
    }
}

/** The link under a tap at [tap] (screen px), from the already fetched regions of that page, or `null`. */
private fun linkAt(
    state: PdfViewportState,
    tap: Offset,
    slopPx: Float,
    regionsOf: (Int) -> List<LinkRegion>?,
): LinkRegion? {
    val layout = state.layout ?: return null
    val t = state.transform
    val hit = LinkHits.pageHit(layout, t, tap.x, tap.y) ?: return null
    val regions = regionsOf(hit.page)?.takeIf { it.isNotEmpty() } ?: return null
    val slopX = slopPx / (layout.contentWidth * t.zoom)
    val slopY = slopPx / (layout.pageHeight(hit.page) * t.zoom)
    return LinkHits.find(regions, hit.x, hit.y, slopX, slopY)
}

private fun handleKey(event: KeyEvent, state: PdfViewportState, motion: ViewportController, pageCount: Int): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val page = state.currentPage
    val screen = state.viewportHeight * 0.9f
    val center = Offset(state.viewportWidth / 2f, state.viewportHeight / 2f)
    return when {
        event.key == Key.PageDown || event.key == Key.Spacebar -> state.panBy(0f, -screen).let { true }
        event.key == Key.PageUp -> state.panBy(0f, screen).let { true }
        event.key == Key.DirectionDown -> state.panBy(0f, -screen / 8f).let { true }
        event.key == Key.DirectionUp -> state.panBy(0f, screen / 8f).let { true }
        event.key == Key.DirectionRight -> motion.goToPage((page + 1).coerceAtMost(pageCount - 1)).let { true }
        event.key == Key.DirectionLeft -> motion.goToPage((page - 1).coerceAtLeast(0)).let { true }
        event.key == Key.MoveHome -> motion.goToPage(0).let { true }
        event.key == Key.MoveEnd -> motion.goToPage(pageCount - 1).let { true }
        event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) ->
            motion.zoomTo(state.transform.zoom * KEY_ZOOM_STEP, center).let { true }
        event.isCtrlPressed && (event.key == Key.Minus || event.key == Key.NumPadSubtract) ->
            motion.zoomTo(state.transform.zoom / KEY_ZOOM_STEP, center).let { true }
        event.isCtrlPressed && event.key == Key.Zero -> motion.zoomTo(1f, center).let { true }
        else -> false
    }
}

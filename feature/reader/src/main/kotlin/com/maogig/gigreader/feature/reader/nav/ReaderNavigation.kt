package com.maogig.gigreader.feature.reader.nav

import androidx.compose.runtime.Immutable
import com.maogig.gigreader.core.common.render.PagePosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class HintKind {
    /** "Back to p. X": the position left behind by the last jump. */
    BACK,

    /** "Forward to p. Y": shown after going back. */
    FORWARD,
}

/** The transient "Back to p. X" / "Forward to p. Y" affordance. [id] changes with every new hint. */
@Immutable
data class NavigationHint(val kind: HintKind, val target: PagePosition, val id: Int)

@Immutable
data class NavigationUi(
    val hint: NavigationHint? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/**
 * Jump history plus the lifetime rules of the transient hint. No timers: the hint disappears when
 * it is used, when a newer one replaces it, or once the user has scrolled by hand more than
 * [DISMISS_DISTANCE_PAGES] away from where the jump arrived (position updates only; nothing runs
 * while the reader is idle). Main thread only.
 */
class ReaderNavigation(private val history: NavigationHistory = NavigationHistory()) {
    private val _ui = MutableStateFlow(NavigationUi())
    val ui: StateFlow<NavigationUi> = _ui.asStateFlow()

    private var current = PagePosition(0, 0f)
    private var arrival = 0f
    private var userMoved = false
    private var nextHintId = 0

    /** The reader's position changed (reported by the viewport for every transform change). */
    fun onPosition(top: PagePosition) {
        current = top
        if (_ui.value.hint == null) return
        // The animation of a jump also reports positions: only a user gesture makes "far away" mean
        // "the user left".
        if (userMoved && abs(fractionalPage(top) - arrival) >= DISMISS_DISTANCE_PAGES) dismissHint()
    }

    /** The user scrolled, flung, used the wheel/keys or the page buttons (not a programmatic jump). */
    fun onUserScroll() {
        userMoved = true
    }

    /** Call right before moving to [to] because of a jump; records where the reader is now. */
    fun recordJump(to: PagePosition) {
        if (!history.recordJump(current, to)) return
        show(HintKind.BACK, current, arrival = to)
    }

    /** Position to go back to (the caller scrolls there without recording), or `null`. */
    fun back(): PagePosition? {
        val target = history.back(current) ?: return null
        afterMove(target, HintKind.FORWARD, history.forwardTarget)
        return target
    }

    /** Position to go forward to (the caller scrolls there without recording), or `null`. */
    fun forward(): PagePosition? {
        val target = history.forward(current) ?: return null
        afterMove(target, HintKind.BACK, history.backTarget)
        return target
    }

    fun dismissHint() {
        if (_ui.value.hint != null) _ui.value = _ui.value.copy(hint = null)
    }

    /** Forgets everything (a different document layout makes old positions meaningless). */
    fun clear() {
        history.clear()
        _ui.value = NavigationUi()
    }

    private fun afterMove(arrivedAt: PagePosition, kind: HintKind, hintTarget: PagePosition?) {
        if (hintTarget == null) {
            arrival = fractionalPage(arrivedAt)
            userMoved = false
            _ui.value = publish(hint = null)
        } else {
            show(kind, hintTarget, arrival = arrivedAt)
        }
    }

    private fun show(kind: HintKind, target: PagePosition, arrival: PagePosition) {
        this.arrival = fractionalPage(arrival)
        userMoved = false
        _ui.value = publish(NavigationHint(kind, target, nextHintId++))
    }

    private fun publish(hint: NavigationHint?) =
        NavigationUi(hint = hint, canGoBack = history.canGoBack, canGoForward = history.canGoForward)

    private fun fractionalPage(p: PagePosition): Float = p.page + p.pageOffset

    companion object {
        const val DISMISS_DISTANCE_PAGES = 1f
    }
}

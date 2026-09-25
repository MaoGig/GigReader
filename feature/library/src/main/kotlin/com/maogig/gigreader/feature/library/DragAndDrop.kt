package com.maogig.gigreader.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.model.LibraryItem
import kotlin.math.roundToInt

/**
 * In-app drag and drop of library items (plan §11), built on pointer input only.
 *
 * - Snapshot state is limited to what the UI must react to: [active], [count], [hovered] (changes
 *   only when the pointer enters/leaves a target) and [pointer] (read exclusively inside
 *   layout/draw lambdas by the preview, so moving the finger never recomposes the list).
 * - Drop target coordinates live in a plain map (no snapshot writes during scroll) and are
 *   hit-tested lazily with [LayoutCoordinates.localBoundingBoxOf] against the screen root.
 */
@Stable
internal class DragDropState(
    private val canDrop: (DropTarget) -> Boolean,
    private val onDrop: (DropTarget) -> Unit,
) {
    var active: Boolean by mutableStateOf(false)
        private set
    var count: Int by mutableIntStateOf(0)
        private set
    var hovered: DropTarget? by mutableStateOf(null)
        private set

    /** Pointer position in screen-root coordinates. Read it only in layout/draw lambdas. */
    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Coordinates of the screen root; every position here is relative to it. */
    var rootCoordinates: LayoutCoordinates? = null

    private val targets = HashMap<DropTarget, LayoutCoordinates>()
    private var armed = false
    private var start = Offset.Zero
    private var slop = 0f

    fun register(target: DropTarget, coordinates: LayoutCoordinates) {
        if (targets[target] !== coordinates) targets[target] = coordinates
    }

    fun unregister(target: DropTarget) {
        targets.remove(target)
    }

    /**
     * Long press detected on an item. The drag only becomes visible once the pointer moves past the
     * touch slop, so a long press without movement just enters selection mode.
     */
    fun arm(source: LayoutCoordinates, localPosition: Offset, itemCount: Int, touchSlop: Float) {
        val root = rootCoordinates ?: return
        if (!source.isAttached || !root.isAttached) return
        start = root.localPositionOf(source, localPosition)
        slop = touchSlop
        count = itemCount
        armed = true
    }

    fun moveTo(source: LayoutCoordinates, localPosition: Offset) {
        if (!armed) return
        val root = rootCoordinates ?: return
        if (!source.isAttached || !root.isAttached) return
        val position = root.localPositionOf(source, localPosition)
        if (!active) {
            if ((position - start).getDistance() < slop) return
            active = true
        }
        pointer = position
        val target = hitTest(root, position)
        if (target != hovered) hovered = target
    }

    fun release() {
        val target = hovered
        val wasActive = active
        reset()
        if (wasActive && target != null) onDrop(target)
    }

    fun cancel() = reset()

    private fun reset() {
        armed = false
        active = false
        hovered = null
    }

    private fun hitTest(root: LayoutCoordinates, position: Offset): DropTarget? {
        // The favorites zone floats above the grid, so it wins over folders underneath it.
        targets[DropTarget.Favorites]?.let { zone ->
            if (zone.isAttached && root.localBoundingBoxOf(zone).contains(position) && canDrop(DropTarget.Favorites)) {
                return DropTarget.Favorites
            }
        }
        for ((target, coordinates) in targets) {
            if (target == DropTarget.Favorites || !coordinates.isAttached) continue
            if (root.localBoundingBoxOf(coordinates).contains(position) && canDrop(target)) return target
        }
        return null
    }
}

/** Non-snapshot holder for an item's coordinates (written on every placement, never observed). */
internal class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}

/** Callbacks shared by every item of a screen; created once per ViewModel. */
@Stable
internal class LibraryItemCallbacks(
    val onClick: (LibraryItem) -> Unit,
    val onLongClick: (ItemRef) -> Unit,
    val onAction: (LibraryItem, ItemAction) -> Unit,
    val onDragStart: (ItemRef) -> Int,
)

/**
 * Click, long press (selection), long press + drag (move) and mouse right click (context menu) for
 * one library item.
 *
 * Order matters: the right-click detector runs on the Initial pass (outermost) and consumes the
 * secondary press so the click handler never sees it; the drag detector is innermost so, once a
 * drag started, it consumes moves before the clickable and the scrolling grid see them. Before the
 * long press nothing is consumed, so normal scrolling is unaffected.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.libraryItemInteraction(
    item: LibraryItem,
    ref: ItemRef,
    clickLabel: String,
    longClickLabel: String,
    holder: CoordinatesHolder,
    dragDrop: DragDropState,
    callbacks: LibraryItemCallbacks,
    onSecondaryClick: () -> Unit,
): Modifier = this
    .onGloballyPositioned { holder.coordinates = it }
    .pointerInput(ref) {
        awaitEachGesture {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onSecondaryClick()
            }
        }
    }
    .combinedClickable(
        onClickLabel = clickLabel,
        onLongClickLabel = longClickLabel,
        onLongClick = { callbacks.onLongClick(ref) },
        onClick = { callbacks.onClick(item) },
    )
    .pointerInput(ref, dragDrop, callbacks) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val dragged = callbacks.onDragStart(ref)
                holder.coordinates?.let { dragDrop.arm(it, offset, dragged, viewConfiguration.touchSlop) }
            },
            onDragEnd = { dragDrop.release() },
            onDragCancel = { dragDrop.cancel() },
            onDrag = { change, _ ->
                change.consume()
                holder.coordinates?.let { dragDrop.moveTo(it, change.position) }
            },
        )
    }

/** Registers the node as a drop target while it is composed and placed. */
internal fun Modifier.dropTarget(target: DropTarget, dragDrop: DragDropState): Modifier =
    onGloballyPositioned { dragDrop.register(target, it) }

/**
 * Translucent card following the pointer. Its position is read in the offset lambda (layout
 * phase), so moving the finger only re-places this card.
 */
@Composable
internal fun DragPreview(dragDrop: DragDropState, modifier: Modifier = Modifier) {
    if (!dragDrop.active) return
    val count = dragDrop.count
    Surface(
        modifier = modifier
            .offset {
                IntOffset(
                    x = (dragDrop.pointer.x - 24.dp.toPx()).roundToInt(),
                    y = (dragDrop.pointer.y - 64.dp.toPx()).roundToInt(),
                )
            }
            .graphicsLayer { alpha = 0.9f },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.library_drag_count, count, count),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

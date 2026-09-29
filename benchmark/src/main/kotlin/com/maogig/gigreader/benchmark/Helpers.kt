package com.maogig.gigreader.benchmark

import android.content.Intent
import android.content.res.Resources
import android.graphics.Point
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** applicationId of the app under test (the :app "benchmark" build type keeps it unchanged). */
const val TARGET_PACKAGE = "com.maogig.gigreader"

// Compose test tags published as resource ids (GigReaderApp sets testTagsAsResourceId = true), so
// By.res(tag) matches them without a package prefix.
const val TAG_LIBRARY_LIST = "library_list"
const val TAG_LIBRARY_DOCUMENT = "library_item_document"
const val TAG_READER_VIEWPORT = "reader_viewport"
const val TAG_READER_NEXT_PAGE = "reader_next_page"
const val TAG_READER_BACK = "reader_back"

// Trace sections emitted by the app (core/pdf PerfTrace, async sections on API 29+).
const val SECTION_OPEN_DOCUMENT = "GigReader.openDocument"
const val SECTION_FIRST_PAGE = "GigReader.firstPageRendered"
const val SECTION_CLOSE_DOCUMENT = "GigReader.closeDocument"
const val SECTION_RENDER_PAGE = "GigReader.renderPage"

const val STARTUP_ITERATIONS = 10
const val OPEN_CLOSE_ITERATIONS = 10
const val INTERACTION_ITERATIONS = 5

const val UI_TIMEOUT_MS = 10_000L

/** Generous on purpose: the first run writes a 1000-page PDF (or 60 small ones) and imports it. */
const val SEED_TIMEOUT_MS = 10 * 60_000L

/** Size of a pinch relative to the (margin-reduced) viewport. */
const val PINCH_PERCENT = 0.6f

// Seeding hook that exists only in the app's "benchmark" build type (app/src/benchmark).
private const val SEED_ACTIVITY = "com.maogig.gigreader.benchmark.SeedActivity"
private const val EXTRA_PAGES = "pages"
private const val EXTRA_TITLE = "title"
private const val EXTRA_COUNT = "count"
private const val EXTRA_OPEN = "open"
private const val TAG_SEED_FAILED = "seed_failed"
private val SEED_FINISHED: Pattern = Pattern.compile("seed_(done|failed)")
private val READER_OR_SEED_FAILURE: Pattern = Pattern.compile("reader_viewport|seed_failed")

/** UiAutomator's default fling speed (dp per second). */
private const val FLING_SPEED_DP_PER_SECOND = 7_500

/** Between Compose's double-tap minimum (40 ms) and timeout (300 ms) after the first tap's up. */
private const val DOUBLE_TAP_GAP_MS = 80L

/**
 * A synthetic document generated on the device by SeedActivity. Titles contain no spaces so they
 * survive the `am start` command line unquoted.
 */
data class SeedDoc(val title: String, val pages: Int)

object Docs {
    val SMALL = SeedDoc(title = "bench-small-10p", pages = 10)
    val LARGE = SeedDoc(title = "bench-large-1000p", pages = 1000)

    /** Prefix of the library filler documents: bench-lib-001 … bench-lib-060. */
    val LIBRARY = SeedDoc(title = "bench-lib", pages = 2)
    const val LIBRARY_COUNT = 60
}

fun seedIntent(doc: SeedDoc, open: Boolean, count: Int = 1): Intent =
    Intent()
        .setClassName(TARGET_PACKAGE, SEED_ACTIVITY)
        .putExtra(EXTRA_PAGES, doc.pages)
        .putExtra(EXTRA_TITLE, doc.title)
        .putExtra(EXTRA_COUNT, count)
        .putExtra(EXTRA_OPEN, open)

/**
 * Makes sure [doc] (or `count` numbered copies of it) is in the library. Idempotent and cheap once
 * seeded. Blocks until SeedActivity reports `seed_done` / `seed_failed`, then dismisses it.
 */
fun MacrobenchmarkScope.seedDocuments(doc: SeedDoc, count: Int = 1) {
    device.dismissStaleSeedScreens()
    startActivityAndWait(seedIntent(doc, open = false, count = count))
    val marker = device.wait(Until.findObject(By.res(SEED_FINISHED)), SEED_TIMEOUT_MS)
        ?: error("Seeding '${doc.title}' did not finish within $SEED_TIMEOUT_MS ms")
    val failed = marker.resourceName == TAG_SEED_FAILED
    marker.click() // dismisses the transparent seeding screen
    device.wait(Until.gone(By.res(SEED_FINISHED)), UI_TIMEOUT_MS)
    check(!failed) { "Seeding '${doc.title}' failed; see logcat tag GigReaderSeed" }
}

/** A seeding screen left over by an interrupted run would otherwise be mistaken for the new one. */
private fun UiDevice.dismissStaleSeedScreens() {
    repeat(3) {
        val stale = findObject(By.res(SEED_FINISHED)) ?: return
        stale.click()
        wait(Until.gone(By.res(SEED_FINISHED)), UI_TIMEOUT_MS)
    }
}

/**
 * Opens [doc] in the reader through SeedActivity (`open = true`): it seeds the document if needed,
 * resets its reading position to page 1 at "fit width" and sends MainActivity the benchmark-only
 * OPEN_DOCUMENT intent. The benchmark never needs to know the document id. Returns the viewport.
 */
fun MacrobenchmarkScope.openDocument(doc: SeedDoc): UiObject2 {
    startActivityAndWait(seedIntent(doc, open = true))
    val shown = device.wait(Until.findObject(By.res(READER_OR_SEED_FAILURE)), SEED_TIMEOUT_MS)
        ?: error("The reader did not show '${doc.title}' within $SEED_TIMEOUT_MS ms")
    if (shown.resourceName == TAG_SEED_FAILED) {
        shown.click()
        error("Seeding '${doc.title}' failed; see logcat tag GigReaderSeed")
    }
    return shown
}

/** Setup for reader scenarios: fresh process, [doc] open on its first page, first render settled. */
fun MacrobenchmarkScope.openDocumentFresh(doc: SeedDoc) {
    killProcess()
    openDocument(doc)
    device.waitForIdle()
}

/** Launches the app from the launcher intent and waits for the library list. */
fun MacrobenchmarkScope.startAtLibrary(): UiObject2 {
    startActivityAndWait()
    val list = waitForObject(TAG_LIBRARY_LIST)
    device.waitForIdle()
    return list
}

/**
 * Leaves the reader with its own back button. System Back is not a substitute: a reader opened
 * through the benchmark hook is alone on the back stack (GigReaderApp resets to it), so Back would
 * leave the app instead of returning to the library. If the chrome is hidden, one tap on the page
 * (which toggles it) brings the button back.
 */
fun MacrobenchmarkScope.closeReader() {
    val back = device.findObject(By.res(TAG_READER_BACK)) ?: revealReaderChrome()
    back.click()
    waitForObject(TAG_LIBRARY_LIST)
}

private fun MacrobenchmarkScope.revealReaderChrome(): UiObject2 {
    val center = waitForObject(TAG_READER_VIEWPORT).visibleCenter
    device.click(center.x, center.y)
    // The single tap is reported only after the double-tap timeout; waitForObject covers that.
    return waitForObject(TAG_READER_BACK)
}

fun MacrobenchmarkScope.waitForObject(tag: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
    device.wait(Until.findObject(By.res(tag)), timeoutMs)
        ?: error("'$tag' was not shown within $timeoutMs ms")

/**
 * Keeps gestures in the middle of the screen: away from the system gesture areas (edge swipes would
 * navigate) and from the reader's top and bottom bars, which sit on top of the viewport.
 */
fun UiObject2.keepGesturesAwayFromEdges(device: UiDevice) {
    val horizontal = device.displayWidth / 10
    val vertical = device.displayHeight / 4
    setGestureMargins(horizontal, vertical, horizontal, vertical)
}

/**
 * One fling on the reader canvas, then waits until the app is idle again. A swipe at fling speed is
 * used instead of [UiObject2.fling]: the canvas publishes no accessibility scroll events, so fling()
 * would always sit out its full timeout.
 */
fun UiObject2.flingReader(device: UiDevice, forward: Boolean = true) {
    val speed = (FLING_SPEED_DP_PER_SECOND * Resources.getSystem().displayMetrics.density).toInt()
    swipe(if (forward) Direction.UP else Direction.DOWN, 1f, speed)
    device.waitForIdle()
}

/** Two taps at [point] spaced to be recognized as a double tap by Compose's tap detector. */
fun UiDevice.doubleTap(point: Point) {
    click(point.x, point.y)
    SystemClock.sleep(DOUBLE_TAP_GAP_MS)
    click(point.x, point.y)
}

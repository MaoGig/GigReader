package com.maogig.gigreader.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile shipped with the app (docs/PERFORMANCE_AND_POWER.md §9.1): startup
 * (also written to the startup profile for DEX layout), then the critical journey: scroll the
 * library, open a document, scroll/zoom/turn a page, go back.
 *
 * Needs API 33+ (or a rooted API 28+ device). Rules that name benchmark-only classes (SeedActivity)
 * are harmless: those classes do not exist in release builds and are ignored.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startAtLibrary()
        }

    @Test
    fun readingJourney() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
            if (!seeded) {
                seedDocuments(Docs.SMALL)
                seedDocuments(Docs.LIBRARY, count = Docs.LIBRARY_COUNT)
                seeded = true
            }
            killProcess()

            val list = startAtLibrary()
            list.keepGesturesAwayFromEdges(device)
            list.fling(Direction.DOWN)
            list.fling(Direction.UP)

            // Open a document the way users do (tap a card); fall back to the seeding hook.
            val item = device.findObject(By.res(TAG_LIBRARY_DOCUMENT))
            val viewport = if (item != null) {
                item.click()
                waitForObject(TAG_READER_VIEWPORT)
            } else {
                openDocument(Docs.SMALL)
            }
            device.waitForIdle()

            viewport.keepGesturesAwayFromEdges(device)
            repeat(3) { viewport.flingReader(device) }
            viewport.flingReader(device, forward = false)
            viewport.pinchOpen(PINCH_PERCENT)
            device.waitForIdle()
            viewport.pinchClose(PINCH_PERCENT)
            device.waitForIdle()
            val center = viewport.visibleCenter
            device.doubleTap(center)
            device.waitForIdle()
            device.doubleTap(center)
            device.waitForIdle()
            device.findObject(By.res(TAG_READER_NEXT_PAGE))?.click()
            device.waitForIdle()

            closeReader()
        }

    private companion object {
        var seeded = false
    }
}

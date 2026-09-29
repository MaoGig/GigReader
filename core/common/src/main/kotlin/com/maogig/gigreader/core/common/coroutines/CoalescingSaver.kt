package com.maogig.gigreader.core.common.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Autosave primitive: keeps only the latest submitted value and writes it at most once per
 * [delayMillis] (throttle, not debounce, so continuous changes are still persisted regularly).
 *
 * This is how the app avoids "writing to storage on every event": page changes, zoom changes and
 * note keystrokes are submitted here, and [flush] is called when the screen stops (onStop) so
 * nothing is lost when the process dies afterwards. No timer runs while nothing is pending.
 */
class CoalescingSaver<T : Any>(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val onError: (Throwable) -> Unit = {},
    private val save: suspend (T) -> Unit,
) {
    private val lock = Any()
    private val saveMutex = Mutex()
    private var pending: T? = null
    private var scheduled: Job? = null

    val hasPending: Boolean get() = synchronized(lock) { pending != null }

    fun submit(value: T) {
        synchronized(lock) {
            pending = value
            if (scheduled?.isActive == true) return
            scheduled = scope.launch {
                delay(delayMillis)
                synchronized(lock) { scheduled = null }
                flush()
            }
        }
    }

    /** Writes the pending value now (if any). Safe to call concurrently with [submit]. */
    suspend fun flush() {
        saveMutex.withLock {
            val value = synchronized(lock) { pending.also { pending = null } } ?: return
            try {
                save(value)
            } catch (e: CancellationException) {
                requeue(value)
                throw e
            } catch (e: Exception) {
                // Keep the value so the next submit/flush retries it, unless something newer arrived.
                requeue(value)
                onError(e)
            }
        }
    }

    /** Drops any pending value and the scheduled write. */
    fun cancel() {
        synchronized(lock) {
            pending = null
            scheduled?.cancel()
            scheduled = null
        }
    }

    private fun requeue(value: T) {
        synchronized(lock) { if (pending == null) pending = value }
    }
}

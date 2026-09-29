package com.maogig.gigreader.core.pdf.render

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Registry of open [RenderPipeline]s, used by the debug diagnostics screen and by the
 * application-level `onTrimMemory` handler. Registration happens once per opened document.
 */
object RenderDiagnostics {
    private val pipelines = CopyOnWriteArraySet<RenderPipeline>()

    internal fun register(pipeline: RenderPipeline) {
        pipelines.add(pipeline)
    }

    internal fun unregister(pipeline: RenderPipeline) {
        pipelines.remove(pipeline)
    }

    fun snapshot(): List<RenderStats> = pipelines.map { it.stats() }

    /** Called on memory pressure: every pipeline keeps only what its current plan needs. */
    fun trimAll() {
        pipelines.forEach { it.trimToPlan() }
    }
}

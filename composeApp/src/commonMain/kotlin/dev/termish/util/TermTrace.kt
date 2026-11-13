package dev.termish.util

import kotlin.time.TimeSource

/**
 * Lightweight span trace: Drawing on OpenTelemetry’s span concept (name / attributes /
 * start-end / status / events), and eliminate the distributed overhead (export, sampling, propagation).
 *
 * Purpose: To identify where time is spent during slow operations such as connection and reconnect—specifically, which step is causing delays: DNS, TCP, KEX, or authentication.
 * Output via [TermLog]: when a span ends, print a one-line structured summary (total + per-step timings +
 * status), operations taking >10s are automatically upgraded to "w" level.
 *
 * Usage (Scope DSL, automatic begin/end):
 * ```
 * TermTrace.span("ssh.connect", tag = "ssh", "host" to name) {
 *     onTraceStep("tcp")   // Engine callback
 *     onTraceStep("kex")
 *     Automatically record FAILED when the block throws or is marked failed
 * }
 * ```
 * Nesting: Sub-`span` elements may be nested within a `span` (e.g., at the authentication method level).
 */
object TermTrace {
    const val SLOW_THRESHOLD_MS = 10_000L

    /** Span status. */
    enum class Status { OK, FAILED }

    /** Single-stage record: Name + elapsed time relative to the start of the span at that moment. */
    class Step(
        val name: String,
        val elapsedMs: Long,
    )

    class Span internal constructor(
        val name: String,
        private val tag: String,
        private val attrs: Map<String, String>,
        private val startMark: TimeSource.Monotonic.ValueTimeMark,
    ) {
        private val steps = mutableListOf<Step>()
        var status: Status = Status.OK
            private set
        var error: String? = null
            private set
        private var ended = false

        /** Record completion points for each phase (duration = relative to the start of the span). */
        fun step(step: String) {
            if (ended) return
            steps.add(Step(step, startMark.elapsedNow().inWholeMilliseconds))
        }

        /** Mark as failure and exit (output level E). */
        fun fail(msg: String?) {
            status = Status.FAILED
            error = msg
            end()
        }

        /** End and output a summary (automatically called by the scope DSL when not explicitly invoked). */
        fun end() {
            if (ended) return
            ended = true
            val total = startMark.elapsedNow().inWholeMilliseconds
            val stepsText = steps.joinToString(", ") { "${it.name} ${it.elapsedMs}ms" }
            val attrsText = attrs.entries.joinToString(" ") { "${it.key}=${it.value}" }
            val msg =
                "span[$name] total=${total}ms ${status.name.lowercase()}" +
                    (error?.let { "($it)" } ?: "") +
                    (if (steps.isEmpty()) "" else " steps=[$stepsText]") +
                    (if (attrs.isEmpty()) "" else " $attrsText")
            if (status == Status.FAILED || total >= SLOW_THRESHOLD_MS) {
                TermLog.w(tag) { msg }
            } else {
                TermLog.i(tag) { msg }
            }
        }
    }

    /** Enable span (manual mode, used with [Span.step]/[Span.end]; for scenarios spanning multiple functions). */
    fun begin(
        name: String,
        tag: String = "trace",
        vararg attrs: Pair<String, String>,
    ): Span = Span(name, tag, attrs.toMap(), TimeSource.Monotonic.markNow())

    /** Scope mode: Automatically use `end` at the end of a block; automatically use `fail` when an exception is thrown, followed by a rethrow. */
    inline fun span(
        name: String,
        tag: String = "trace",
        crossinline body: Span.() -> Unit,
        vararg attrs: Pair<String, String>,
    ) {
        val span = begin(name, tag, *attrs)
        try {
            body(span)
            span.end()
        } catch (e: Throwable) {
            span.fail(e.message)
            throw e
        }
    }
}

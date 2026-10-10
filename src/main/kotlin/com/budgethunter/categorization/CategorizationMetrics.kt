package com.budgethunter.categorization

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * The counters that say how automatic categorisation is doing, for Actuator (`/actuator/metrics/<name>`,
 * once `metrics` is exposed; see MONITORING.md). Tag values are fixed enums, so the number of series is small
 * and nothing about an entry, a budget or a user ever becomes a label.
 *
 * - `categorization.runs{outcome}`: requests to categorise a budget and how they ended.
 * - `categorization.entries{result}`: what happened to the entries a run looked at.
 * - `categorization.resolved{source}`: which layer answered a description (rules and cache are free; remote is
 *   the one that spends quota).
 * - `categorization.provider.requests{outcome}`: what Gemini did, or why it was not asked.
 *
 * Defaults to a private registry, so tests and callers that do not care need not provide one.
 */
class CategorizationMetrics(private val registry: MeterRegistry = SimpleMeterRegistry()) {

    enum class Run { COMPLETED, ALREADY_RUNNING, AI_OFF }

    /** categorized: stored. dropped: a person changed the entry first. unplaced: nobody had an answer. */
    enum class EntryResult { CATEGORIZED, DROPPED, UNPLACED }

    enum class Layer { RULES, CACHE, REMOTE }

    enum class Provider { OK, RATE_LIMITED, HTTP_ERROR, UNREACHABLE, INVALID_REPLY, BUDGET_EXHAUSTED, COOLING_DOWN }

    fun run(outcome: Run) = count("categorization.runs", "outcome", outcome.name, 1)

    fun entries(result: EntryResult, amount: Int) = count("categorization.entries", "result", result.name, amount)

    fun resolved(layer: Layer, amount: Int) = count("categorization.resolved", "source", layer.name, amount)

    fun provider(outcome: Provider) = count("categorization.provider.requests", "outcome", outcome.name, 1)

    private fun count(name: String, tag: String, value: String, amount: Int) {
        if (amount > 0) registry.counter(name, tag, value.lowercase()).increment(amount.toDouble())
    }
}

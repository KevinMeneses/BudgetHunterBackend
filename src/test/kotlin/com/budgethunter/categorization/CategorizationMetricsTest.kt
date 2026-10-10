package com.budgethunter.categorization

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CategorizationMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = CategorizationMetrics(registry)

    private fun count(name: String, tag: String, value: String) = registry.counter(name, tag, value).count()

    @Test
    fun `counts each family under its own name and a lower-case tag`() {
        metrics.run(CategorizationMetrics.Run.COMPLETED)
        metrics.entries(CategorizationMetrics.EntryResult.CATEGORIZED, 3)
        metrics.resolved(CategorizationMetrics.Layer.RULES, 2)
        metrics.provider(CategorizationMetrics.Provider.RATE_LIMITED)

        assertEquals(1.0, count("categorization.runs", "outcome", "completed"))
        assertEquals(3.0, count("categorization.entries", "result", "categorized"))
        assertEquals(2.0, count("categorization.resolved", "source", "rules"))
        assertEquals(1.0, count("categorization.provider.requests", "outcome", "rate_limited"))
    }

    @Test
    fun `adds up and does not create a series for nothing`() {
        metrics.entries(CategorizationMetrics.EntryResult.DROPPED, 2)
        metrics.entries(CategorizationMetrics.EntryResult.DROPPED, 5)
        metrics.entries(CategorizationMetrics.EntryResult.UNPLACED, 0)

        assertEquals(7.0, count("categorization.entries", "result", "dropped"))
        assertEquals(1, registry.meters.size, "a zero amount must not register a meter")
    }

    @Test
    fun `works without a registry being provided`() {
        CategorizationMetrics().run(CategorizationMetrics.Run.AI_OFF)
    }
}

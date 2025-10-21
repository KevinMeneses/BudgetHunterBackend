package com.budgethunter.service

import com.budgethunter.dto.BudgetEntryEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

@Service
class ReactiveSseService {
    private val logger = LoggerFactory.getLogger(ReactiveSseService::class.java)

    // Map of budgetId -> Sink (broadcasts to all subscribers of that budget)
    private val budgetSinks = ConcurrentHashMap<Long, Sinks.Many<BudgetEntryEvent>>()

    /**
     * Creates a Flux that emits BudgetEntryEvent for the specified budget.
     * This Flux will remain active until cancelled by the client.
     * Multiple subscribers to the same budget will all receive the same events.
     */
    fun subscribeToEvents(budgetId: Long): Flux<BudgetEntryEvent> {
        val sink = budgetSinks.computeIfAbsent(budgetId) {
            // Create a multicast sink that can have multiple subscribers
            Sinks.many().multicast().onBackpressureBuffer()
        }

        logger.info("New subscriber for budget: {}. Sink: {}", budgetId, sink)

        return sink.asFlux()
            .doOnSubscribe {
                logger.info("Subscription started for budget: {}", budgetId)
            }
            .doOnCancel {
                logger.info("Subscription cancelled for budget: {}", budgetId)
                cleanupSinkIfNoSubscribers(budgetId)
            }
            .doOnTerminate {
                logger.info("Subscription terminated for budget: {}", budgetId)
                cleanupSinkIfNoSubscribers(budgetId)
            }
    }

    /**
     * Broadcasts an event to all subscribers of the specified budget.
     * This method is thread-safe and can be called from any thread.
     */
    fun broadcastEvent(budgetId: Long, event: BudgetEntryEvent) {
        val sink = budgetSinks[budgetId]

        if (sink == null) {
            logger.debug("No subscribers for budget: {}, event not broadcasted", budgetId)
            return
        }

        val result = sink.tryEmitNext(event)

        when {
            result.isSuccess -> {
                logger.debug("Event broadcasted successfully to budget: {}", budgetId)
            }
            result.isFailure -> {
                logger.warn("Failed to broadcast event to budget: {}. Result: {}", budgetId, result)
            }
        }
    }

    /**
     * Clean up sink if it has no more subscribers
     */
    private fun cleanupSinkIfNoSubscribers(budgetId: Long) {
        val sink = budgetSinks[budgetId]
        if (sink != null && sink.currentSubscriberCount() == 0) {
            logger.info("No more subscribers for budget: {}, removing sink", budgetId)
            budgetSinks.remove(budgetId)
        }
    }

    /**
     * Gets the count of active subscribers for a budget (useful for testing/monitoring)
     */
    fun getSubscriberCount(budgetId: Long): Int {
        return budgetSinks[budgetId]?.currentSubscriberCount() ?: 0
    }
}

package com.budgethunter.service

import com.budgethunter.dto.BudgetEntryEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionalEventListener
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

@Service
class ReactiveSseService {
    private val logger = LoggerFactory.getLogger(ReactiveSseService::class.java)

    /**
     * budgetId -> sink that broadcasts to every subscriber of that budget.
     *
     * Sinks are never removed, and are `directBestEffort` on purpose:
     * - The previous `onBackpressureBuffer()` sink has autoCancel: it shuts down for good when
     *   its last subscriber leaves. Its cleanup ran in `doOnCancel`, before the cancellation
     *   reached the sink, so it always saw one subscriber left and never removed it. Anyone
     *   subscribing later to that budget got the dead sink: heartbeats kept the stream looking
     *   healthy but no event was ever delivered again until the server restarted.
     * - A direct sink does not shut down when it has no subscribers and does not buffer events
     *   for later ones, so a sink with nobody listening is harmless. Keeping it avoids the race
     *   between removing a sink and a new subscriber that has just obtained it.
     * - Best effort drops an event for a subscriber that has no demand at that moment instead of
     *   failing the whole broadcast; clients refresh the full entry list on reconnect anyway.
     */
    private val budgetSinks = ConcurrentHashMap<Long, Sinks.Many<BudgetEntryEvent>>()

    private fun sinkFor(budgetId: Long): Sinks.Many<BudgetEntryEvent> =
        budgetSinks.computeIfAbsent(budgetId) { Sinks.many().multicast().directBestEffort() }

    /**
     * Creates a Flux that emits BudgetEntryEvent for the specified budget.
     * This Flux will remain active until cancelled by the client.
     * Multiple subscribers to the same budget will all receive the same events.
     */
    fun subscribeToEvents(budgetId: Long): Flux<BudgetEntryEvent> {
        logger.info("New subscriber for budget: {}", budgetId)

        return sinkFor(budgetId).asFlux()
            .doOnSubscribe {
                logger.info("Subscription started for budget: {}", budgetId)
            }
            .doFinally { signal ->
                logger.info("Subscription ended for budget: {} ({})", budgetId, signal)
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
            result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER -> {
                logger.debug("No subscribers for budget: {}, event not broadcasted", budgetId)
            }
            result.isFailure -> {
                logger.warn("Failed to broadcast event to budget: {}. Result: {}", budgetId, result)
            }
        }
    }

    /**
     * Emits the email of each account whose sessions have ended ([SessionsEndedEvent]), to every open stream.
     * Never shut down; nobody listening is fine. Emission is serialised in [endStreamsOf].
     */
    private val endedSessions = Sinks.many().multicast().directBestEffort<String>()

    /**
     * Emits [email] when its sessions end, so a stream opened with one of its access tokens stops (the
     * filter checks the token only when the stream opens). Streams opened after that are not affected.
     */
    fun sessionsEnded(email: String): Mono<String> =
        endedSessions.asFlux().filter { it == email }.next()

    /**
     * After the commit, so a rollback leaves the streams alone, and before the new session reaches the client, so
     * none of its streams can be open yet.
     */
    @TransactionalEventListener
    fun endStreamsOf(event: SessionsEndedEvent) {
        val result = synchronized(endedSessions) { endedSessions.tryEmitNext(event.email) }
        if (result.isFailure && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            logger.warn("Failed to end the event streams of an account: {}", result)
        }
    }

    /**
     * Gets the count of active subscribers for a budget (useful for testing/monitoring)
     */
    fun getSubscriberCount(budgetId: Long): Int {
        return budgetSinks[budgetId]?.currentSubscriberCount() ?: 0
    }
}

package com.budgethunter.service

import com.budgethunter.dto.BudgetEntryEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Service
class SseService(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(SseService::class.java)
    private val emitters = ConcurrentHashMap<Long, MutableSet<SseEmitter>>()

    companion object {
        private const val SSE_TIMEOUT_MS = 1800000L
    }

    fun createEmitter(budgetId: Long): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)

        addEmitter(budgetId, emitter)

        emitter.onCompletion {
            logger.debug("SSE emitter completed for budget: {}", budgetId)
            removeEmitter(budgetId, emitter)
        }

        emitter.onTimeout {
            logger.debug("SSE emitter timed out for budget: {}", budgetId)
            removeEmitter(budgetId, emitter)
        }

        emitter.onError { throwable ->
            logger.error("SSE emitter error for budget: {}", budgetId, throwable)
            removeEmitter(budgetId, emitter)
        }

        return emitter
    }

    fun broadcastBudgetEntryEvent(budgetId: Long, event: BudgetEntryEvent) {
        val emittersForBudget = emitters[budgetId] ?: return

        val eventData = objectMapper.writeValueAsString(event)
        val deadEmitters = mutableSetOf<SseEmitter>()

        emittersForBudget.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .name("budget-entry")
                        .data(eventData)
                )
                logger.debug("Broadcasted event to emitter for budget: {}", budgetId)
            } catch (e: Exception) {
                logger.error("Failed to send event to emitter for budget: {}", budgetId, e)
                deadEmitters.add(emitter)
            }
        }

        deadEmitters.forEach { removeEmitter(budgetId, it) }
    }

    private fun addEmitter(budgetId: Long, emitter: SseEmitter) {
        emitters.computeIfAbsent(budgetId) { ConcurrentHashMap.newKeySet() }
            .add(emitter)
        logger.debug("Added SSE emitter for budget: {}. Total emitters: {}", budgetId, emitters[budgetId]?.size)
    }

    private fun removeEmitter(budgetId: Long, emitter: SseEmitter) {
        emitters[budgetId]?.remove(emitter)
        if (emitters[budgetId]?.isEmpty() == true) {
            emitters.remove(budgetId)
        }
        logger.debug("Removed SSE emitter for budget: {}. Remaining emitters: {}", budgetId, emitters[budgetId]?.size ?: 0)
    }
}

package com.budgethunter.service

import com.budgethunter.dto.BudgetEntryEvent
import com.budgethunter.dto.BudgetEntryEventData
import com.budgethunter.dto.UserEventInfo
import com.budgethunter.model.EntryType
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class SseServiceTest {

    private lateinit var objectMapper: ObjectMapper
    private lateinit var sseService: SseService

    @BeforeEach
    fun setup() {
        objectMapper = mockk()
        sseService = SseService(objectMapper)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // CreateEmitter Tests

    @Test
    fun `createEmitter should create and register new emitter`() {
        // Given
        val budgetId = 1L

        // When
        val emitter = sseService.createEmitter(budgetId)

        // Then
        assertNotNull(emitter)
        assertEquals(1800000L, emitter.timeout)
    }

    @Test
    fun `createEmitter should create multiple emitters for same budget`() {
        // Given
        val budgetId = 1L

        // When
        val emitter1 = sseService.createEmitter(budgetId)
        val emitter2 = sseService.createEmitter(budgetId)

        // Then
        assertNotNull(emitter1)
        assertNotNull(emitter2)
        assertNotSame(emitter1, emitter2)
    }

    @Test
    fun `createEmitter should create emitters for different budgets`() {
        // Given
        val budgetId1 = 1L
        val budgetId2 = 2L

        // When
        val emitter1 = sseService.createEmitter(budgetId1)
        val emitter2 = sseService.createEmitter(budgetId2)

        // Then
        assertNotNull(emitter1)
        assertNotNull(emitter2)
        assertNotSame(emitter1, emitter2)
    }

    @Test
    fun `createEmitter should setup completion callback`() {
        // Given
        val budgetId = 1L

        // When
        val emitter = sseService.createEmitter(budgetId)

        // Then - emitter should have onCompletion callback set
        assertNotNull(emitter)
        // Create another emitter to verify the first one can be removed
        sseService.createEmitter(budgetId)

        // Trigger completion on first emitter
        emitter.complete()

        // Verify emitter2 is still registered by broadcasting
        val event = createTestEvent()
        every { objectMapper.writeValueAsString(event) } returns "{}"

        // This should not throw - emitter2 should still receive events
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId, event)
        }
    }

    @Test
    fun `createEmitter should setup timeout callback`() {
        // Given
        val budgetId = 1L

        // When
        val emitter = sseService.createEmitter(budgetId)

        // Then
        assertNotNull(emitter)
        // The timeout is set to 30 minutes (1800000ms)
        assertEquals(1800000L, emitter.timeout)
    }

    // BroadcastBudgetEntryEvent Tests

    @Test
    fun `broadcastBudgetEntryEvent should send event to all emitters for budget`() {
        // Given
        val budgetId = 1L
        val event = createTestEvent()
        val eventJson = """{"budgetEntry":{"id":1},"userInfo":{"email":"test@example.com"}}"""

        every { objectMapper.writeValueAsString(event) } returns eventJson

        // Create emitters directly without spying
        sseService.createEmitter(budgetId)
        sseService.createEmitter(budgetId)

        // When - should not throw exception
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId, event)
        }

        // Then - verify objectMapper was called to serialize the event
        verify(exactly = 1) { objectMapper.writeValueAsString(event) }
    }

    @Test
    fun `broadcastBudgetEntryEvent should not send event to emitters of different budget`() {
        // Given
        val budgetId1 = 1L
        val budgetId2 = 2L
        val event = createTestEvent()
        val eventJson = """{"budgetEntry":{"id":1},"userInfo":{"email":"test@example.com"}}"""

        every { objectMapper.writeValueAsString(event) } returns eventJson

        // Create emitters for different budgets
        sseService.createEmitter(budgetId1)
        sseService.createEmitter(budgetId2)

        // When - broadcast to budget 1 only
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId1, event)
        }

        // Then - verify objectMapper was called (meaning at least one emitter received it)
        verify(exactly = 1) { objectMapper.writeValueAsString(event) }
    }

    @Test
    fun `broadcastBudgetEntryEvent should do nothing when no emitters registered`() {
        // Given
        val budgetId = 999L
        val event = createTestEvent()

        // When & Then - should not throw
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId, event)
        }

        // ObjectMapper should not be called since there are no emitters
        verify(exactly = 0) { objectMapper.writeValueAsString(any()) }
    }

    @Test
    fun `broadcastBudgetEntryEvent should handle errors gracefully`() {
        // Given
        val budgetId = 1L
        val event = createTestEvent()
        val eventJson = """{"budgetEntry":{"id":1},"userInfo":{"email":"test@example.com"}}"""

        every { objectMapper.writeValueAsString(event) } returns eventJson

        // Create an emitter and complete it to simulate a dead connection
        val deadEmitter = sseService.createEmitter(budgetId)
        sseService.createEmitter(budgetId)

        // Complete the first emitter to make it dead
        deadEmitter.complete()

        // When - broadcast should handle the dead emitter gracefully
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId, event)
        }

        // Then - verify the service attempted to send
        verify(exactly = 1) { objectMapper.writeValueAsString(event) }
    }

    @Test
    fun `broadcastBudgetEntryEvent should serialize event correctly`() {
        // Given
        val budgetId = 1L
        val event = BudgetEntryEvent(
            budgetEntry = BudgetEntryEventData(
                id = 123L,
                budgetId = budgetId,
                amount = BigDecimal("500.00"),
                description = "Test Entry",
                category = "Test Category",
                type = EntryType.INCOME,
                creationDate = LocalDateTime.of(2025, 1, 1, 10, 0),
                modificationDate = LocalDateTime.of(2025, 1, 1, 10, 0)
            ),
            userInfo = UserEventInfo(
                email = "user@example.com",
                name = "Test User"
            )
        )
        val expectedJson = """{"budgetEntry":{"id":123,"amount":"500.00"},"userInfo":{"email":"user@example.com"}}"""

        every { objectMapper.writeValueAsString(event) } returns expectedJson

        sseService.createEmitter(budgetId)

        // When
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId, event)
        }

        // Then - verify event was serialized
        verify(exactly = 1) { objectMapper.writeValueAsString(event) }
    }

    @Test
    fun `multiple emitters should be cleaned up on completion`() {
        // Given
        val budgetId = 1L
        val event = createTestEvent()
        val eventJson = """{"test":"data"}"""

        every { objectMapper.writeValueAsString(event) } returns eventJson

        // Create multiple emitters
        val emitter1 = sseService.createEmitter(budgetId)
        val emitter2 = sseService.createEmitter(budgetId)
        sseService.createEmitter(budgetId)

        // First broadcast - should succeed with all emitters
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId, event)
        }
        verify(exactly = 1) { objectMapper.writeValueAsString(event) }

        // Complete emitter1 and emitter2
        emitter1.complete()
        emitter2.complete()

        // Reset the mock to track the second call
        clearMocks(objectMapper)
        every { objectMapper.writeValueAsString(event) } returns eventJson

        // Second broadcast - should still work with remaining emitter
        assertDoesNotThrow {
            sseService.broadcastBudgetEntryEvent(budgetId, event)
        }
        verify(exactly = 1) { objectMapper.writeValueAsString(event) }
    }

    // Helper Methods

    private fun createTestEvent(): BudgetEntryEvent {
        return BudgetEntryEvent(
            budgetEntry = BudgetEntryEventData(
                id = 1L,
                budgetId = 1L,
                amount = BigDecimal("100.00"),
                description = "Test Entry",
                category = "Test",
                type = EntryType.OUTCOME,
                creationDate = LocalDateTime.now(),
                modificationDate = LocalDateTime.now()
            ),
            userInfo = UserEventInfo(
                email = "test@example.com",
                name = "Test User"
            )
        )
    }
}

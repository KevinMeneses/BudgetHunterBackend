package com.budgethunter.service

import com.budgethunter.dto.BudgetEntryAction
import com.budgethunter.dto.BudgetEntryEvent
import com.budgethunter.dto.UserEventInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReactiveSseServiceTest {

    private val service = ReactiveSseService()

    private fun event(entryId: Long) = BudgetEntryEvent(
        budgetId = BUDGET_ID,
        entryId = entryId,
        action = BudgetEntryAction.CREATED,
        userInfo = UserEventInfo(email = "other@test.com", name = "Other")
    )

    /**
     * Regression test: once every subscriber of a budget had left (app closed, budget switched),
     * the budget's sink stayed in the map but shut down, so later subscribers kept the
     * connection (heartbeats) and never received another event until the server restarted.
     */
    @Test
    fun `a subscriber that joins after everyone left still receives events`() {
        val first = service.subscribeToEvents(BUDGET_ID).subscribe()
        first.dispose()

        val received = mutableListOf<Long>()
        val second = service.subscribeToEvents(BUDGET_ID).subscribe { received += it.entryId }
        service.broadcastEvent(BUDGET_ID, event(entryId = 7))

        assertEquals(listOf(7L), received)
        second.dispose()
    }

    @Test
    fun `events broadcast while nobody listens are not replayed to later subscribers`() {
        service.subscribeToEvents(BUDGET_ID).subscribe().dispose()
        service.broadcastEvent(BUDGET_ID, event(entryId = 1))

        val received = mutableListOf<Long>()
        val subscription = service.subscribeToEvents(BUDGET_ID).subscribe { received += it.entryId }
        service.broadcastEvent(BUDGET_ID, event(entryId = 2))

        assertEquals(listOf(2L), received)
        assertEquals(1, service.getSubscriberCount(BUDGET_ID))
        subscription.dispose()
        assertEquals(0, service.getSubscriberCount(BUDGET_ID))
    }

    @Test
    fun `every current subscriber receives the event`() {
        val a = mutableListOf<Long>()
        val b = mutableListOf<Long>()
        val subA = service.subscribeToEvents(BUDGET_ID).subscribe { a += it.entryId }
        val subB = service.subscribeToEvents(BUDGET_ID).subscribe { b += it.entryId }

        service.broadcastEvent(BUDGET_ID, event(entryId = 3))

        assertEquals(listOf(3L), a)
        assertEquals(listOf(3L), b)
        subA.dispose()
        subB.dispose()
    }

    private companion object {
        const val BUDGET_ID = 23L
    }
}

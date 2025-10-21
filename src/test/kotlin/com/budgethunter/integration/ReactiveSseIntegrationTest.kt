package com.budgethunter.integration

import com.budgethunter.dto.*
import com.budgethunter.model.EntryType
import com.budgethunter.service.ReactiveSseService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Reactive SSE Integration Tests
 *
 * These tests verify the Flux-based SSE implementation.
 * They test that:
 * 1. The reactive SSE service correctly manages subscribers
 * 2. Events are broadcast to the correct budget subscribers
 * 3. The endpoint is accessible with proper authentication
 *
 * Note: Full end-to-end SSE event reception testing requires a real HTTP client
 * or browser. These tests verify the server-side logic and subscription management.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReactiveSseIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var reactiveSseService: ReactiveSseService

    private val userEmail = "reactive-sse-test@example.com"
    private val userPassword = "Password123!"
    private val userName = "Reactive SSE Test User"

    private lateinit var authToken: String
    private var budgetId: Long = 0

    @BeforeEach
    fun setup() {
        authToken = createAndAuthenticateUser(userEmail, userName, userPassword)
        budgetId = createTestBudget()
    }

    private fun createAndAuthenticateUser(email: String, name: String, password: String): String {
        val signUpRequest = SignUpRequest(email = email, name = name, password = password)

        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        )

        val signInRequest = SignInRequest(email = email, password = password)

        val result = mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInRequest))
        ).andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)
        return response.authToken
    }

    private fun createTestBudget(): Long {
        val request = CreateBudgetRequest(
            name = "Reactive SSE Test Budget",
            amount = BigDecimal("1000.00")
        )

        val result = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java)
        return response.id
    }

    @Test
    fun `reactive SSE service should manage subscribers correctly`() {
        // Given - Initially no subscribers
        assertEquals(0, reactiveSseService.getSubscriberCount(budgetId))

        // When - Subscribe to events
        val flux1 = reactiveSseService.subscribeToEvents(budgetId)
        val subscription1 = flux1.subscribe()

        // Then - Should have 1 subscriber
        assertEquals(1, reactiveSseService.getSubscriberCount(budgetId))

        // When - Add another subscriber
        val flux2 = reactiveSseService.subscribeToEvents(budgetId)
        val subscription2 = flux2.subscribe()

        // Then - Should have 2 subscribers
        assertEquals(2, reactiveSseService.getSubscriberCount(budgetId))

        // When - Cancel subscriptions
        subscription1.dispose()
        subscription2.dispose()

        // Give cleanup time to run
        Thread.sleep(100)

        // Then - Should have 0 subscribers (sink cleaned up)
        assertEquals(0, reactiveSseService.getSubscriberCount(budgetId))
    }

    @Test
    fun `should receive SSE event when budget entry is created`() {
        // Given - Subscribe to events for this budget
        val receivedEvents = mutableListOf<BudgetEntryEvent>()
        val subscription = reactiveSseService.subscribeToEvents(budgetId)
            .subscribe { event -> receivedEvents.add(event) }

        // Wait for subscription to be ready
        Thread.sleep(100)

        // When - Create a budget entry
        val entryRequest = PutEntryRequest(
            id = null,
            budgetId = budgetId,
            amount = BigDecimal("150.00"),
            description = "Test Entry",
            category = "Food",
            type = EntryType.OUTCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entryRequest))
        )

        // Wait for event to be broadcasted
        Thread.sleep(200)

        // Then - Should have received exactly 1 event
        assertEquals(1, receivedEvents.size)

        // And - Event should contain correct data
        val event = receivedEvents[0]
        assertEquals(budgetId, event.budgetEntry.budgetId)
        assertEquals("Test Entry", event.budgetEntry.description)
        assertEquals(BigDecimal("150.00"), event.budgetEntry.amount)
        assertEquals("Food", event.budgetEntry.category)
        assertEquals(EntryType.OUTCOME, event.budgetEntry.type)
        assertEquals(userEmail, event.userInfo.email)
        assertEquals(userName, event.userInfo.name)

        // Cleanup
        subscription.dispose()
    }

    @Test
    fun `should receive SSE event when budget entry is updated`() {
        // Given - Create an initial entry
        val createRequest = PutEntryRequest(
            id = null,
            budgetId = budgetId,
            amount = BigDecimal("100.00"),
            description = "Initial Entry",
            category = "Transport",
            type = EntryType.OUTCOME
        )

        val createResult = mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        ).andReturn()

        val createdEntry = objectMapper.readValue(createResult.response.contentAsString, BudgetEntryResponse::class.java)

        // And - Subscribe to events
        val receivedEvents = mutableListOf<BudgetEntryEvent>()
        val subscription = reactiveSseService.subscribeToEvents(budgetId)
            .subscribe { event -> receivedEvents.add(event) }

        Thread.sleep(100)

        // When - Update the entry
        val updateRequest = PutEntryRequest(
            id = createdEntry.id,
            budgetId = budgetId,
            amount = BigDecimal("250.00"),
            description = "Updated Entry",
            category = "Transport",
            type = EntryType.OUTCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest))
        )

        Thread.sleep(200)

        // Then - Should have received exactly 1 event (the update)
        assertEquals(1, receivedEvents.size)

        // And - Event should contain updated data
        val event = receivedEvents[0]
        assertEquals(budgetId, event.budgetEntry.budgetId)
        assertEquals("Updated Entry", event.budgetEntry.description)
        assertEquals(BigDecimal("250.00"), event.budgetEntry.amount)
        assertEquals(userEmail, event.userInfo.email)

        // Cleanup
        subscription.dispose()
    }

    @Test
    fun `should broadcast events only to subscribers of correct budget`() {
        // Given - Two budgets
        val budget2Request = CreateBudgetRequest(name = "Budget 2", amount = BigDecimal("2000.00"))
        val budget2Result = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(budget2Request))
        ).andReturn()
        val budget2 = objectMapper.readValue(budget2Result.response.contentAsString, BudgetResponse::class.java)

        // And - Subscribe to events for both budgets
        val budget1Events = mutableListOf<BudgetEntryEvent>()
        val budget2Events = mutableListOf<BudgetEntryEvent>()

        val subscription1 = reactiveSseService.subscribeToEvents(budgetId)
            .subscribe { event -> budget1Events.add(event) }
        val subscription2 = reactiveSseService.subscribeToEvents(budget2.id)
            .subscribe { event -> budget2Events.add(event) }

        Thread.sleep(100)

        // When - Create entry in budget 1
        val entry1 = PutEntryRequest(
            id = null,
            budgetId = budgetId,
            amount = BigDecimal("100.00"),
            description = "Budget 1 Entry",
            category = "Cat1",
            type = EntryType.OUTCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry1))
        )

        Thread.sleep(200)

        // And - Create entry in budget 2
        val entry2 = PutEntryRequest(
            id = null,
            budgetId = budget2.id,
            amount = BigDecimal("200.00"),
            description = "Budget 2 Entry",
            category = "Cat2",
            type = EntryType.OUTCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry2))
        )

        Thread.sleep(200)

        // Then - Budget 1 subscriber should have received only budget 1 event
        assertEquals(1, budget1Events.size)
        assertEquals("Budget 1 Entry", budget1Events[0].budgetEntry.description)
        assertEquals(budgetId, budget1Events[0].budgetEntry.budgetId)

        // And - Budget 2 subscriber should have received only budget 2 event
        assertEquals(1, budget2Events.size)
        assertEquals("Budget 2 Entry", budget2Events[0].budgetEntry.description)
        assertEquals(budget2.id, budget2Events[0].budgetEntry.budgetId)

        // Cleanup
        subscription1.dispose()
        subscription2.dispose()
    }

    @Test
    fun `multiple collaborators should receive events for entries created by each other`() {
        // Given - Create second user and add as collaborator
        val user2Email = "user2@example.com"
        val user2Name = "User Two"
        val user2Token = createAndAuthenticateUser(user2Email, user2Name, userPassword)

        mockMvc.perform(
            post("/api/budgets/add_collaborator")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddCollaboratorRequest(budgetId, user2Email)))
        )

        // And - Subscribe to events (simulating both users listening)
        val receivedEvents = mutableListOf<BudgetEntryEvent>()
        val subscription = reactiveSseService.subscribeToEvents(budgetId)
            .subscribe { event -> receivedEvents.add(event) }

        Thread.sleep(100)

        // When - User 1 creates an entry
        val entry1 = PutEntryRequest(
            id = null,
            budgetId = budgetId,
            amount = BigDecimal("50.00"),
            description = "User 1 Entry",
            category = "Food",
            type = EntryType.OUTCOME
        )
        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry1))
        )

        Thread.sleep(200)

        // And - User 2 creates an entry
        val entry2 = PutEntryRequest(
            id = null,
            budgetId = budgetId,
            amount = BigDecimal("75.00"),
            description = "User 2 Entry",
            category = "Transport",
            type = EntryType.OUTCOME
        )
        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user2Token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry2))
        )

        Thread.sleep(200)

        // Then - Should have received 2 events
        assertEquals(2, receivedEvents.size)

        // And - First event should be from user 1
        val event1 = receivedEvents[0]
        assertEquals("User 1 Entry", event1.budgetEntry.description)
        assertEquals(BigDecimal("50.00"), event1.budgetEntry.amount)
        assertEquals(userEmail, event1.userInfo.email)
        assertEquals(userName, event1.userInfo.name)

        // And - Second event should be from user 2
        val event2 = receivedEvents[1]
        assertEquals("User 2 Entry", event2.budgetEntry.description)
        assertEquals(BigDecimal("75.00"), event2.budgetEntry.amount)
        assertEquals(user2Email, event2.userInfo.email)
        assertEquals(user2Name, event2.userInfo.name)

        // Cleanup
        subscription.dispose()
    }

    @Test
    fun `multiple subscribers to same budget should all receive the same event`() {
        // Given - Three subscribers to the same budget
        val subscriber1Events = mutableListOf<BudgetEntryEvent>()
        val subscriber2Events = mutableListOf<BudgetEntryEvent>()
        val subscriber3Events = mutableListOf<BudgetEntryEvent>()

        val subscription1 = reactiveSseService.subscribeToEvents(budgetId)
            .subscribe { event -> subscriber1Events.add(event) }
        val subscription2 = reactiveSseService.subscribeToEvents(budgetId)
            .subscribe { event -> subscriber2Events.add(event) }
        val subscription3 = reactiveSseService.subscribeToEvents(budgetId)
            .subscribe { event -> subscriber3Events.add(event) }

        Thread.sleep(100)

        // Verify all 3 are subscribed
        assertEquals(3, reactiveSseService.getSubscriberCount(budgetId))

        // When - A single entry is created
        val entryRequest = PutEntryRequest(
            id = null,
            budgetId = budgetId,
            amount = BigDecimal("999.99"),
            description = "Multicast Test",
            category = "Test",
            type = EntryType.INCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entryRequest))
        )

        Thread.sleep(200)

        // Then - All 3 subscribers should have received the same event
        assertEquals(1, subscriber1Events.size)
        assertEquals(1, subscriber2Events.size)
        assertEquals(1, subscriber3Events.size)

        // And - All events should have the same data
        listOf(subscriber1Events[0], subscriber2Events[0], subscriber3Events[0]).forEach { event ->
            assertEquals("Multicast Test", event.budgetEntry.description)
            assertEquals(BigDecimal("999.99"), event.budgetEntry.amount)
            assertEquals(EntryType.INCOME, event.budgetEntry.type)
            assertEquals(budgetId, event.budgetEntry.budgetId)
        }

        // Cleanup
        subscription1.dispose()
        subscription2.dispose()
        subscription3.dispose()
    }
}

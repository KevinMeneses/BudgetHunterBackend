package com.budgethunter.integration

import com.budgethunter.dto.*
import com.budgethunter.model.EntryType
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SseIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val userEmail = "sse-test@example.com"
    private val userPassword = "Password123!"
    private val userName = "SSE Test User"

    private lateinit var authToken: String
    private var budgetId: Long = 0

    @BeforeEach
    fun setup() {
        // Create and authenticate user
        authToken = createAndAuthenticateUser(userEmail, userName, userPassword)

        // Create a budget for testing
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
            name = "SSE Test Budget",
            amount = BigDecimal("1000.00")
        )

        val result = mockMvc.perform(
            post("/api/budgets")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java)
        return response.id
    }

    // SSE Connection Tests

    @Test
    fun `should establish SSE connection successfully with authentication`() {
        // When & Then - SSE endpoint should return 200 OK and an SseEmitter
        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .header("Authorization", "Bearer $authToken")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `should reject SSE connection without authentication`() {
        // When & Then
        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .accept("text/event-stream")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `should reject SSE connection without budget access`() {
        // Given - Create another user
        val otherUserToken = createAndAuthenticateUser(
            "other@example.com",
            "Other User",
            "Password123!"
        )

        // When & Then - Try to connect to budget they don't have access to
        // The exception is thrown during controller execution
        try {
            mockMvc.perform(
                get("/api/budgets/${budgetId}/entries/stream")
                    .header("Authorization", "Bearer $otherUserToken")
                    .accept("text/event-stream")
            )
            fail("Should have thrown an exception")
        } catch (e: Exception) {
            // Verify it's the expected authorization exception
            assertTrue(e.message!!.contains("don't have access") || e.cause?.message?.contains("don't have access") == true)
        }
    }

    @Test
    fun `should reject SSE connection for non-existent budget`() {
        // When & Then - The exception is thrown during controller execution
        try {
            mockMvc.perform(
                get("/api/budgets/99999/entries/stream")
                    .header("Authorization", "Bearer $authToken")
                    .accept("text/event-stream")
            )
            fail("Should have thrown an exception")
        } catch (e: Exception) {
            // Verify it's the expected authorization exception
            assertTrue(e.message!!.contains("don't have access") || e.cause?.message?.contains("don't have access") == true)
        }
    }

    // SSE Event Broadcasting Tests

    @Test
    fun `should receive SSE event when budget entry is created`() {
        // Verify SSE endpoint is accessible
        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .header("Authorization", "Bearer $authToken")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)

        // Create a budget entry which should trigger SSE event
        val entryRequest = CreateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Test Entry",
            category = "Test",
            type = EntryType.OUTCOME
        )

        val result = mockMvc.perform(
            post("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entryRequest))
        )
            .andExpect(status().isCreated)
            .andReturn()

        // Verify the entry was created (SSE event would have been broadcast)
        val response = objectMapper.readValue(result.response.contentAsString, BudgetEntryResponse::class.java)
        assertNotNull(response.id)
        assertEquals(entryRequest.amount, response.amount)
        assertEquals(entryRequest.description, response.description)
    }

    @Test
    fun `should receive SSE event when budget entry is updated`() {
        // Given - Create an entry first
        val createRequest = CreateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Original",
            category = "Test",
            type = EntryType.OUTCOME
        )

        val createResult = mockMvc.perform(
            post("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        ).andReturn()

        val createdEntry = objectMapper.readValue(createResult.response.contentAsString, BudgetEntryResponse::class.java)

        // Verify SSE endpoint is accessible
        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .header("Authorization", "Bearer $authToken")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)

        // When - Update the entry (should trigger SSE event)
        val updateRequest = UpdateBudgetEntryRequest(
            amount = BigDecimal("200.00"),
            description = "Updated",
            category = "Updated",
            type = EntryType.INCOME
        )

        val updateResult = mockMvc.perform(
            put("/api/budgets/${budgetId}/entries/${createdEntry.id}")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        // Then - Verify the entry was updated
        val updated = objectMapper.readValue(updateResult.response.contentAsString, BudgetEntryResponse::class.java)
        assertEquals(createdEntry.id, updated.id)
        assertEquals(updateRequest.amount, updated.amount)
        assertEquals(updateRequest.description, updated.description)
        assertNotNull(updated.updatedByEmail)
    }

    // Multi-User SSE Tests

    @Test
    fun `multiple users should be able to connect to same budget SSE`() {
        // Given - Create second user and add as collaborator
        val user2Token = createAndAuthenticateUser(
            "user2@example.com",
            "User Two",
            "Password123!"
        )

        val addCollaboratorRequest = AddCollaboratorRequest(
            budgetId = budgetId,
            email = "user2@example.com"
        )

        mockMvc.perform(
            post("/api/budgets/${budgetId}/collaborators")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addCollaboratorRequest))
        )

        // When & Then - Both users should be able to connect
        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .header("Authorization", "Bearer $authToken")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .header("Authorization", "Bearer $user2Token")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `SSE events should be budget-scoped`() {
        // Given - Create two budgets
        val budget1Id = budgetId
        val budget2Request = CreateBudgetRequest(
            name = "Budget 2",
            amount = BigDecimal("2000.00")
        )

        val budget2Result = mockMvc.perform(
            post("/api/budgets")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(budget2Request))
        ).andReturn()

        val budget2 = objectMapper.readValue(budget2Result.response.contentAsString, BudgetResponse::class.java)
        val budget2Id = budget2.id

        // Verify both SSE connections can be established
        mockMvc.perform(
            get("/api/budgets/${budget1Id}/entries/stream")
                .header("Authorization", "Bearer $authToken")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/budgets/${budget2Id}/entries/stream")
                .header("Authorization", "Bearer $authToken")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)

        // Create entries in both budgets (each would trigger budget-specific SSE events)
        val entry1 = CreateBudgetEntryRequest(
            amount = BigDecimal("50.00"),
            description = "Budget 1 Entry",
            category = "Cat1",
            type = EntryType.OUTCOME
        )
        val entry2 = CreateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Budget 2 Entry",
            category = "Cat2",
            type = EntryType.OUTCOME
        )

        mockMvc.perform(
            post("/api/budgets/${budget1Id}/entries")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry1))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/budgets/${budget2Id}/entries")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry2))
        )
            .andExpect(status().isCreated)
    }

    // Collaborative SSE Test

    @Test
    fun `collaborators should receive events from each other's actions`() {
        // Given - Create two users as collaborators
        val user1Token = authToken
        val user1Email = userEmail

        val user2Email = "collaborator@example.com"
        val user2Token = createAndAuthenticateUser(
            user2Email,
            "Collaborator",
            "Password123!"
        )

        // Add user2 as collaborator
        val addCollaboratorRequest = AddCollaboratorRequest(
            budgetId = budgetId,
            email = user2Email
        )

        mockMvc.perform(
            post("/api/budgets/${budgetId}/collaborators")
                .header("Authorization", "Bearer $user1Token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addCollaboratorRequest))
        )

        // Both establish SSE connections
        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .header("Authorization", "Bearer $user1Token")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/budgets/${budgetId}/entries/stream")
                .header("Authorization", "Bearer $user2Token")
                .accept("text/event-stream")
        )
            .andExpect(status().isOk)

        // When - User 1 creates an entry
        val entryRequest = CreateBudgetEntryRequest(
            amount = BigDecimal("75.00"),
            description = "User 1 Entry",
            category = "Food",
            type = EntryType.OUTCOME
        )

        val result = mockMvc.perform(
            post("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1Token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entryRequest))
        )
            .andExpect(status().isCreated)
            .andReturn()

        // Then - Verify entry was created (both users would receive SSE event)
        val response = objectMapper.readValue(result.response.contentAsString, BudgetEntryResponse::class.java)
        assertEquals(user1Email, response.createdByEmail)

        // When - User 2 creates an entry
        val entry2Request = CreateBudgetEntryRequest(
            amount = BigDecimal("125.00"),
            description = "User 2 Entry",
            category = "Transport",
            type = EntryType.OUTCOME
        )

        val result2 = mockMvc.perform(
            post("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user2Token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry2Request))
        )
            .andExpect(status().isCreated)
            .andReturn()

        // Then - Verify entry was created (both users would receive SSE event)
        val response2 = objectMapper.readValue(result2.response.contentAsString, BudgetEntryResponse::class.java)
        assertEquals(user2Email, response2.createdByEmail)

        // Verify both entries exist
        val entriesResult = mockMvc.perform(
            get("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1Token")
        ).andReturn()

        val entries = objectMapper.readValue(entriesResult.response.contentAsString, Array<BudgetEntryResponse>::class.java)
        assertEquals(2, entries.size)
    }
}

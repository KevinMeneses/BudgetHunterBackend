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

/**
 * Concurrent Budget Entry Tests
 *
 * NOTE: These tests simulate concurrent behavior by rapidly executing operations
 * from multiple users. While not using true concurrent threads (due to MockMvc
 * thread-safety limitations), they test the system's ability to handle rapid
 * sequential operations from multiple users and verify data consistency.
 *
 * For true multi-threaded concurrent testing, a separate load testing tool
 * (e.g., JMeter, Gatling) should be used against a running server instance.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConcurrentBudgetEntryTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val user1Email = "concurrent-user1@example.com"
    private val user2Email = "concurrent-user2@example.com"
    private val user3Email = "concurrent-user3@example.com"
    private val userPassword = "Password123!"

    private lateinit var user1AuthToken: String
    private lateinit var user2AuthToken: String
    private lateinit var user3AuthToken: String
    private var budgetId: Long = 0

    @BeforeEach
    fun setup() {
        // Create and authenticate multiple users
        user1AuthToken = createAndAuthenticateUser(user1Email, "User One", userPassword)
        user2AuthToken = createAndAuthenticateUser(user2Email, "User Two", userPassword)
        user3AuthToken = createAndAuthenticateUser(user3Email, "User Three", userPassword)

        // Create a shared budget with all users as collaborators
        budgetId = createBudgetAndAddCollaborators()
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

    private fun createBudgetAndAddCollaborators(): Long {
        // User 1 creates the budget
        val request = CreateBudgetRequest(
            name = "Concurrent Test Budget",
            amount = BigDecimal("10000.00")
        )

        val result = mockMvc.perform(
            post("/api/budgets")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andReturn()

        val budget = objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java)

        // Add user 2 and user 3 as collaborators
        mockMvc.perform(
            post("/api/budgets/${budget.id}/collaborators")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddCollaboratorRequest(budget.id, user2Email)))
        )

        mockMvc.perform(
            post("/api/budgets/${budget.id}/collaborators")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddCollaboratorRequest(budget.id, user3Email)))
        )

        return budget.id
    }

    @Test
    fun `multiple users should be able to create entries in rapid succession`() {
        // Given
        val numEntriesPerUser = 5
        val totalExpectedEntries = numEntriesPerUser * 3

        val users = listOf(
            Triple(user1Email, user1AuthToken, "User1"),
            Triple(user2Email, user2AuthToken, "User2"),
            Triple(user3Email, user3AuthToken, "User3")
        )

        // When - Create entries rapidly from different users
        users.forEach { (_, authToken, userName) ->
            repeat(numEntriesPerUser) { index ->
                val entryRequest = CreateBudgetEntryRequest(
                    amount = BigDecimal("${(index + 1) * 10}.00"),
                    description = "$userName Entry $index",
                    category = "Category $index",
                    type = if (index % 2 == 0) EntryType.OUTCOME else EntryType.INCOME
                )

                mockMvc.perform(
                    post("/api/budgets/${budgetId}/entries")
                        .header("Authorization", "Bearer $authToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(entryRequest))
                )
                    .andExpect(status().isCreated)
            }
        }

        // Then - Verify all entries were created
        val entriesResult = mockMvc.perform(
            get("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1AuthToken")
        ).andReturn()

        val entries = objectMapper.readValue(entriesResult.response.contentAsString, Array<BudgetEntryResponse>::class.java)
        assertEquals(totalExpectedEntries, entries.size)

        // Verify entries from all users exist
        assertTrue(entries.any { it.createdByEmail == user1Email })
        assertTrue(entries.any { it.createdByEmail == user2Email })
        assertTrue(entries.any { it.createdByEmail == user3Email })

        // Verify each user has correct number of entries
        assertEquals(numEntriesPerUser, entries.count { it.createdByEmail == user1Email })
        assertEquals(numEntriesPerUser, entries.count { it.createdByEmail == user2Email })
        assertEquals(numEntriesPerUser, entries.count { it.createdByEmail == user3Email })
    }

    @Test
    fun `multiple users should be able to update same entry in succession with proper audit trail`() {
        // Given - Create an initial entry
        val createRequest = CreateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Initial Entry",
            category = "Test",
            type = EntryType.OUTCOME
        )

        val createResult = mockMvc.perform(
            post("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isCreated)
            .andReturn()

        val createdEntry = objectMapper.readValue(createResult.response.contentAsString, BudgetEntryResponse::class.java)
        val entryId = createdEntry.id

        val users = listOf(
            Triple(user1Email, user1AuthToken, "User1"),
            Triple(user2Email, user2AuthToken, "User2"),
            Triple(user3Email, user3AuthToken, "User3")
        )

        // When - Multiple users update the same entry
        users.forEachIndexed { userIndex, (_, authToken, userName) ->
            val updateRequest = UpdateBudgetEntryRequest(
                amount = BigDecimal("${(userIndex + 1) * 100}.00"),
                description = "$userName Update",
                category = "Updated",
                type = EntryType.INCOME
            )

            mockMvc.perform(
                put("/api/budgets/${budgetId}/entries/${entryId}")
                    .header("Authorization", "Bearer $authToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest))
            )
                .andExpect(status().isOk)
        }

        // Then - Verify the final state
        val finalResult = mockMvc.perform(
            get("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1AuthToken")
        ).andReturn()

        val entries = objectMapper.readValue(finalResult.response.contentAsString, Array<BudgetEntryResponse>::class.java)
        assertEquals(1, entries.size, "Should still have only one entry")

        val finalEntry = entries[0]
        assertEquals(entryId, finalEntry.id)
        assertEquals("User3 Update", finalEntry.description) // Last update wins
        assertEquals(BigDecimal("300.00"), finalEntry.amount)
        assertEquals(user1Email, finalEntry.createdByEmail) // Original creator
        assertEquals(user3Email, finalEntry.updatedByEmail) // Last updater
    }

    @Test
    fun `rapid mixed operations from multiple users should maintain data consistency`() {
        // Given - Create some initial entries
        val initialEntries = mutableListOf<Long>()
        repeat(3) { index ->
            val authToken = when (index) {
                0 -> user1AuthToken
                1 -> user2AuthToken
                else -> user3AuthToken
            }

            val createRequest = CreateBudgetEntryRequest(
                amount = BigDecimal("${(index + 1) * 100}.00"),
                description = "Initial Entry $index",
                category = "Cat$index",
                type = EntryType.OUTCOME
            )

            val result = mockMvc.perform(
                post("/api/budgets/${budgetId}/entries")
                    .header("Authorization", "Bearer $authToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest))
            ).andReturn()

            val entry = objectMapper.readValue(result.response.contentAsString, BudgetEntryResponse::class.java)
            initialEntries.add(entry.id)
        }

        val users = listOf(user1AuthToken, user2AuthToken, user3AuthToken)

        // When - Perform rapid mixed operations (creates and updates)
        var createCount = 0
        var updateCount = 0

        repeat(15) { index ->
            val authToken = users[index % users.size]

            if (index % 2 == 0) {
                // Create new entry
                val createRequest = CreateBudgetEntryRequest(
                    amount = BigDecimal("${index * 10}.00"),
                    description = "Rapid Create $index",
                    category = "CatX",
                    type = EntryType.OUTCOME
                )

                mockMvc.perform(
                    post("/api/budgets/${budgetId}/entries")
                        .header("Authorization", "Bearer $authToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                )
                    .andExpect(status().isCreated)

                createCount++
            } else {
                // Update existing entry
                val entryToUpdate = initialEntries[index % initialEntries.size]
                val updateRequest = UpdateBudgetEntryRequest(
                    amount = BigDecimal("999.99"),
                    description = "Updated $index",
                    category = "UpdatedCat",
                    type = EntryType.INCOME
                )

                mockMvc.perform(
                    put("/api/budgets/${budgetId}/entries/${entryToUpdate}")
                        .header("Authorization", "Bearer $authToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                )
                    .andExpect(status().isOk)

                updateCount++
            }
        }

        // Then - Verify final database state is consistent
        val finalResult = mockMvc.perform(
            get("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1AuthToken")
        ).andReturn()

        val finalEntries = objectMapper.readValue(finalResult.response.contentAsString, Array<BudgetEntryResponse>::class.java)

        // All entries should have valid data
        finalEntries.forEach { entry ->
            assertNotNull(entry.id)
            assertEquals(budgetId, entry.budgetId)
            assertNotNull(entry.createdByEmail)
            assertNotNull(entry.amount)
            assertNotNull(entry.description)
        }

        // Total entry count should be initial + new creates
        val expectedTotal = initialEntries.size + createCount
        assertEquals(expectedTotal, finalEntries.size)

        // At least some entries should show they were updated
        assertTrue(finalEntries.any { it.updatedByEmail != null })
    }

    @Test
    fun `SSE subscriptions should handle rapid entry creations correctly`() {
        // Given - Establish SSE connections for all users
        val users = listOf(user1AuthToken, user2AuthToken, user3AuthToken)

        users.forEach { authToken ->
            mockMvc.perform(
                get("/api/budgets/${budgetId}/entries/stream")
                    .header("Authorization", "Bearer $authToken")
                    .accept("text/event-stream")
            )
                .andExpect(status().isOk)
        }

        // When - Create multiple entries rapidly (which trigger SSE events)
        val numEntries = 10
        repeat(numEntries) { index ->
            val authToken = users[index % users.size]
            val entryRequest = CreateBudgetEntryRequest(
                amount = BigDecimal("${index * 50}.00"),
                description = "SSE Entry $index",
                category = "SSE",
                type = EntryType.OUTCOME
            )

            mockMvc.perform(
                post("/api/budgets/${budgetId}/entries")
                    .header("Authorization", "Bearer $authToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(entryRequest))
            )
                .andExpect(status().isCreated)
        }

        // Then - Verify all entries were persisted correctly
        val entriesResult = mockMvc.perform(
            get("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1AuthToken")
        ).andReturn()

        val entries = objectMapper.readValue(entriesResult.response.contentAsString, Array<BudgetEntryResponse>::class.java)
        assertEquals(numEntries, entries.size)

        // Verify entries are from different users
        assertTrue(entries.any { it.createdByEmail == user1Email })
        assertTrue(entries.any { it.createdByEmail == user2Email })
        assertTrue(entries.any { it.createdByEmail == user3Email })
    }

    @Test
    fun `system should handle interleaved creates and updates without data loss`() {
        // Given
        val users = listOf(
            user1AuthToken to user1Email,
            user2AuthToken to user2Email,
            user3AuthToken to user3Email
        )

        val createdIds = mutableListOf<Long>()

        // When - Interleave creates and updates in a pattern
        repeat(20) { iteration ->
            val (authToken, _) = users[iteration % users.size]

            when {
                // First 10 iterations: only creates
                iteration < 10 -> {
                    val createRequest = CreateBudgetEntryRequest(
                        amount = BigDecimal("${iteration * 25}.00"),
                        description = "Interleaved Create $iteration",
                        category = "Create",
                        type = EntryType.OUTCOME
                    )

                    val result = mockMvc.perform(
                        post("/api/budgets/${budgetId}/entries")
                            .header("Authorization", "Bearer $authToken")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                    )
                        .andExpect(status().isCreated)
                        .andReturn()

                    val entry = objectMapper.readValue(result.response.contentAsString, BudgetEntryResponse::class.java)
                    createdIds.add(entry.id)
                }
                // Next 10 iterations: mix of creates and updates
                else -> {
                    if (iteration % 2 == 0) {
                        // Create
                        val createRequest = CreateBudgetEntryRequest(
                            amount = BigDecimal("${iteration * 25}.00"),
                            description = "Late Create $iteration",
                            category = "Create",
                            type = EntryType.INCOME
                        )

                        val result = mockMvc.perform(
                            post("/api/budgets/${budgetId}/entries")
                                .header("Authorization", "Bearer $authToken")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createRequest))
                        )
                            .andExpect(status().isCreated)
                            .andReturn()

                        val entry = objectMapper.readValue(result.response.contentAsString, BudgetEntryResponse::class.java)
                        createdIds.add(entry.id)
                    } else {
                        // Update a random existing entry
                        val idToUpdate = createdIds.random()
                        val updateRequest = UpdateBudgetEntryRequest(
                            amount = BigDecimal("777.77"),
                            description = "Updated $iteration",
                            category = "Updated",
                            type = EntryType.INCOME
                        )

                        mockMvc.perform(
                            put("/api/budgets/${budgetId}/entries/${idToUpdate}")
                                .header("Authorization", "Bearer $authToken")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateRequest))
                        )
                            .andExpect(status().isOk)
                    }
                }
            }
        }

        // Then - Verify data consistency
        val finalResult = mockMvc.perform(
            get("/api/budgets/${budgetId}/entries")
                .header("Authorization", "Bearer $user1AuthToken")
        ).andReturn()

        val finalEntries = objectMapper.readValue(finalResult.response.contentAsString, Array<BudgetEntryResponse>::class.java)

        // Verify total count matches created entries
        assertEquals(createdIds.size, finalEntries.size)

        // Verify all created IDs exist in final result
        val finalIds = finalEntries.map { it.id }.toSet()
        createdIds.forEach { createdId ->
            assertTrue(finalIds.contains(createdId), "Entry $createdId should exist in final result")
        }

        // Verify some entries were updated
        assertTrue(finalEntries.any { it.updatedByEmail != null })
        assertTrue(finalEntries.any { it.description.startsWith("Updated") })
    }
}

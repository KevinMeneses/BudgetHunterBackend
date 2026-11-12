package com.budgethunter.integration

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.EntryType
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.service.BudgetService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Budget ownership: the creator owns the budget; only the owner deletes it for everyone or removes others; any
 * other member can only leave, and a member's DELETE of the budget is leaving it (what installed apps send).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BudgetOwnershipIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var budgetService: BudgetService

    @Autowired
    private lateinit var budgetRepository: BudgetRepository

    @Autowired
    private lateinit var userBudgetRepository: UserBudgetRepository

    @Autowired
    private lateinit var budgetEntryRepository: BudgetEntryRepository

    private val owner = "owner@example.com"
    private val member = "member@example.com"
    private val other = "other@example.com"
    private val outsider = "outsider@example.com"

    private val tokens = mutableMapOf<String, String>()

    @BeforeEach
    fun setup() {
        listOf(owner, member, other, outsider).forEach { tokens[it] = signUpAndIn(it) }
    }

    // --- Who owns what, and what the responses say

    @Test
    fun `the creator owns the budget and the responses say so`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)

        listOf(owner, member).forEach { caller ->
            perform(get("/api/budgets"), caller)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].ownerEmail").value(owner))
        }
        perform(get("/api/budgets?page=0&size=10"), member)
            .andExpect(jsonPath("$.content[0].ownerEmail").value(owner))

        perform(get("/api/budgets/$budgetId/collaborators"), member)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.email == '$owner')].isOwner").value(true))
            .andExpect(jsonPath("$[?(@.email == '$member')].isOwner").value(false))
    }

    @Test
    fun `any member can add collaborators, who never come in as owners`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)

        perform(post("/api/budgets/$budgetId/collaborators").json(AddCollaboratorRequest(budgetId, other)), member)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.collaboratorEmail").value(other))
            .andExpect(jsonPath("$.isOwner").value(false))
    }

    // --- DELETE /api/budgets/{id}

    @Test
    fun `the owner deletes the budget for everyone`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)
        createEntry(budgetId, member)

        perform(delete("/api/budgets/$budgetId"), owner).andExpect(status().isNoContent)

        assertFalse(budgetRepository.existsById(budgetId))
        assertEquals(0, budgetEntryRepository.findByBudgetId(budgetId).size)
        perform(get("/api/budgets"), member).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `a member who deletes a budget they do not own only leaves it`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)
        addMember(budgetId, other)
        createEntry(budgetId, owner)

        perform(delete("/api/budgets/$budgetId"), member).andExpect(status().isNoContent)

        // Gone from the member's list, which is all an installed app needs...
        perform(get("/api/budgets"), member).andExpect(jsonPath("$.length()").value(0))
        perform(get("/api/budgets/$budgetId/entries"), member).andExpect(status().isForbidden)
        // ...and untouched for everyone else.
        assertTrue(budgetRepository.existsById(budgetId))
        assertEquals(1, budgetEntryRepository.findByBudgetId(budgetId).size)
        perform(get("/api/budgets"), owner)
            .andExpect(jsonPath("$[0].id").value(budgetId))
            .andExpect(jsonPath("$[0].collaboratorCount").value(2))
        perform(get("/api/budgets"), other).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `someone outside the budget can neither delete nor leave it`() {
        val budgetId = createBudget(owner)

        perform(delete("/api/budgets/$budgetId"), outsider).andExpect(status().isForbidden)
        perform(delete("/api/budgets/$budgetId/collaborators/me"), outsider).andExpect(status().isForbidden)
        assertTrue(budgetRepository.existsById(budgetId))
    }

    // --- Leaving

    @Test
    fun `a member leaves through collaborators-me`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)

        perform(delete("/api/budgets/$budgetId/collaborators/me"), member).andExpect(status().isNoContent)

        assertFalse(userBudgetRepository.existsById(UserBudgetId(budgetId, member)))
        assertTrue(budgetRepository.existsById(budgetId))
    }

    @Test
    fun `a member leaves by removing their own email`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)

        perform(delete("/api/budgets/$budgetId/collaborators/$member"), member).andExpect(status().isNoContent)

        assertFalse(userBudgetRepository.existsById(UserBudgetId(budgetId, member)))
    }

    @Test
    fun `the owner cannot leave, by either path`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)

        listOf("me", owner).forEach { path ->
            perform(delete("/api/budgets/$budgetId/collaborators/$path"), owner)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.message").value("The owner cannot leave budget $budgetId; delete the budget instead"))
        }
        assertTrue(userBudgetRepository.existsById(UserBudgetId(budgetId, owner)))
    }

    // --- Removing others

    @Test
    fun `only the owner removes other members`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)
        addMember(budgetId, other)

        perform(delete("/api/budgets/$budgetId/collaborators/$other"), member)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message").value("Only the owner of budget $budgetId can remove its members"))
        assertTrue(userBudgetRepository.existsById(UserBudgetId(budgetId, other)))

        perform(delete("/api/budgets/$budgetId/collaborators/$other"), owner).andExpect(status().isNoContent)
        assertFalse(userBudgetRepository.existsById(UserBudgetId(budgetId, other)))
    }

    @Test
    fun `nobody removes the owner`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)

        perform(delete("/api/budgets/$budgetId/collaborators/$owner"), member).andExpect(status().isForbidden)
        perform(delete("/api/budgets/$budgetId/collaborators/$owner"), outsider).andExpect(status().isForbidden)

        assertTrue(userBudgetRepository.existsById(UserBudgetId(budgetId, owner)))
        assertEquals(owner, budgetRepository.findById(budgetId).get().ownerEmail)
    }

    // --- Before an account is deleted

    @Test
    fun `handing over gives each budget to the member who wrote its oldest entry`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, member)
        addMember(budgetId, other)
        createEntry(budgetId, owner)
        createEntry(budgetId, other)
        createEntry(budgetId, member)

        budgetService.handOverOwnedBudgets(owner)

        assertEquals(other, budgetRepository.findById(budgetId).get().ownerEmail)
    }

    @Test
    fun `handing over falls back to the lowest email when no other member wrote an entry`() {
        val budgetId = createBudget(owner)
        addMember(budgetId, other)
        addMember(budgetId, member)
        createEntry(budgetId, owner)

        budgetService.handOverOwnedBudgets(owner)

        assertEquals(member, budgetRepository.findById(budgetId).get().ownerEmail)
    }

    @Test
    fun `handing over deletes a budget nobody else is in, and leaves other people's budgets alone`() {
        val alone = createBudget(owner)
        createEntry(alone, owner)
        val notTheirs = createBudget(member)
        addMember(notTheirs, owner, by = member)

        budgetService.handOverOwnedBudgets(owner)

        assertFalse(budgetRepository.existsById(alone))
        assertEquals(0, budgetEntryRepository.findByBudgetId(alone).size)
        assertEquals(member, budgetRepository.findById(notTheirs).get().ownerEmail)
    }

    // --- Helpers

    private fun signUpAndIn(email: String): String {
        val password = "Password123!"
        mockMvc.perform(
            post("/api/users/sign_up").json(SignUpRequest(email = email, name = email.substringBefore('@'), password = password))
        )
        val result = mockMvc.perform(post("/api/users/sign_in").json(SignInRequest(email = email, password = password)))
            .andReturn()
        return objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java).authToken
    }

    private fun createBudget(creator: String): Long {
        val result = perform(post("/api/budgets").json(CreateBudgetRequest(name = "Shared", amount = BigDecimal("100.00"))), creator)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.ownerEmail").value(creator))
            .andReturn()
        return objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java).id
    }

    private fun addMember(budgetId: Long, email: String, by: String = owner) {
        perform(post("/api/budgets/$budgetId/collaborators").json(AddCollaboratorRequest(budgetId, email)), by)
            .andExpect(status().isCreated)
    }

    private fun createEntry(budgetId: Long, author: String) {
        val request = CreateBudgetEntryRequest(amount = BigDecimal("1.00"), description = "by $author", type = EntryType.OUTCOME)
        perform(post("/api/budgets/$budgetId/entries").json(request), author).andExpect(status().isCreated)
    }

    private fun perform(
        request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        caller: String
    ): ResultActions = mockMvc.perform(request.header("Authorization", "Bearer ${tokens.getValue(caller)}"))

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.json(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
}

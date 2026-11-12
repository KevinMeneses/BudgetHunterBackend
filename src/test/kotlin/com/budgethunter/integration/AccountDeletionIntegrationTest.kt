package com.budgethunter.integration

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetEntryResponse
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UpdateBudgetEntryRequest
import com.budgethunter.model.EntryType
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.AiUsageRepository
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** `DELETE /api/users/me`: what happens to the account, its budgets, its entries and its sessions. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountDeletionIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var budgetRepository: BudgetRepository

    @Autowired
    private lateinit var userBudgetRepository: UserBudgetRepository

    @Autowired
    private lateinit var budgetEntryRepository: BudgetEntryRepository

    @Autowired
    private lateinit var aiUsageRepository: AiUsageRepository

    private val leaving = "leaving@example.com"
    private val member = "member@example.com"
    private val other = "other@example.com"

    private val sessions = mutableMapOf<String, SignInResponse>()

    @BeforeEach
    fun setup() {
        listOf(leaving, member, other).forEach { sessions[it] = signUpAndIn(it) }
    }

    @Test
    fun `the account and everything only it used are gone`() {
        val alone = createBudget(leaving)
        createEntry(alone, leaving)
        aiUsageRepository.createIfMissing(leaving, "2026-11")

        deleteAccount(leaving).andExpect(status().isNoContent)

        assertFalse(userRepository.existsById(leaving))
        assertFalse(budgetRepository.existsById(alone))
        assertEquals(0, budgetEntryRepository.findByBudgetId(alone).size)
        assertEquals(0, aiUsageRepository.count())
    }

    @Test
    fun `a shared budget it owns goes to another member, with its entries`() {
        val shared = createBudget(leaving)
        addMember(shared, member, by = leaving)
        addMember(shared, other, by = leaving)
        createEntry(shared, leaving)
        createEntry(shared, other)

        deleteAccount(leaving).andExpect(status().isNoContent)

        assertEquals(other, budgetRepository.findById(shared).get().ownerEmail)
        perform(get("/api/budgets"), member)
            .andExpect(jsonPath("$[0].id").value(shared))
            .andExpect(jsonPath("$[0].ownerEmail").value(other))
            .andExpect(jsonPath("$[0].collaboratorCount").value(2))
        // The new owner is the owner in every sense: deleting is for everyone now.
        perform(delete("/api/budgets/$shared"), other).andExpect(status().isNoContent)
        assertFalse(budgetRepository.existsById(shared))
    }

    @Test
    fun `it leaves the budgets of others, and what it wrote there stays without an author`() {
        val theirs = createBudget(member)
        addMember(theirs, leaving, by = member)
        createEntry(theirs, leaving)
        createEntry(theirs, member)

        deleteAccount(leaving).andExpect(status().isNoContent)

        assertFalse(userBudgetRepository.existsById(UserBudgetId(theirs, leaving)))
        assertEquals(member, budgetRepository.findById(theirs).get().ownerEmail)
        val authors = budgetEntryRepository.findByBudgetId(theirs).map { it.createdBy?.email }
        assertEquals(setOf(null, member), authors.toSet())
        perform(get("/api/budgets/$theirs/entries"), member)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `every budget it owns is handed over in one go, each by its own rule`() {
        val byEntry = createBudget(leaving)
        addMember(byEntry, member, by = leaving)
        addMember(byEntry, other, by = leaving)
        createEntry(byEntry, other)
        val byEmail = createBudget(leaving)
        addMember(byEmail, other, by = leaving)
        addMember(byEmail, member, by = leaving)
        val alone = createBudget(leaving)
        aiUsageRepository.createIfMissing(leaving, "2026-11")
        aiUsageRepository.createIfMissing(member, "2026-11")

        deleteAccount(leaving).andExpect(status().isNoContent)

        assertEquals(other, budgetRepository.findById(byEntry).get().ownerEmail)
        assertEquals(member, budgetRepository.findById(byEmail).get().ownerEmail)
        assertFalse(budgetRepository.existsById(alone))
        assertEquals(listOf(member), aiUsageRepository.findAll().map { it.id.userEmail })
    }

    @Test
    fun `an entry it only edited keeps its creator and loses the editor`() {
        val theirs = createBudget(member)
        addMember(theirs, leaving, by = member)
        val entryId = createEntry(theirs, member)
        val edit = UpdateBudgetEntryRequest(amount = BigDecimal("2.00"), type = EntryType.OUTCOME)
        perform(put("/api/budgets/$theirs/entries/$entryId").json(edit), leaving).andExpect(status().isOk)

        deleteAccount(leaving).andExpect(status().isNoContent)

        val entry = budgetEntryRepository.findById(entryId).get()
        assertEquals(member, entry.createdBy?.email)
        assertNull(entry.updatedBy)
        assertEquals(BigDecimal("2.00"), entry.amount)
    }

    @Test
    fun `its tokens stop working and it cannot sign in again`() {
        val session = sessions.getValue(leaving)

        deleteAccount(leaving).andExpect(status().isNoContent)

        perform(get("/api/users/me"), leaving).andExpect(status().isUnauthorized)
        deleteAccount(leaving).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/users/refresh_token").json(RefreshTokenRequest(session.refreshToken)))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/users/sign_in").json(SignInRequest(leaving, PASSWORD)))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `the email can sign up again, as a new account with nothing in it`() {
        createBudget(leaving)
        deleteAccount(leaving).andExpect(status().isNoContent)

        sessions[leaving] = signUpAndIn(leaving)

        perform(get("/api/budgets"), leaving).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `nobody else is touched`() {
        val theirs = createBudget(member)
        addMember(theirs, other, by = member)

        deleteAccount(leaving).andExpect(status().isNoContent)

        assertTrue(userRepository.existsById(member))
        assertTrue(userBudgetRepository.existsById(UserBudgetId(theirs, other)))
    }

    @Test
    fun `it needs an access token`() {
        mockMvc.perform(delete("/api/users/me")).andExpect(status().isUnauthorized)
        assertTrue(userRepository.existsById(leaving))
    }

    // --- Helpers

    private fun deleteAccount(email: String) = perform(delete("/api/users/me"), email)

    private fun signUpAndIn(email: String): SignInResponse {
        mockMvc.perform(post("/api/users/sign_up").json(SignUpRequest(email = email, name = "name", password = PASSWORD)))
        val result = mockMvc.perform(post("/api/users/sign_in").json(SignInRequest(email = email, password = PASSWORD)))
            .andExpect(status().isOk)
            .andReturn()
        return objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)
    }

    private fun createBudget(creator: String): Long {
        val request = CreateBudgetRequest(name = "Budget", amount = BigDecimal("100.00"))
        val result = perform(post("/api/budgets").json(request), creator).andExpect(status().isCreated).andReturn()
        return objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java).id
    }

    private fun addMember(budgetId: Long, email: String, by: String) {
        perform(post("/api/budgets/$budgetId/collaborators").json(AddCollaboratorRequest(budgetId, email)), by)
            .andExpect(status().isCreated)
    }

    private fun createEntry(budgetId: Long, author: String): Long {
        val request = CreateBudgetEntryRequest(amount = BigDecimal("1.00"), description = "by $author", type = EntryType.OUTCOME)
        val result = perform(post("/api/budgets/$budgetId/entries").json(request), author)
            .andExpect(status().isCreated)
            .andReturn()
        return objectMapper.readValue(result.response.contentAsString, BudgetEntryResponse::class.java).id
    }

    private fun perform(request: MockHttpServletRequestBuilder, caller: String): ResultActions =
        mockMvc.perform(request.header("Authorization", "Bearer ${sessions.getValue(caller).authToken}"))

    private fun MockHttpServletRequestBuilder.json(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private companion object {
        const val PASSWORD = "Password123!"
    }
}

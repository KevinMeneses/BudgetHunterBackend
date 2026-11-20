package com.budgethunter.integration

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.EntryType
import com.budgethunter.model.Plan
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.service.BudgetService
import com.budgethunter.sharing.SharingLimitExceededException
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The sharing limits through the whole stack, with the default limits (FREE: one shared budget, one person
 * besides the owner). Not `@Transactional`: the concurrent tests need each addition in its own transaction, so
 * every test uses accounts of its own instead of a rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharingLimitIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var budgetRepository: BudgetRepository

    @Autowired private lateinit var userBudgetRepository: UserBudgetRepository

    @Autowired private lateinit var budgetService: BudgetService

    private val run = UUID.randomUUID().toString().take(8)
    private val tokens = mutableMapOf<String, String>()

    // --- FREE

    @Test
    fun `a free owner shares one budget with one person, and no more`() {
        val (owner, first, second) = accounts("owner", "first", "second")
        val budgetId = createBudget(owner)

        add(budgetId, first, by = owner).andExpect(status().isCreated)

        add(budgetId, second, by = owner)
            .expectLimit("COLLABORATORS_PER_BUDGET", plan = "FREE", limit = 1, used = 1, callerIsOwner = true)

        val otherBudget = createBudget(owner)
        add(otherBudget, second, by = owner)
            .expectLimit("SHARED_BUDGETS", plan = "FREE", limit = 1, used = 1, callerIsOwner = true)

        assertEquals(2, members(budgetId))
        assertEquals(1, members(otherBudget))
    }

    @Test
    fun `a refused addition still answers 404 for an unknown account and 409 for a member`() {
        val (owner, first) = accounts("owner", "first")
        val budgetId = createBudget(owner)
        add(budgetId, first, by = owner).andExpect(status().isCreated)

        add(budgetId, "nobody-$run@example.com", by = owner).andExpect(status().isNotFound)
        add(budgetId, first, by = owner).andExpect(status().isConflict)
    }

    // --- PREMIUM, and when it runs out

    @Test
    fun `a premium owner shares without limits`() {
        val (owner) = accounts("owner")
        val people = accounts("a", "b", "c")
        setPlan(owner, Plan.PREMIUM)

        repeat(3) {
            val budgetId = createBudget(owner)
            people.forEach { add(budgetId, it, by = owner).andExpect(status().isCreated) }
        }

        status(owner)
            .andExpect(jsonPath("$.sharing.plan").value("PREMIUM"))
            .andExpect(jsonPath("$.sharing.sharedBudgetsLimit").doesNotExist())
            .andExpect(jsonPath("$.sharing.collaboratorsPerBudgetLimit").doesNotExist())
            .andExpect(jsonPath("$.sharing.sharedBudgetsUsed").value(3))
    }

    @Test
    fun `a lapsed premium keeps what it shared but counts as free for anything new`() {
        val (owner, a, b) = accounts("owner", "a", "b")
        val c = signUp("c")
        setPlan(owner, Plan.PREMIUM)
        val first = createBudget(owner)
        val second = createBudget(owner)
        listOf(a, b).forEach {
            add(first, it, by = owner).andExpect(status().isCreated)
            add(second, it, by = owner).andExpect(status().isCreated)
        }

        setPlan(owner, Plan.PREMIUM, expiresAt = Instant.now().minus(1, ChronoUnit.DAYS))

        add(first, c, by = owner)
            .expectLimit("COLLABORATORS_PER_BUDGET", plan = "FREE", limit = 1, used = 2, callerIsOwner = true)
        add(createBudget(owner), c, by = owner)
            .expectLimit("SHARED_BUDGETS", plan = "FREE", limit = 1, used = 2, callerIsOwner = true)
        assertEquals(3, members(first))
        assertEquals(3, members(second))
        perform(get("/api/budgets/$first/entries"), a).andExpect(status().isOk)
        status(owner)
            .andExpect(jsonPath("$.sharing.plan").value("FREE"))
            .andExpect(jsonPath("$.sharing.sharedBudgetsLimit").value(1))
            .andExpect(jsonPath("$.sharing.sharedBudgetsUsed").value(2))
    }

    // --- Whose plan decides

    @Test
    fun `a free member can invite to a premium owner's budget`() {
        val (owner, member) = accounts("owner", "member")
        val (a, b) = accounts("a", "b")
        setPlan(owner, Plan.PREMIUM)
        val budgetId = createBudget(owner)
        add(budgetId, member, by = owner).andExpect(status().isCreated)

        add(budgetId, a, by = member).andExpect(status().isCreated)
        add(budgetId, b, by = member).andExpect(status().isCreated)

        // And it cost the member nothing.
        status(member).andExpect(jsonPath("$.sharing.sharedBudgetsUsed").value(0))
    }

    @Test
    fun `a premium member cannot go past a free owner's limit`() {
        val (owner, member, other) = accounts("owner", "member", "other")
        setPlan(member, Plan.PREMIUM)
        val budgetId = createBudget(owner)
        add(budgetId, member, by = owner).andExpect(status().isCreated)

        add(budgetId, other, by = member)
            .expectLimit("COLLABORATORS_PER_BUDGET", plan = "FREE", limit = 1, used = 1, callerIsOwner = false)
    }

    @Test
    fun `being invited does not spend one's own limit`() {
        val (host, guest, friend) = accounts("host", "guest", "friend")
        add(createBudget(host), guest, by = host).andExpect(status().isCreated)

        status(guest)
            .andExpect(jsonPath("$.sharing.plan").value("FREE"))
            .andExpect(jsonPath("$.sharing.sharedBudgetsLimit").value(1))
            .andExpect(jsonPath("$.sharing.sharedBudgetsUsed").value(0))
            .andExpect(jsonPath("$.sharing.collaboratorsPerBudgetLimit").value(1))
        add(createBudget(guest), friend, by = guest).andExpect(status().isCreated)
        status(guest).andExpect(jsonPath("$.sharing.sharedBudgetsUsed").value(1))
    }

    // --- Taking away always works

    @Test
    fun `removing members and leaving work over the limit, and free up room`() {
        val (owner, a, b) = accounts("owner", "a", "b")
        val c = signUp("c")
        setPlan(owner, Plan.PREMIUM)
        val first = createBudget(owner)
        val second = createBudget(owner)
        listOf(a, b).forEach { add(first, it, by = owner).andExpect(status().isCreated) }
        add(second, a, by = owner).andExpect(status().isCreated)
        setPlan(owner, Plan.FREE)

        perform(delete("/api/budgets/$first/collaborators/me"), a).andExpect(status().isNoContent)
        perform(delete("/api/budgets/$first/collaborators/$b"), owner).andExpect(status().isNoContent)
        perform(delete("/api/budgets/$second"), a).andExpect(status().isNoContent)
        assertEquals(1, members(first))
        assertEquals(1, members(second))

        // Nothing shared any more: back within the limit.
        add(first, c, by = owner).andExpect(status().isCreated)
        add(second, c, by = owner)
            .expectLimit("SHARED_BUDGETS", plan = "FREE", limit = 1, used = 1, callerIsOwner = true)
    }

    @Test
    fun `a budget handed over when its owner deletes the account is kept past the new owner's limit`() {
        val (leaving, heir) = accounts("a-leaving", "b-heir")
        val (friend, other) = accounts("c-friend", "d-other")
        add(createBudget(heir), friend, by = heir).andExpect(status().isCreated)
        setPlan(leaving, Plan.PREMIUM)
        val budgetId = createBudget(leaving)
        listOf(heir, other).forEach { add(budgetId, it, by = leaving).andExpect(status().isCreated) }
        perform(
            post("/api/budgets/$budgetId/entries")
                .json(CreateBudgetEntryRequest(amount = BigDecimal.ONE, description = "x", type = EntryType.OUTCOME)),
            heir
        ).andExpect(status().isCreated)

        perform(delete("/api/users/me"), leaving).andExpect(status().isNoContent)

        assertEquals(heir, budgetRepository.findOwnerEmail(budgetId))
        assertEquals(2, members(budgetId))
        status(heir).andExpect(jsonPath("$.sharing.sharedBudgetsUsed").value(2))
    }

    // --- Concurrency

    @Test
    fun `parallel additions to one budget never go past the limit`() {
        val (owner) = accounts("owner")
        val budgetId = budgetService.createBudget(CreateBudgetRequest("Parallel", BigDecimal.TEN), owner).id
        val people = (1..PARALLEL_REQUESTS).map { signUp("p$it") }

        val added = inParallel(people.map { { budgetService.addCollaborator(budgetId, AddCollaboratorRequest(budgetId, it), owner) } })

        assertEquals(1, added)
        assertEquals(2, members(budgetId))
    }

    @Test
    fun `parallel additions to different budgets of one owner share one budget at most`() {
        val (owner) = accounts("owner")
        val people = (1..PARALLEL_REQUESTS).map { signUp("q$it") }
        val budgets = people.map { budgetService.createBudget(CreateBudgetRequest("B", BigDecimal.TEN), owner).id }

        // As from several devices at once: each would be the owner's first shared budget on its own.
        val added = inParallel(
            budgets.zip(people).map { (budgetId, person) ->
                { budgetService.addCollaborator(budgetId, AddCollaboratorRequest(budgetId, person), owner) }
            }
        )

        assertEquals(1, added)
        assertEquals(1, budgetRepository.countSharedBudgetsOwnedBy(owner).toInt())
    }

    // --- Helpers

    /** Runs every call at once; the number that went through. Anything but the sharing limit fails the test. */
    private fun inParallel(calls: List<() -> Any>): Int {
        val pool = Executors.newFixedThreadPool(calls.size)
        try {
            val attempts = calls.map { call ->
                Callable {
                    try {
                        call()
                        true
                    } catch (_: SharingLimitExceededException) {
                        false
                    }
                }
            }
            return pool.invokeAll(attempts).count { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun ResultActions.expectLimit(type: String, plan: String, limit: Int, used: Int, callerIsOwner: Boolean) =
        andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.status").value(402))
            .andExpect(jsonPath("$.code").value("SHARING_LIMIT_REACHED"))
            .andExpect(jsonPath("$.sharing.limitType").value(type))
            .andExpect(jsonPath("$.sharing.plan").value(plan))
            .andExpect(jsonPath("$.sharing.limit").value(limit))
            .andExpect(jsonPath("$.sharing.used").value(used))
            .andExpect(jsonPath("$.sharing.callerIsOwner").value(callerIsOwner))

    private fun accounts(vararg names: String): List<String> = names.map { signUp(it) }

    private fun signUp(name: String): String {
        val email = "$name-$run@example.com"
        val password = "Password123!"
        mockMvc.perform(post("/api/users/sign_up").json(SignUpRequest(email = email, name = name, password = password)))
            .andExpect(status().isCreated)
        val result = mockMvc.perform(post("/api/users/sign_in").json(SignInRequest(email = email, password = password)))
            .andReturn()
        tokens[email] = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java).authToken
        return email
    }

    private fun setPlan(email: String, plan: Plan, expiresAt: Instant? = null) {
        userRepository.updatePlan(email, plan, expiresAt)
    }

    private fun createBudget(owner: String): Long {
        val result = perform(post("/api/budgets").json(CreateBudgetRequest(name = "B", amount = BigDecimal.TEN)), owner)
            .andExpect(status().isCreated)
            .andReturn()
        return objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java).id
    }

    private fun add(budgetId: Long, email: String, by: String): ResultActions =
        perform(post("/api/budgets/$budgetId/collaborators").json(AddCollaboratorRequest(budgetId, email)), by)

    private fun status(email: String): ResultActions =
        perform(get("/api/billing/status"), email).andExpect(status().isOk)

    private fun members(budgetId: Long) = userBudgetRepository.countByBudgetId(budgetId).toInt()

    private fun perform(request: MockHttpServletRequestBuilder, caller: String): ResultActions =
        mockMvc.perform(request.header("Authorization", "Bearer ${tokens.getValue(caller)}"))

    private fun MockHttpServletRequestBuilder.json(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private companion object {
        const val PARALLEL_REQUESTS = 20
    }
}

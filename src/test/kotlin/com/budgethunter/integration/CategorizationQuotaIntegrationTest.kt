package com.budgethunter.integration

import com.budgethunter.categorization.BudgetCategorizationService
import com.budgethunter.categorization.CategorizationMetrics
import com.budgethunter.categorization.CategorizationQuotaService
import com.budgethunter.categorization.CategoryCache
import com.budgethunter.categorization.CategoryClassifier
import com.budgethunter.categorization.CategoryResolver
import com.budgethunter.categorization.RuleBasedClassifier
import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.Plan
import com.budgethunter.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The monthly allowance of AI categorizations through the whole stack, with a fake Gemini in place of the real
 * one: what counts (distinct descriptions sent to the AI), what is free (rules, cache), who pays (the caller),
 * what is given back (descriptions the AI did not answer) and where each plan stops.
 */
@SpringBootTest(
    properties = [
        "categorization.free-monthly-entries=3",
        "categorization.premium-monthly-entries=5"
    ]
)
@AutoConfigureMockMvc
class CategorizationQuotaIntegrationTest {

    @TestConfiguration
    class FakeGemini {
        /** Rules and cache as in production, then a fake remote classifier that records what it was asked. */
        @Bean
        @Primary
        fun fakeResolver(metrics: CategorizationMetrics) = CategoryResolver(
            RuleBasedClassifier(),
            CategoryCache(),
            CategoryClassifier { list ->
                asked.addAll(list)
                list.map { if (it.contains("fail", ignoreCase = true)) null else "GROCERIES" }
            },
            metrics
        )
    }

    companion object {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        const val PARALLEL_REQUESTS = 20
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var quotaService: CategorizationQuotaService

    @Autowired private lateinit var categorizationService: BudgetCategorizationService

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    // Letters only: the normaliser drops tokens with digits, and each test needs descriptions no other has cached.
    private val tag = (1..8).map { 'a' + Random.nextInt(26) }.joinToString("")
    private val owner = "quota-owner-$tag@example.com"
    private val partner = "quota-partner-$tag@example.com"
    private lateinit var ownerToken: String
    private lateinit var partnerToken: String
    private var budgetId = 0L

    @BeforeEach
    fun setup() {
        asked.clear()
        ownerToken = signUpAndIn(owner)
        partnerToken = signUpAndIn(partner)
        setAiProcessing(owner)
        setAiProcessing(partner)
        budgetId = createBudget(ownerToken)
    }

    /**
     * The in-memory database is shared by every test context of the run, and some tests (account deletion) count
     * the whole ai_usage table. These rows are committed (the parallel tests need that), so remove them.
     */
    @AfterEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_usage WHERE user_email IN (?, ?)", owner, partner)
    }

    private fun signUpAndIn(email: String): String {
        mockMvc.perform(
            post("/api/users/sign_up").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(email, "User", "Password123!")))
        )
        val result = mockMvc.perform(
            post("/api/users/sign_in").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(email, "Password123!")))
        ).andReturn()
        return objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java).authToken
    }

    private fun setAiProcessing(email: String) {
        val user = userRepository.findById(email).get()
        user.aiProcessingEnabled = true
        userRepository.save(user)
    }

    private fun setPlan(email: String, plan: Plan, expiresAt: Instant? = null) {
        val user = userRepository.findById(email).get()
        user.plan = plan
        user.planExpiresAt = expiresAt
        userRepository.save(user)
    }

    private fun createBudget(token: String): Long {
        val result = mockMvc.perform(
            post("/api/budgets").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateBudgetRequest("Budget", BigDecimal("100.00"))))
        ).andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asLong()
    }

    private fun createEntries(vararg descriptions: String, token: String = ownerToken, budget: Long = budgetId) {
        descriptions.forEach { description ->
            mockMvc.perform(
                post("/api/budgets/$budget/entries").header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amount":5.00,"description":"$description","type":"OUTCOME"}""")
            ).andExpect(status().isCreated)
        }
    }

    /** [count] different descriptions nobody knows yet. */
    private fun shops(count: Int, prefix: String = "shop") = (1..count).map { "$prefix $tag ${'a' + it}" }

    private fun categorize(token: String = ownerToken): ResultActions =
        mockMvc.perform(post("/api/budgets/$budgetId/entries/categorize").header("Authorization", "Bearer $token"))

    private fun aiUsage(token: String = ownerToken): ResultActions =
        mockMvc.perform(get("/api/ai/usage").header("Authorization", "Bearer $token"))

    @Test
    fun `past the free allowance what fits is categorised, the rest waits, and the response says so`() {
        createEntries(*shops(5).toTypedArray())
        val month = YearMonth.now(ZoneOffset.UTC)

        categorize().andExpect(status().isOk)
            .andExpect(jsonPath("$.categorized").value(3))
            .andExpect(jsonPath("$.pending").value(2))
            .andExpect(jsonPath("$.quotaExceeded").value(true))
            .andExpect(jsonPath("$.usage.plan").value("FREE"))
            .andExpect(jsonPath("$.usage.period").value(month.toString()))
            .andExpect(jsonPath("$.usage.categorizedEntriesUsed").value(3))
            .andExpect(jsonPath("$.usage.categorizedEntriesLimit").value(3))
            .andExpect(jsonPath("$.usage.categorizedEntriesRemaining").value(0))
            .andExpect(jsonPath("$.usage.resetsOn").value(month.plusMonths(1).atDay(1).toString()))
        assertEquals(shops(3), asked)
    }

    @Test
    fun `with the allowance used up the rules still apply and nothing reaches the AI`() {
        createEntries(*shops(3).toTypedArray())
        categorize().andExpect(jsonPath("$.quotaExceeded").value(false))
        asked.clear()
        createEntries("Netflix", "shop $tag zz")

        categorize().andExpect(status().isOk)
            .andExpect(jsonPath("$.categorized").value(1))
            .andExpect(jsonPath("$.pending").value(1))
            .andExpect(jsonPath("$.quotaExceeded").value(true))
            .andExpect(jsonPath("$.usage.categorizedEntriesUsed").value(3))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `entries with the same description are one categorization`() {
        createEntries("Tienda $tag 123", "tienda $tag 456", "TIENDA $tag")

        categorize().andExpect(jsonPath("$.categorized").value(3))
            .andExpect(jsonPath("$.usage.categorizedEntriesUsed").value(1))
    }

    @Test
    fun `what the AI does not answer is given back`() {
        createEntries("fail $tag one", "fail $tag two", "shop $tag ok")

        categorize().andExpect(jsonPath("$.categorized").value(1))
            .andExpect(jsonPath("$.pending").value(2))
            .andExpect(jsonPath("$.quotaExceeded").value(false))
            .andExpect(jsonPath("$.usage.categorizedEntriesUsed").value(1))
        aiUsage().andExpect(jsonPath("$.categorizedEntriesUsed").value(1))
    }

    @Test
    fun `when the AI fails, more waiting than is left is not reported as the allowance running out`() {
        createEntries(*shops(4, "fail").toTypedArray())

        categorize().andExpect(jsonPath("$.categorized").value(0))
            .andExpect(jsonPath("$.pending").value(4))
            .andExpect(jsonPath("$.quotaExceeded").value(false))
            .andExpect(jsonPath("$.usage.categorizedEntriesRemaining").value(3))
        assertEquals(3, asked.size, "only what the allowance granted was sent")
    }

    @Test
    fun `in a shared budget the caller pays, not the entries' creator`() {
        mockMvc.perform(
            post("/api/budgets/$budgetId/collaborators").header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddCollaboratorRequest(budgetId, partner)))
        ).andExpect(status().isCreated)
        createEntries(*shops(2).toTypedArray(), token = partnerToken)

        categorize(ownerToken).andExpect(jsonPath("$.usage.categorizedEntriesUsed").value(2))

        aiUsage(partnerToken).andExpect(jsonPath("$.categorizedEntriesUsed").value(0))
        aiUsage(ownerToken).andExpect(jsonPath("$.categorizedEntriesUsed").value(2))
    }

    @Test
    fun `premium has its own allowance, and a premium past its expiry counts as FREE`() {
        setPlan(owner, Plan.PREMIUM, expiresAt = Instant.now().minusSeconds(60))
        aiUsage().andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.categorizedEntriesLimit").value(3))

        setPlan(owner, Plan.PREMIUM, expiresAt = Instant.now().plusSeconds(3600))
        createEntries(*shops(6).toTypedArray())
        categorize().andExpect(jsonPath("$.categorized").value(5))
            .andExpect(jsonPath("$.quotaExceeded").value(true))
            .andExpect(jsonPath("$.usage.plan").value("PREMIUM"))
            .andExpect(jsonPath("$.usage.categorizedEntriesLimit").value(5))
    }

    @Test
    fun `the AI usage endpoint has both allowances, and the receipts one is unchanged`() {
        createEntries(*shops(2).toTypedArray())
        categorize()

        aiUsage().andExpect(status().isOk)
            .andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.period").value(YearMonth.now(ZoneOffset.UTC).toString()))
            .andExpect(jsonPath("$.receiptScansUsed").value(0))
            .andExpect(jsonPath("$.receiptScansLimit").value(10))
            .andExpect(jsonPath("$.receiptScansRemaining").value(10))
            .andExpect(jsonPath("$.categorizedEntriesUsed").value(2))
            .andExpect(jsonPath("$.categorizedEntriesLimit").value(3))
            .andExpect(jsonPath("$.categorizedEntriesRemaining").value(1))
            .andExpect(jsonPath("$.resetsOn").exists())

        mockMvc.perform(get("/api/receipts/usage").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.receiptScansUsed").value(0))
            .andExpect(jsonPath("$.categorizedEntriesUsed").doesNotExist())
    }

    @Test
    fun `the AI usage endpoint needs a token`() {
        mockMvc.perform(get("/api/ai/usage")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `parallel reservations never go over the allowance`() {
        val pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        val granted = try {
            val attempts = (1..PARALLEL_REQUESTS).map { Callable { quotaService.reserve(owner, 2).granted } }
            pool.invokeAll(attempts).sumOf { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(3, granted)
        aiUsage().andExpect(jsonPath("$.categorizedEntriesUsed").value(3))
    }

    @Test
    fun `two runs at once on different budgets never go over the allowance`() {
        val second = createBudget(ownerToken)
        createEntries(*shops(3, "first").toTypedArray())
        createEntries(*shops(3, "second").toTypedArray(), budget = second)

        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            pool.invokeAll(
                listOf(budgetId, second).map { id -> Callable { categorizationService.categorize(id, owner) } }
            ).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(3, results.sumOf { it.categorized })
        assertEquals(3, asked.size)
        assertTrue(results.any { it.quotaExceeded })
        aiUsage().andExpect(jsonPath("$.categorizedEntriesUsed").value(3))
    }
}

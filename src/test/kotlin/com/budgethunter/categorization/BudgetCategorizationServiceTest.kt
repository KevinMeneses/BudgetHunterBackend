package com.budgethunter.categorization

import com.budgethunter.exception.ForbiddenAccessException
import com.budgethunter.model.User
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.PendingCategorization
import com.budgethunter.repository.UserRepository
import com.budgethunter.service.BudgetService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional

class BudgetCategorizationServiceTest {

    private val email = "a@b.c"
    private val budgetId = 4L

    private class Pending(override val id: Long, override val description: String) : PendingCategorization

    private lateinit var budgetService: BudgetService
    private lateinit var budgets: BudgetRepository
    private lateinit var entries: BudgetEntryRepository
    private lateinit var users: UserRepository
    private val asked = mutableListOf<List<String>>()
    private var remoteAnswers = mapOf("rappi" to "GROCERIES", "dr smith" to "HEALTH", "mystery" to "OTHER")

    private lateinit var service: BudgetCategorizationService

    @BeforeEach
    fun setup() {
        asked.clear()
        budgetService = mockk(relaxed = true)
        budgets = mockk()
        entries = mockk()
        users = mockk()
        every { budgets.existsById(budgetId) } returns true
        every { users.findById(email) } returns Optional.of(User(email = email, name = "A", aiProcessingEnabled = true))
        every { entries.applyAutoCategory(any(), any(), any(), any()) } returns 1
        every { entries.countPendingCategorization(budgetId, "OTHER") } returns 0
        waiting()
        service = serviceWith(RuleBasedClassifier())
    }

    private fun serviceWith(rules: CategoryClassifier) = BudgetCategorizationService(
        CategoryResolver(
            rules,
            CategoryCache(),
            CategoryClassifier { list ->
                asked += list
                list.map { remoteAnswers[DescriptionNormalizer.normalize(it)] }
            }
        ),
        budgetService,
        budgets,
        entries,
        users
    )

    private fun waiting(vararg descriptions: String) {
        every { entries.findPendingCategorization(budgetId, "OTHER", any()) } returns
            descriptions.mapIndexed { index, text -> Pending(index + 1L, text) }
    }

    @Test
    fun `categorises what the rules and the classifier know and counts it`() {
        waiting("Netflix", "Rappi 8841", "Dr Smith")

        val result = service.categorize(budgetId, email)

        assertEquals(3, result.categorized)
        verify { entries.applyAutoCategory(1L, "Netflix", "LEISURE", any<LocalDateTime>()) }
        verify { entries.applyAutoCategory(2L, "Rappi 8841", "GROCERIES", any<LocalDateTime>()) }
        verify { entries.applyAutoCategory(3L, "Dr Smith", "HEALTH", any<LocalDateTime>()) }
    }

    @Test
    fun `asks the classifier once for everything the rules did not know`() {
        waiting("Netflix", "Rappi", "Dr Smith")

        service.categorize(budgetId, email)

        assertEquals(listOf(listOf("Rappi", "Dr Smith")), asked)
    }

    @Test
    fun `reports what is still waiting afterwards`() {
        waiting("Netflix")
        every { entries.countPendingCategorization(budgetId, "OTHER") } returns 7

        assertEquals(7, service.categorize(budgetId, email).pending)
    }

    @Test
    fun `writes nothing for entries nobody could place`() {
        waiting("zzz unknown", "mystery")

        val result = service.categorize(budgetId, email)

        assertEquals(0, result.categorized)
        verify(exactly = 0) { entries.applyAutoCategory(any(), any(), any(), any()) }
    }

    @Test
    fun `an entry a person edited meanwhile is not counted`() {
        waiting("Netflix", "Rappi")
        every { entries.applyAutoCategory(1L, any(), any(), any()) } returns 0

        assertEquals(1, service.categorize(budgetId, email).categorized)
    }

    @Test
    fun `needs access to the budget`() {
        every { budgetService.verifyUserHasAccessToBudget(budgetId, email) } throws ForbiddenAccessException("no")

        assertThrows<ForbiddenAccessException> { service.categorize(budgetId, email) }
        verify(exactly = 0) { entries.findPendingCategorization(any(), any(), any()) }
    }

    @Test
    fun `an unknown budget is a bad request`() {
        every { budgets.existsById(budgetId) } returns false

        assertThrows<IllegalArgumentException> { service.categorize(budgetId, email) }
    }

    @Test
    fun `is refused, and sends nothing, when the user did not turn AI processing on`() {
        waiting("Rappi")
        listOf(false, null).forEach { preference ->
            every { users.findById(email) } returns
                Optional.of(User(email = email, name = "A", aiProcessingEnabled = preference))

            assertThrows<ForbiddenAccessException> { service.categorize(budgetId, email) }
        }

        assertEquals(emptyList<List<String>>(), asked)
        verify(exactly = 0) { entries.findPendingCategorization(any(), any(), any()) }
    }

    @Test
    fun `refuses a second run of the same budget while one is in flight, and lets it go afterwards`() {
        waiting("Rappi")
        var inner: Throwable? = null
        var reentered = false
        lateinit var guarded: BudgetCategorizationService
        guarded = serviceWith(
            CategoryClassifier { list ->
                if (!reentered) {
                    reentered = true
                    // While the first run is working, a second request for the same budget arrives.
                    inner = assertThrows<IllegalStateException> { guarded.categorize(budgetId, email) }
                }
                list.map { null }
            }
        )

        guarded.categorize(budgetId, email)

        assertEquals("Categorization is already running for budget $budgetId", inner?.message)
        // Released afterwards: the same budget can be run again.
        guarded.categorize(budgetId, email)
    }
}

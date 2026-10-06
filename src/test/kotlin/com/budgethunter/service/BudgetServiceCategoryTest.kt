package com.budgethunter.service

import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.PutEntryRequest
import com.budgethunter.dto.UpdateBudgetEntryRequest
import com.budgethunter.model.Budget
import com.budgethunter.model.BudgetEntry
import com.budgethunter.model.CategorySource
import com.budgethunter.model.EntryType
import com.budgethunter.model.User
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional

/**
 * Covers who a stored category is credited to (USER or AUTO) and the placeholder an entry holds
 * while it waits for the AI. The rule depends on the acting user's AI processing preference, where
 * `null` (never saved) must behave like off. Kept apart from [BudgetServiceTest] for detekt's
 * LargeClass threshold.
 */
class BudgetServiceCategoryTest {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var userBudgetRepository: UserBudgetRepository
    private lateinit var userRepository: UserRepository
    private lateinit var budgetEntryRepository: BudgetEntryRepository
    private lateinit var budgetService: BudgetService

    private val email = "test@example.com"
    private val budget = Budget(id = 1L, name = "Test Budget", amount = BigDecimal("1000.00"))
    private val saved = slot<BudgetEntry>()

    @BeforeEach
    fun setup() {
        budgetRepository = mockk()
        userBudgetRepository = mockk()
        userRepository = mockk()
        budgetEntryRepository = mockk()
        budgetService = BudgetService(
            budgetRepository,
            userBudgetRepository,
            userRepository,
            budgetEntryRepository,
            mockk(relaxed = true)
        )

        every { userBudgetRepository.existsById(UserBudgetId(1L, email)) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(budget)
        every { budgetEntryRepository.save(capture(saved)) } answers { saved.captured.copy(id = saved.captured.id ?: 10L) }
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    private fun userWith(aiProcessingEnabled: Boolean?): User {
        val user = User(email = email, name = "Test User", password = "encoded", aiProcessingEnabled = aiProcessingEnabled)
        every { userRepository.findById(email) } returns Optional.of(user)
        return user
    }

    private fun existingEntry(category: String, source: CategorySource) = BudgetEntry(
        id = 5L,
        budget = budget,
        amount = BigDecimal("10.00"),
        description = "coffee",
        category = category,
        categorySource = source,
        type = EntryType.OUTCOME
    ).also { every { budgetEntryRepository.findById(5L) } returns Optional.of(it) }

    private fun create(category: String?) =
        budgetService.createEntry(
            1L,
            CreateBudgetEntryRequest(amount = BigDecimal("10.00"), description = "coffee", category = category, type = EntryType.OUTCOME),
            email
        )

    private fun update(category: String?) =
        budgetService.updateEntry(
            1L,
            5L,
            UpdateBudgetEntryRequest(amount = BigDecimal("10.00"), description = "coffee", category = category, type = EntryType.OUTCOME),
            email
        )

    // Create

    @Test
    fun `create with a category is the users choice whatever the AI preference`() {
        listOf(true, false, null).forEach { preference ->
            userWith(preference)

            val response = create("  Food  ")

            assertEquals("Food", response.category)
            assertEquals(CategorySource.USER, response.categorySource)
            assertEquals(CategorySource.USER, saved.captured.categorySource)
        }
    }

    @Test
    fun `create without a category is left to the AI when the user turned it on`() {
        userWith(true)

        val response = create(null)

        assertEquals("OTHER", response.category)
        assertEquals(CategorySource.AUTO, response.categorySource)
    }

    @Test
    fun `create with a blank category counts as no category`() {
        userWith(true)

        val response = create("   ")

        assertEquals("OTHER", response.category)
        assertEquals(CategorySource.AUTO, response.categorySource)
    }

    @Test
    fun `create without a category is not automatic when AI is off or never saved`() {
        listOf(false, null).forEach { preference ->
            userWith(preference)

            val response = create(null)

            assertEquals("OTHER", response.category)
            assertEquals(CategorySource.USER, response.categorySource)
        }
    }

    // Update

    @Test
    fun `update with a category makes it the users choice even on an automatic entry`() {
        userWith(true)
        existingEntry("OTHER", CategorySource.AUTO)

        val response = update("Health")

        assertEquals("Health", response.category)
        assertEquals(CategorySource.USER, response.categorySource)
    }

    @Test
    fun `update without a category keeps an automatic entry waiting for the AI`() {
        userWith(false)
        existingEntry("FOOD", CategorySource.AUTO)

        val response = update(null)

        assertEquals("FOOD", response.category)
        assertEquals(CategorySource.AUTO, response.categorySource)
    }

    @Test
    fun `update without a category hands a manual entry back to the AI when it is on`() {
        userWith(true)
        existingEntry("Groceries", CategorySource.USER)

        val response = update(null)

        assertEquals("Groceries", response.category)
        assertEquals(CategorySource.AUTO, response.categorySource)
    }

    @Test
    fun `update without a category leaves a manual entry alone when AI is off or never saved`() {
        listOf(false, null).forEach { preference ->
            userWith(preference)
            existingEntry("Groceries", CategorySource.USER)

            val response = update(null)

            assertEquals("Groceries", response.category)
            assertEquals(CategorySource.USER, response.categorySource)
        }
    }

    // Put (upsert)

    @Test
    fun `put without an id and a category behaves like create`() {
        userWith(true)

        val response = budgetService.putEntry(
            PutEntryRequest(budgetId = 1L, amount = BigDecimal("10.00"), description = "coffee", type = EntryType.OUTCOME),
            email
        )

        assertEquals("OTHER", response.category)
        assertEquals(CategorySource.AUTO, response.categorySource)
    }

    @Test
    fun `put with an id and no category behaves like update`() {
        userWith(false)
        existingEntry("FOOD", CategorySource.AUTO)

        val response = budgetService.putEntry(
            PutEntryRequest(id = 5L, budgetId = 1L, amount = BigDecimal("10.00"), description = "coffee", type = EntryType.OUTCOME),
            email
        )

        assertEquals("FOOD", response.category)
        assertEquals(CategorySource.AUTO, response.categorySource)
    }
}

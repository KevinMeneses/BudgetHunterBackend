package com.budgethunter.service

import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.PutEntryRequest
import com.budgethunter.dto.UpdateBudgetEntryRequest
import com.budgethunter.model.Budget
import com.budgethunter.model.BudgetEntry
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
 * Which category an entry is stored with. An entry nobody categorised holds UNCATEGORIZED, and that is the
 * only thing that makes it eligible for automatic categorisation later; the account's AI preference plays no
 * part when saving. Kept apart from [BudgetServiceTest] for detekt's LargeClass threshold.
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
        every { userRepository.findById(email) } returns Optional.of(User(email = email, name = "Test User", password = "encoded"))
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    private fun existingEntry(category: String) = BudgetEntry(
        id = 5L,
        budget = budget,
        amount = BigDecimal("10.00"),
        description = "coffee",
        category = category,
        type = EntryType.OUTCOME
    ).also { every { budgetEntryRepository.findById(5L) } returns Optional.of(it) }

    private fun create(category: String?) =
        budgetService.createEntry(
            1L,
            CreateBudgetEntryRequest(amount = BigDecimal("10.00"), description = "coffee", category = category, type = EntryType.OUTCOME),
            email
        )

    private fun update(category: String?, description: String = "coffee") =
        budgetService.updateEntry(
            1L,
            5L,
            UpdateBudgetEntryRequest(
                amount = BigDecimal("10.00"),
                description = description,
                category = category,
                type = EntryType.OUTCOME
            ),
            email
        )

    // Create

    @Test
    fun `create with a category stores it, trimmed`() {
        val response = create("  Food  ")

        assertEquals("Food", response.category)
        assertEquals("Food", saved.captured.category)
    }

    @Test
    fun `create without a category is uncategorized`() {
        assertEquals("UNCATEGORIZED", create(null).category)
    }

    @Test
    fun `create with a blank category counts as no category`() {
        assertEquals("UNCATEGORIZED", create("   ").category)
    }

    @Test
    fun `picking Sin categoria on purpose is the same as not choosing`() {
        listOf("UNCATEGORIZED", "uncategorized", "  Uncategorized ").forEach { requested ->
            assertEquals("UNCATEGORIZED", create(requested).category)
        }
    }

    @Test
    fun `OTHER is a real choice`() {
        assertEquals("OTHER", create("OTHER").category)
    }

    // Update

    @Test
    fun `update with a category stores it`() {
        existingEntry("UNCATEGORIZED")

        assertEquals("Health", update("Health").category)
    }

    @Test
    fun `update without a category leaves whatever is stored alone, even if the description changed`() {
        listOf("Groceries", "FOOD", "OTHER", "UNCATEGORIZED").forEach { stored ->
            existingEntry(stored)

            assertEquals(stored, update(null).category)
            assertEquals(stored, update(null, description = "something else").category)
        }
    }

    @Test
    fun `update back to Sin categoria makes the entry waiting again`() {
        existingEntry("Groceries")

        assertEquals("UNCATEGORIZED", update("UNCATEGORIZED").category)
    }

    // Put (upsert)

    @Test
    fun `put without an id and a category behaves like create`() {
        val response = budgetService.putEntry(
            PutEntryRequest(budgetId = 1L, amount = BigDecimal("10.00"), description = "coffee", type = EntryType.OUTCOME),
            email
        )

        assertEquals("UNCATEGORIZED", response.category)
    }

    @Test
    fun `put with an id and no category behaves like update`() {
        existingEntry("FOOD")

        val response = budgetService.putEntry(
            PutEntryRequest(id = 5L, budgetId = 1L, amount = BigDecimal("10.00"), description = "coffee", type = EntryType.OUTCOME),
            email
        )

        assertEquals("FOOD", response.category)
    }
}

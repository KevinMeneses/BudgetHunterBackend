package com.budgethunter.service

import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.PutEntryRequest
import com.budgethunter.dto.UpdateBudgetEntryRequest
import com.budgethunter.dto.UpdateBudgetRequest
import com.budgethunter.model.Budget
import com.budgethunter.model.BudgetEntry
import com.budgethunter.model.EntryType
import com.budgethunter.model.User
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/**
 * Covers the user-facing calendar `date` field on budgets and budget entries: stored on create,
 * defaulted to today when the request omits it, preserved on update when the request omits it,
 * and overwritten on update when the request supplies one. Kept separate from
 * [BudgetServiceTest] purely to keep that file within detekt's LargeClass threshold.
 */
class BudgetServiceDateTest {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var userBudgetRepository: UserBudgetRepository
    private lateinit var userRepository: UserRepository
    private lateinit var budgetEntryRepository: BudgetEntryRepository
    private lateinit var reactiveSseService: ReactiveSseService
    private lateinit var budgetService: BudgetService

    private val testUserEmail = "test@example.com"
    private val testUser = User(
        email = testUserEmail,
        name = "Test User",
        password = "encodedPassword"
    )
    private val testBudget = Budget(
        id = 1L,
        name = "Test Budget",
        amount = BigDecimal("1000.00")
    )

    @BeforeEach
    fun setup() {
        budgetRepository = mockk()
        userBudgetRepository = mockk()
        userRepository = mockk()
        budgetEntryRepository = mockk()
        reactiveSseService = mockk(relaxed = true)
        budgetService = BudgetService(
            budgetRepository,
            userBudgetRepository,
            userRepository,
            budgetEntryRepository,
            reactiveSseService
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // CreateBudget date behaviour

    @Test
    fun `createBudget should store the date supplied in the request`() {
        // Given
        val requestedDate = LocalDate.of(2026, 1, 15)
        val request = CreateBudgetRequest(
            name = "Monthly Budget",
            amount = BigDecimal("2500.00"),
            date = requestedDate
        )
        val budgetSlot = slot<Budget>()

        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetRepository.save(capture(budgetSlot)) } answers { budgetSlot.captured.copy(id = 1L) }
        every { userBudgetRepository.save(any()) } returns mockk()

        // When
        val result = budgetService.createBudget(request, testUserEmail)

        // Then
        assertEquals(requestedDate, budgetSlot.captured.date)
        assertEquals(requestedDate, result.date)
    }

    @Test
    fun `createBudget should default the date to today when the request omits it`() {
        // Given
        val request = CreateBudgetRequest(
            name = "Monthly Budget",
            amount = BigDecimal("2500.00"),
            date = null
        )
        val budgetSlot = slot<Budget>()

        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetRepository.save(capture(budgetSlot)) } answers { budgetSlot.captured.copy(id = 1L) }
        every { userBudgetRepository.save(any()) } returns mockk()

        // When
        val result = budgetService.createBudget(request, testUserEmail)

        // Then
        assertEquals(LocalDate.now(), budgetSlot.captured.date)
        assertEquals(LocalDate.now(), result.date)
    }

    // UpdateBudget date behaviour

    @Test
    fun `updateBudget should overwrite the stored date when the request supplies one`() {
        // Given
        val budgetId = 1L
        val requestedDate = LocalDate.of(2026, 3, 1)
        val request = UpdateBudgetRequest(
            name = "Updated Budget Name",
            amount = BigDecimal("3500.00"),
            date = requestedDate
        )
        val existingBudget = Budget(
            id = budgetId,
            name = "Old Budget Name",
            amount = BigDecimal("2000.00"),
            date = LocalDate.of(2025, 1, 1)
        )
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val budgetSlot = slot<Budget>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(budgetId) } returns Optional.of(existingBudget)
        every { budgetRepository.save(capture(budgetSlot)) } answers { budgetSlot.captured }

        // When
        val result = budgetService.updateBudget(budgetId, request, testUserEmail)

        // Then
        assertEquals(requestedDate, budgetSlot.captured.date)
        assertEquals(requestedDate, result.date)
    }

    @Test
    fun `updateBudget should keep the stored date when the request omits it`() {
        // Given
        val budgetId = 1L
        val storedDate = LocalDate.of(2025, 1, 1)
        val request = UpdateBudgetRequest(
            name = "Updated Budget Name",
            amount = BigDecimal("3500.00"),
            date = null
        )
        val existingBudget = Budget(
            id = budgetId,
            name = "Old Budget Name",
            amount = BigDecimal("2000.00"),
            date = storedDate
        )
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val budgetSlot = slot<Budget>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(budgetId) } returns Optional.of(existingBudget)
        every { budgetRepository.save(capture(budgetSlot)) } answers { budgetSlot.captured }

        // When
        val result = budgetService.updateBudget(budgetId, request, testUserEmail)

        // Then - never reset to null, never reset to today: it keeps what was already stored
        assertEquals(storedDate, budgetSlot.captured.date)
        assertEquals(storedDate, result.date)
    }

    // CreateEntry date behaviour

    @Test
    fun `createEntry should store the date supplied in the request`() {
        // Given
        val budgetId = 1L
        val requestedDate = LocalDate.of(2026, 2, 10)
        val request = CreateBudgetEntryRequest(
            amount = BigDecimal("150.00"),
            description = "Groceries",
            category = "Food",
            type = EntryType.OUTCOME,
            date = requestedDate
        )
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val entrySlot = slot<BudgetEntry>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.existsById(budgetId) } returns true
        every { budgetRepository.findById(budgetId) } returns Optional.of(testBudget)
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.save(capture(entrySlot)) } answers { entrySlot.captured.copy(id = 1L) }
        every { reactiveSseService.broadcastEvent(any(), any()) } just Runs

        // When
        val result = budgetService.createEntry(budgetId, request, testUserEmail)

        // Then
        assertEquals(requestedDate, entrySlot.captured.date)
        assertEquals(requestedDate, result.date)
    }

    @Test
    fun `createEntry should default the date to today when the request omits it`() {
        // Given
        val budgetId = 1L
        val request = CreateBudgetEntryRequest(
            amount = BigDecimal("150.00"),
            description = "Groceries",
            category = "Food",
            type = EntryType.OUTCOME,
            date = null
        )
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val entrySlot = slot<BudgetEntry>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.existsById(budgetId) } returns true
        every { budgetRepository.findById(budgetId) } returns Optional.of(testBudget)
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.save(capture(entrySlot)) } answers { entrySlot.captured.copy(id = 1L) }
        every { reactiveSseService.broadcastEvent(any(), any()) } just Runs

        // When
        val result = budgetService.createEntry(budgetId, request, testUserEmail)

        // Then
        assertEquals(LocalDate.now(), entrySlot.captured.date)
        assertEquals(LocalDate.now(), result.date)
    }

    // UpdateEntry date behaviour

    @Test
    fun `updateEntry should overwrite the stored date when the request supplies one`() {
        // Given
        val budgetId = 1L
        val entryId = 5L
        val requestedDate = LocalDate.of(2026, 4, 20)
        val existingEntry = BudgetEntry(
            id = entryId,
            budget = testBudget,
            amount = BigDecimal("100.00"),
            description = "Old Description",
            category = "Old Category",
            type = EntryType.OUTCOME,
            createdBy = testUser,
            creationDate = LocalDateTime.now().minusDays(1),
            modificationDate = LocalDateTime.now().minusDays(1),
            date = LocalDate.of(2025, 1, 1)
        )
        val request = UpdateBudgetEntryRequest(
            amount = BigDecimal("200.00"),
            description = "Updated Description",
            category = "Updated Category",
            type = EntryType.INCOME,
            date = requestedDate
        )
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val entrySlot = slot<BudgetEntry>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.findById(entryId) } returns Optional.of(existingEntry)
        every { budgetEntryRepository.save(capture(entrySlot)) } answers { entrySlot.captured }
        every { reactiveSseService.broadcastEvent(any(), any()) } just Runs

        // When
        val result = budgetService.updateEntry(budgetId, entryId, request, testUserEmail)

        // Then
        assertEquals(requestedDate, entrySlot.captured.date)
        assertEquals(requestedDate, result.date)
    }

    @Test
    fun `updateEntry should keep the stored date when the request omits it`() {
        // Given
        val budgetId = 1L
        val entryId = 5L
        val storedDate = LocalDate.of(2025, 1, 1)
        val existingEntry = BudgetEntry(
            id = entryId,
            budget = testBudget,
            amount = BigDecimal("100.00"),
            description = "Old Description",
            category = "Old Category",
            type = EntryType.OUTCOME,
            createdBy = testUser,
            creationDate = LocalDateTime.now().minusDays(1),
            modificationDate = LocalDateTime.now().minusDays(1),
            date = storedDate
        )
        val request = UpdateBudgetEntryRequest(
            amount = BigDecimal("200.00"),
            description = "Updated Description",
            category = "Updated Category",
            type = EntryType.INCOME,
            date = null
        )
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val entrySlot = slot<BudgetEntry>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.findById(entryId) } returns Optional.of(existingEntry)
        every { budgetEntryRepository.save(capture(entrySlot)) } answers { entrySlot.captured }
        every { reactiveSseService.broadcastEvent(any(), any()) } just Runs

        // When
        val result = budgetService.updateEntry(budgetId, entryId, request, testUserEmail)

        // Then - never reset to null, never reset to today: it keeps what was already stored
        assertEquals(storedDate, entrySlot.captured.date)
        assertEquals(storedDate, result.date)
    }

    // PutEntry date behaviour

    @Test
    fun `putEntry should default the date to today when creating and the request omits it`() {
        // Given
        val request = PutEntryRequest(
            id = null,
            budgetId = 1L,
            amount = BigDecimal("150.00"),
            description = "Groceries",
            category = "Food",
            type = EntryType.OUTCOME,
            date = null
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)
        val entrySlot = slot<BudgetEntry>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.save(capture(entrySlot)) } answers { entrySlot.captured.copy(id = 1L) }
        every { reactiveSseService.broadcastEvent(any(), any()) } just Runs

        // When
        val result = budgetService.putEntry(request, testUserEmail)

        // Then
        assertEquals(LocalDate.now(), entrySlot.captured.date)
        assertEquals(LocalDate.now(), result.date)
    }

    @Test
    fun `putEntry should keep the stored date on update when the request omits it`() {
        // Given
        val storedDate = LocalDate.of(2025, 6, 1)
        val existingEntry = BudgetEntry(
            id = 1L,
            budget = testBudget,
            amount = BigDecimal("100.00"),
            description = "Old Description",
            category = "Old Category",
            type = EntryType.OUTCOME,
            createdBy = testUser,
            creationDate = LocalDateTime.now().minusDays(1),
            modificationDate = LocalDateTime.now().minusDays(1),
            date = storedDate
        )
        val request = PutEntryRequest(
            id = 1L,
            budgetId = 1L,
            amount = BigDecimal("200.00"),
            description = "Updated Description",
            category = "Updated Category",
            type = EntryType.INCOME,
            date = null
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)
        val entrySlot = slot<BudgetEntry>()

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.findById(1L) } returns Optional.of(existingEntry)
        every { budgetEntryRepository.save(capture(entrySlot)) } answers { entrySlot.captured }
        every { reactiveSseService.broadcastEvent(any(), any()) } just Runs

        // When
        val result = budgetService.putEntry(request, testUserEmail)

        // Then
        assertEquals(storedDate, entrySlot.captured.date)
        assertEquals(storedDate, result.date)
    }
}

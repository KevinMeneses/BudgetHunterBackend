package com.budgethunter.service

import com.budgethunter.dto.*
import com.budgethunter.model.*
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

class BudgetServiceTest {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var userBudgetRepository: UserBudgetRepository
    private lateinit var userRepository: UserRepository
    private lateinit var budgetEntryRepository: BudgetEntryRepository
    private lateinit var sseService: SseService
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
        sseService = mockk(relaxed = true)
        budgetService = BudgetService(
            budgetRepository,
            userBudgetRepository,
            userRepository,
            budgetEntryRepository,
            sseService
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // CreateBudget Tests

    @Test
    fun `createBudget should create new budget successfully`() {
        // Given
        val request = CreateBudgetRequest(
            name = "Monthly Budget",
            amount = BigDecimal("2500.00")
        )
        val savedBudget = Budget(
            id = 1L,
            name = request.name,
            amount = request.amount
        )

        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetRepository.save(any()) } returns savedBudget
        every { userBudgetRepository.save(any()) } returns mockk()

        // When
        val result = budgetService.createBudget(request, testUserEmail)

        // Then
        assertEquals(savedBudget.id, result.id)
        assertEquals(request.name, result.name)
        assertEquals(request.amount, result.amount)

        verify(exactly = 1) { userRepository.findById(testUserEmail) }
        verify(exactly = 1) { budgetRepository.save(any()) }
        verify(exactly = 1) { userBudgetRepository.save(any()) }
    }

    @Test
    fun `createBudget should throw exception when user not found`() {
        // Given
        val request = CreateBudgetRequest(
            name = "Monthly Budget",
            amount = BigDecimal("2500.00")
        )

        every { userRepository.findById(testUserEmail) } returns Optional.empty()

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.createBudget(request, testUserEmail)
        }

        assertEquals("User not found", exception.message)
        verify(exactly = 1) { userRepository.findById(testUserEmail) }
        verify(exactly = 0) { budgetRepository.save(any()) }
    }

    // GetBudgetsByUserEmail Tests

    @Test
    fun `getBudgetsByUserEmail should return list of budgets`() {
        // Given
        val budgets = listOf(
            Budget(id = 1L, name = "Budget 1", amount = BigDecimal("1000.00")),
            Budget(id = 2L, name = "Budget 2", amount = BigDecimal("2000.00"))
        )

        every { userBudgetRepository.findBudgetsByUserEmail(testUserEmail) } returns budgets

        // When
        val result = budgetService.getBudgetsByUserEmail(testUserEmail)

        // Then
        assertEquals(2, result.size)
        assertEquals(budgets[0].id, result[0].id)
        assertEquals(budgets[0].name, result[0].name)
        assertEquals(budgets[1].id, result[1].id)
        assertEquals(budgets[1].name, result[1].name)

        verify(exactly = 1) { userBudgetRepository.findBudgetsByUserEmail(testUserEmail) }
    }

    @Test
    fun `getBudgetsByUserEmail should return empty list when user has no budgets`() {
        // Given
        every { userBudgetRepository.findBudgetsByUserEmail(testUserEmail) } returns emptyList()

        // When
        val result = budgetService.getBudgetsByUserEmail(testUserEmail)

        // Then
        assertTrue(result.isEmpty())
        verify(exactly = 1) { userBudgetRepository.findBudgetsByUserEmail(testUserEmail) }
    }

    // AddCollaborator Tests

    @Test
    fun `addCollaborator should add collaborator successfully`() {
        // Given
        val collaboratorEmail = "collaborator@example.com"
        val collaborator = User(
            email = collaboratorEmail,
            name = "Collaborator User",
            password = "encodedPassword"
        )
        val request = AddCollaboratorRequest(
            budgetId = 1L,
            email = collaboratorEmail
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(collaboratorEmail) } returns Optional.of(collaborator)
        every { userBudgetRepository.existsById(UserBudgetId(1L, collaboratorEmail)) } returns false
        every { userBudgetRepository.save(any()) } returns mockk()

        // When
        val result = budgetService.addCollaborator(request, testUserEmail)

        // Then
        assertEquals(1L, result.budgetId)
        assertEquals(testBudget.name, result.budgetName)
        assertEquals(collaboratorEmail, result.collaboratorEmail)
        assertEquals(collaborator.name, result.collaboratorName)

        verify(exactly = 1) { userBudgetRepository.existsById(userBudgetId) }
        verify(exactly = 1) { budgetRepository.findById(1L) }
        verify(exactly = 1) { userRepository.findById(collaboratorEmail) }
        verify(exactly = 1) { userBudgetRepository.save(any()) }
    }

    @Test
    fun `addCollaborator should throw exception when user has no access to budget`() {
        // Given
        val request = AddCollaboratorRequest(
            budgetId = 1L,
            email = "collaborator@example.com"
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns false

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.addCollaborator(request, testUserEmail)
        }

        assertEquals("You don't have access to budget with id: 1", exception.message)
        verify(exactly = 1) { userBudgetRepository.existsById(userBudgetId) }
        verify(exactly = 0) { budgetRepository.findById(any()) }
    }

    @Test
    fun `addCollaborator should throw exception when budget not found`() {
        // Given
        val request = AddCollaboratorRequest(
            budgetId = 999L,
            email = "collaborator@example.com"
        )
        val userBudgetId = UserBudgetId(budgetId = 999L, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(999L) } returns Optional.empty()

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.addCollaborator(request, testUserEmail)
        }

        assertEquals("Budget not found with id: 999", exception.message)
        verify(exactly = 1) { budgetRepository.findById(999L) }
    }

    @Test
    fun `addCollaborator should throw exception when collaborator email not found`() {
        // Given
        val nonExistentEmail = "nonexistent@example.com"
        val request = AddCollaboratorRequest(
            budgetId = 1L,
            email = nonExistentEmail
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(nonExistentEmail) } returns Optional.empty()

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.addCollaborator(request, testUserEmail)
        }

        assertEquals("User not found with email: $nonExistentEmail", exception.message)
        verify(exactly = 1) { userRepository.findById(nonExistentEmail) }
    }

    @Test
    fun `addCollaborator should throw exception when user is already a collaborator`() {
        // Given
        val collaboratorEmail = "existing@example.com"
        val collaborator = User(
            email = collaboratorEmail,
            name = "Existing Collaborator",
            password = "encodedPassword"
        )
        val request = AddCollaboratorRequest(
            budgetId = 1L,
            email = collaboratorEmail
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)
        val collaboratorBudgetId = UserBudgetId(budgetId = 1L, userEmail = collaboratorEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(collaboratorEmail) } returns Optional.of(collaborator)
        every { userBudgetRepository.existsById(collaboratorBudgetId) } returns true

        // When & Then
        val exception = assertThrows<IllegalStateException> {
            budgetService.addCollaborator(request, testUserEmail)
        }

        assertTrue(exception.message!!.contains("is already a collaborator"))
        verify(exactly = 1) { userBudgetRepository.existsById(collaboratorBudgetId) }
        verify(exactly = 0) { userBudgetRepository.save(any()) }
    }

    // GetCollaboratorsByBudgetId Tests

    @Test
    fun `getCollaboratorsByBudgetId should return list of collaborators`() {
        // Given
        val budgetId = 1L
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val collaborators = listOf(
            testUser,
            User(email = "user2@example.com", name = "User 2", password = "pass")
        )

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.existsById(budgetId) } returns true
        every { userBudgetRepository.findUsersByBudgetId(budgetId) } returns collaborators

        // When
        val result = budgetService.getCollaboratorsByBudgetId(budgetId, testUserEmail)

        // Then
        assertEquals(2, result.size)
        assertEquals(collaborators[0].email, result[0].email)
        assertEquals(collaborators[0].name, result[0].name)

        verify(exactly = 1) { userBudgetRepository.findUsersByBudgetId(budgetId) }
    }

    @Test
    fun `getCollaboratorsByBudgetId should throw exception when user has no access`() {
        // Given
        val budgetId = 1L
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns false

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.getCollaboratorsByBudgetId(budgetId, testUserEmail)
        }

        assertEquals("You don't have access to budget with id: $budgetId", exception.message)
    }

    @Test
    fun `getCollaboratorsByBudgetId should throw exception when budget not found`() {
        // Given
        val budgetId = 999L
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.existsById(budgetId) } returns false

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.getCollaboratorsByBudgetId(budgetId, testUserEmail)
        }

        assertEquals("Budget not found with id: $budgetId", exception.message)
    }

    // GetEntriesByBudgetId Tests

    @Test
    fun `getEntriesByBudgetId should return list of entries`() {
        // Given
        val budgetId = 1L
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)
        val now = LocalDateTime.now()
        val entries = listOf(
            BudgetEntry(
                id = 1L,
                budget = testBudget,
                amount = BigDecimal("100.00"),
                description = "Entry 1",
                category = "Food",
                type = EntryType.OUTCOME,
                createdBy = testUser,
                creationDate = now,
                modificationDate = now
            ),
            BudgetEntry(
                id = 2L,
                budget = testBudget,
                amount = BigDecimal("200.00"),
                description = "Entry 2",
                category = "Transport",
                type = EntryType.OUTCOME,
                createdBy = testUser,
                creationDate = now,
                modificationDate = now
            )
        )

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.existsById(budgetId) } returns true
        every { budgetEntryRepository.findByBudgetId(budgetId) } returns entries

        // When
        val result = budgetService.getEntriesByBudgetId(budgetId, testUserEmail)

        // Then
        assertEquals(2, result.size)
        assertEquals(entries[0].id, result[0].id)
        assertEquals(entries[0].amount, result[0].amount)
        assertEquals(entries[0].description, result[0].description)

        verify(exactly = 1) { budgetEntryRepository.findByBudgetId(budgetId) }
    }

    // PutEntry Tests - Create New Entry

    @Test
    fun `putEntry should create new entry successfully`() {
        // Given
        val request = PutEntryRequest(
            id = null,
            budgetId = 1L,
            amount = BigDecimal("150.00"),
            description = "Groceries",
            category = "Food",
            type = EntryType.OUTCOME
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)
        val savedEntry = BudgetEntry(
            id = 1L,
            budget = testBudget,
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            createdBy = testUser,
            creationDate = LocalDateTime.now(),
            modificationDate = LocalDateTime.now()
        )

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.save(any()) } returns savedEntry
        every { sseService.broadcastBudgetEntryEvent(any(), any()) } just Runs

        // When
        val result = budgetService.putEntry(request, testUserEmail)

        // Then
        assertEquals(savedEntry.id, result.id)
        assertEquals(request.amount, result.amount)
        assertEquals(request.description, result.description)
        assertEquals(request.category, result.category)
        assertEquals(request.type, result.type)

        verify(exactly = 1) { budgetEntryRepository.save(any()) }
        verify(exactly = 1) { sseService.broadcastBudgetEntryEvent(1L, any()) }
    }

    @Test
    fun `putEntry should update existing entry successfully`() {
        // Given
        val existingEntry = BudgetEntry(
            id = 1L,
            budget = testBudget,
            amount = BigDecimal("100.00"),
            description = "Old Description",
            category = "Old Category",
            type = EntryType.OUTCOME,
            createdBy = testUser,
            creationDate = LocalDateTime.now().minusDays(1),
            modificationDate = LocalDateTime.now().minusDays(1)
        )
        val request = PutEntryRequest(
            id = 1L,
            budgetId = 1L,
            amount = BigDecimal("200.00"),
            description = "Updated Description",
            category = "Updated Category",
            type = EntryType.INCOME
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)
        val updatedEntry = existingEntry.copy(
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            updatedBy = testUser,
            modificationDate = LocalDateTime.now()
        )

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.findById(1L) } returns Optional.of(existingEntry)
        every { budgetEntryRepository.save(any()) } returns updatedEntry
        every { sseService.broadcastBudgetEntryEvent(any(), any()) } just Runs

        // When
        val result = budgetService.putEntry(request, testUserEmail)

        // Then
        assertEquals(updatedEntry.id, result.id)
        assertEquals(request.amount, result.amount)
        assertEquals(request.description, result.description)

        verify(exactly = 1) { budgetEntryRepository.findById(1L) }
        verify(exactly = 1) { budgetEntryRepository.save(any()) }
        verify(exactly = 1) { sseService.broadcastBudgetEntryEvent(1L, any()) }
    }

    @Test
    fun `putEntry should throw exception when user has no access to budget`() {
        // Given
        val request = PutEntryRequest(
            id = null,
            budgetId = 1L,
            amount = BigDecimal("150.00"),
            description = "Test",
            category = "Test",
            type = EntryType.OUTCOME
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns false

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.putEntry(request, testUserEmail)
        }

        assertEquals("You don't have access to budget with id: 1", exception.message)
    }

    @Test
    fun `putEntry should throw exception when entry does not belong to budget`() {
        // Given
        val differentBudget = Budget(id = 2L, name = "Different Budget", amount = BigDecimal("1000.00"))
        val existingEntry = BudgetEntry(
            id = 1L,
            budget = differentBudget,
            amount = BigDecimal("100.00"),
            description = "Entry",
            category = "Category",
            type = EntryType.OUTCOME,
            createdBy = testUser,
            creationDate = LocalDateTime.now(),
            modificationDate = LocalDateTime.now()
        )
        val request = PutEntryRequest(
            id = 1L,
            budgetId = 1L,
            amount = BigDecimal("200.00"),
            description = "Updated",
            category = "Updated",
            type = EntryType.INCOME
        )
        val userBudgetId = UserBudgetId(budgetId = 1L, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns true
        every { budgetRepository.findById(1L) } returns Optional.of(testBudget)
        every { userRepository.findById(testUserEmail) } returns Optional.of(testUser)
        every { budgetEntryRepository.findById(1L) } returns Optional.of(existingEntry)

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.putEntry(request, testUserEmail)
        }

        assertTrue(exception.message!!.contains("does not belong to budget"))
    }

    // VerifyUserHasAccessToBudget Tests

    @Test
    fun `verifyUserHasAccessToBudget should not throw when user has access`() {
        // Given
        val budgetId = 1L
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns true

        // When & Then - should not throw
        assertDoesNotThrow {
            budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail)
        }

        verify(exactly = 1) { userBudgetRepository.existsById(userBudgetId) }
    }

    @Test
    fun `verifyUserHasAccessToBudget should throw exception when user has no access`() {
        // Given
        val budgetId = 1L
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = testUserEmail)

        every { userBudgetRepository.existsById(userBudgetId) } returns false

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail)
        }

        assertEquals("You don't have access to budget with id: $budgetId", exception.message)
    }
}

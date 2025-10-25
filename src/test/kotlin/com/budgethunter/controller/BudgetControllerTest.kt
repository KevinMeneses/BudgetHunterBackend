package com.budgethunter.controller

import com.budgethunter.dto.*
import com.budgethunter.model.EntryType
import com.budgethunter.service.BudgetService
import com.budgethunter.service.ReactiveSseService
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import java.math.BigDecimal
import java.time.LocalDateTime

class BudgetControllerTest {

    private lateinit var budgetService: BudgetService
    private lateinit var reactiveSseService: ReactiveSseService
    private lateinit var budgetController: BudgetController
    private lateinit var authentication: Authentication

    private val testUserEmail = "test@example.com"

    @BeforeEach
    fun setup() {
        budgetService = mockk()
        reactiveSseService = mockk(relaxed = true)
        budgetController = BudgetController(budgetService, reactiveSseService)
        authentication = mockk()

        // Mock authentication to return test user email
        every { authentication.principal } returns testUserEmail
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // CreateBudget Tests

    @Test
    fun `createBudget should return created status with budget response`() {
        // Given
        val request = CreateBudgetRequest(
            name = "Monthly Budget",
            amount = BigDecimal("2500.00")
        )
        val expectedResponse = BudgetResponse(
            id = 1L,
            name = request.name,
            amount = request.amount
        )

        every { budgetService.createBudget(request, testUserEmail) } returns expectedResponse

        // When
        val response = budgetController.createBudget(request, authentication)

        // Then
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals(1L, response.body?.id)
        verify(exactly = 1) { budgetService.createBudget(request, testUserEmail) }
        verify(exactly = 1) { authentication.principal }
    }

    @Test
    fun `createBudget should propagate exception from service`() {
        // Given
        val request = CreateBudgetRequest(
            name = "Test Budget",
            amount = BigDecimal("1000.00")
        )

        every { budgetService.createBudget(request, testUserEmail) } throws IllegalArgumentException("User not found")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.createBudget(request, authentication)
        }

        assertEquals("User not found", exception.message)
        verify(exactly = 1) { budgetService.createBudget(request, testUserEmail) }
    }

    // GetBudgets Tests

    @Test
    fun `getBudgets should return ok status with list of budgets`() {
        // Given
        val expectedBudgets = listOf(
            BudgetResponse(id = 1L, name = "Budget 1", amount = BigDecimal("1000.00")),
            BudgetResponse(id = 2L, name = "Budget 2", amount = BigDecimal("2000.00"))
        )

        every { budgetService.getBudgetsByUserEmail(testUserEmail) } returns expectedBudgets

        // When
        val response = budgetController.getBudgets(authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedBudgets, response.body)
        assertEquals(2, response.body?.size)
        verify(exactly = 1) { budgetService.getBudgetsByUserEmail(testUserEmail) }
    }

    @Test
    fun `getBudgets should return empty list when user has no budgets`() {
        // Given
        every { budgetService.getBudgetsByUserEmail(testUserEmail) } returns emptyList()

        // When
        val response = budgetController.getBudgets(authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.isEmpty())
        verify(exactly = 1) { budgetService.getBudgetsByUserEmail(testUserEmail) }
    }

    // AddCollaborator Tests

    @Test
    fun `addCollaborator should return created status with collaborator response`() {
        // Given
        val budgetId = 1L
        val request = AddCollaboratorRequest(
            budgetId = budgetId,
            email = "collaborator@example.com"
        )
        val expectedResponse = CollaboratorResponse(
            budgetId = budgetId,
            budgetName = "Test Budget",
            collaboratorEmail = request.email,
            collaboratorName = "Collaborator User"
        )

        every { budgetService.addCollaborator(budgetId, request, testUserEmail) } returns expectedResponse

        // When
        val response = budgetController.addCollaborator(budgetId, request, authentication)

        // Then
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals(request.email, response.body?.collaboratorEmail)
        verify(exactly = 1) { budgetService.addCollaborator(budgetId, request, testUserEmail) }
    }

    @Test
    fun `addCollaborator should propagate exception when user has no access`() {
        // Given
        val request = AddCollaboratorRequest(
            budgetId = 1L,
            email = "collaborator@example.com"
        )

        every { budgetService.addCollaborator(1L, request, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: 1")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.addCollaborator(1L, request, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.addCollaborator(1L, request, testUserEmail) }
    }

    @Test
    fun `addCollaborator should propagate exception when collaborator already exists`() {
        // Given
        val budgetId = 1L
        val request = AddCollaboratorRequest(
            budgetId = budgetId,
            email = "existing@example.com"
        )

        every { budgetService.addCollaborator(budgetId, request, testUserEmail) } throws
            IllegalStateException("User existing@example.com is already a collaborator on budget 1")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            budgetController.addCollaborator(budgetId, request, authentication)
        }

        assertTrue(exception.message!!.contains("already a collaborator"))
        verify(exactly = 1) { budgetService.addCollaborator(budgetId, request, testUserEmail) }
    }

    // GetCollaborators Tests

    @Test
    fun `getCollaborators should return ok status with list of collaborators`() {
        // Given
        val budgetId = 1L
        val expectedCollaborators = listOf(
            UserResponse(email = "user1@example.com", name = "User 1"),
            UserResponse(email = "user2@example.com", name = "User 2")
        )

        every { budgetService.getCollaboratorsByBudgetId(budgetId, testUserEmail) } returns expectedCollaborators

        // When
        val response = budgetController.getCollaborators(budgetId, authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedCollaborators, response.body)
        assertEquals(2, response.body?.size)
        verify(exactly = 1) { budgetService.getCollaboratorsByBudgetId(budgetId, testUserEmail) }
    }

    @Test
    fun `getCollaborators should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L

        every { budgetService.getCollaboratorsByBudgetId(budgetId, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.getCollaborators(budgetId, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.getCollaboratorsByBudgetId(budgetId, testUserEmail) }
    }

    // PutEntry Tests - Testing Legacy Endpoint

    @Test
    fun `putEntryLegacy should return created status when creating new entry`() {
        // Given
        val request = PutEntryRequest(
            id = null,
            budgetId = 1L,
            amount = BigDecimal("150.00"),
            description = "Groceries",
            category = "Food",
            type = EntryType.OUTCOME
        )
        val now = LocalDateTime.now()
        val expectedResponse = BudgetEntryResponse(
            id = 1L,
            budgetId = request.budgetId,
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            createdByEmail = testUserEmail,
            updatedByEmail = null,
            creationDate = now,
            modificationDate = now
        )

        every {
            budgetService.createEntry(
                request.budgetId,
                match { it.amount == request.amount && it.description == request.description },
                testUserEmail
            )
        } returns expectedResponse

        // When
        val response = budgetController.putEntryLegacy(request, authentication)

        // Then
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals(1L, response.body?.id)
        verify(exactly = 1) { budgetService.createEntry(request.budgetId, any(), testUserEmail) }
    }

    @Test
    fun `putEntryLegacy should return ok status when updating existing entry`() {
        // Given
        val request = PutEntryRequest(
            id = 1L,
            budgetId = 1L,
            amount = BigDecimal("200.00"),
            description = "Updated Description",
            category = "Updated Category",
            type = EntryType.INCOME
        )
        val now = LocalDateTime.now()
        val expectedResponse = BudgetEntryResponse(
            id = request.id!!,
            budgetId = request.budgetId,
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            createdByEmail = testUserEmail,
            updatedByEmail = testUserEmail,
            creationDate = now.minusDays(1),
            modificationDate = now
        )

        every {
            budgetService.updateEntry(
                request.budgetId,
                request.id,
                match { it.amount == request.amount && it.description == request.description },
                testUserEmail
            )
        } returns expectedResponse

        // When
        val response = budgetController.putEntryLegacy(request, authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals(testUserEmail, response.body?.updatedByEmail)
        verify(exactly = 1) { budgetService.updateEntry(request.budgetId, request.id, any(), testUserEmail) }
    }

    @Test
    fun `putEntryLegacy should propagate exception when user has no access`() {
        // Given
        val request = PutEntryRequest(
            id = null,
            budgetId = 1L,
            amount = BigDecimal("100.00"),
            description = "Test",
            category = "Test",
            type = EntryType.OUTCOME
        )

        every { budgetService.createEntry(request.budgetId, any(), testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: 1")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.putEntryLegacy(request, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.createEntry(request.budgetId, any(), testUserEmail) }
    }

    // GetEntries Tests

    @Test
    fun `getEntries should return ok status with list of entries`() {
        // Given
        val budgetId = 1L
        val now = LocalDateTime.now()
        val expectedEntries = listOf(
            BudgetEntryResponse(
                id = 1L,
                budgetId = budgetId,
                amount = BigDecimal("100.00"),
                description = "Entry 1",
                category = "Food",
                type = EntryType.OUTCOME,
                createdByEmail = testUserEmail,
                updatedByEmail = null,
                creationDate = now,
                modificationDate = now
            ),
            BudgetEntryResponse(
                id = 2L,
                budgetId = budgetId,
                amount = BigDecimal("200.00"),
                description = "Entry 2",
                category = "Transport",
                type = EntryType.OUTCOME,
                createdByEmail = testUserEmail,
                updatedByEmail = null,
                creationDate = now,
                modificationDate = now
            )
        )

        every { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) } returns expectedEntries

        // When
        val response = budgetController.getEntries(budgetId, authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedEntries, response.body)
        assertEquals(2, response.body?.size)
        verify(exactly = 1) { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) }
    }

    @Test
    fun `getEntries should return empty list when budget has no entries`() {
        // Given
        val budgetId = 1L

        every { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) } returns emptyList()

        // When
        val response = budgetController.getEntries(budgetId, authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(response.body!!.isEmpty())
        verify(exactly = 1) { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) }
    }

    @Test
    fun `getEntries should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L

        every { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.getEntries(budgetId, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) }
    }

    // CreateEntry Tests - Testing new RESTful endpoint

    @Test
    fun `createEntry should return created status with entry response`() {
        // Given
        val budgetId = 1L
        val request = CreateBudgetEntryRequest(
            amount = BigDecimal("150.00"),
            description = "Groceries",
            category = "Food",
            type = EntryType.OUTCOME
        )
        val now = LocalDateTime.now()
        val expectedResponse = BudgetEntryResponse(
            id = 1L,
            budgetId = budgetId,
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            createdByEmail = testUserEmail,
            updatedByEmail = null,
            creationDate = now,
            modificationDate = now
        )

        every { budgetService.createEntry(budgetId, request, testUserEmail) } returns expectedResponse

        // When
        val response = budgetController.createEntry(budgetId, request, authentication)

        // Then
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals(1L, response.body?.id)
        assertEquals(budgetId, response.body?.budgetId)
        verify(exactly = 1) { budgetService.createEntry(budgetId, request, testUserEmail) }
    }

    @Test
    fun `createEntry should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L
        val request = CreateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Test",
            category = "Test",
            type = EntryType.OUTCOME
        )

        every { budgetService.createEntry(budgetId, request, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.createEntry(budgetId, request, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.createEntry(budgetId, request, testUserEmail) }
    }

    // UpdateEntry Tests - Testing new RESTful endpoint

    @Test
    fun `updateEntry should return ok status with updated entry response`() {
        // Given
        val budgetId = 1L
        val entryId = 5L
        val request = UpdateBudgetEntryRequest(
            amount = BigDecimal("200.00"),
            description = "Updated Groceries",
            category = "Food",
            type = EntryType.OUTCOME
        )
        val now = LocalDateTime.now()
        val expectedResponse = BudgetEntryResponse(
            id = entryId,
            budgetId = budgetId,
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            createdByEmail = testUserEmail,
            updatedByEmail = testUserEmail,
            creationDate = now.minusDays(1),
            modificationDate = now
        )

        every { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) } returns expectedResponse

        // When
        val response = budgetController.updateEntry(budgetId, entryId, request, authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals(entryId, response.body?.id)
        assertEquals(testUserEmail, response.body?.updatedByEmail)
        verify(exactly = 1) { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) }
    }

    @Test
    fun `updateEntry should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L
        val entryId = 5L
        val request = UpdateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Test",
            category = "Test",
            type = EntryType.OUTCOME
        )

        every { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.updateEntry(budgetId, entryId, request, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) }
    }

    @Test
    fun `updateEntry should propagate exception when entry not found`() {
        // Given
        val budgetId = 1L
        val entryId = 999L
        val request = UpdateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Test",
            category = "Test",
            type = EntryType.OUTCOME
        )

        every { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) } throws
            IllegalArgumentException("Budget entry not found with id: $entryId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.updateEntry(budgetId, entryId, request, authentication)
        }

        assertTrue(exception.message!!.contains("Budget entry not found"))
        verify(exactly = 1) { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) }
    }

    @Test
    fun `updateEntry should propagate exception when entry does not belong to budget`() {
        // Given
        val budgetId = 1L
        val entryId = 5L
        val request = UpdateBudgetEntryRequest(
            amount = BigDecimal("100.00"),
            description = "Test",
            category = "Test",
            type = EntryType.OUTCOME
        )

        every { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) } throws
            IllegalArgumentException("Budget entry $entryId does not belong to budget $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.updateEntry(budgetId, entryId, request, authentication)
        }

        assertTrue(exception.message!!.contains("does not belong to budget"))
        verify(exactly = 1) { budgetService.updateEntry(budgetId, entryId, request, testUserEmail) }
    }

    // StreamEntries (SSE) Tests - Testing new RESTful endpoint

    @Test
    fun `streamEntries should verify access and return Flux when user has access`() {
        // Given
        val budgetId = 1L

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs

        // When
        val flux = budgetController.streamEntries(budgetId, authentication)

        // Then
        assertNotNull(flux)
        verify(exactly = 1) { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) }
    }

    @Test
    fun `streamEntries should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.streamEntries(budgetId, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) }
    }

    // DeleteBudget Tests

    @Test
    fun `deleteBudget should return no content status when budget deleted successfully`() {
        // Given
        val budgetId = 1L

        every { budgetService.deleteBudget(budgetId, testUserEmail) } just Runs

        // When
        val response = budgetController.deleteBudget(budgetId, authentication)

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertNull(response.body)
        verify(exactly = 1) { budgetService.deleteBudget(budgetId, testUserEmail) }
    }

    @Test
    fun `deleteBudget should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L

        every { budgetService.deleteBudget(budgetId, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.deleteBudget(budgetId, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.deleteBudget(budgetId, testUserEmail) }
    }

    @Test
    fun `deleteBudget should propagate exception when budget not found`() {
        // Given
        val budgetId = 999L

        every { budgetService.deleteBudget(budgetId, testUserEmail) } throws
            IllegalArgumentException("Budget not found with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.deleteBudget(budgetId, authentication)
        }

        assertTrue(exception.message!!.contains("Budget not found"))
        verify(exactly = 1) { budgetService.deleteBudget(budgetId, testUserEmail) }
    }

    // DeleteEntry Tests

    @Test
    fun `deleteEntry should return no content status when entry deleted successfully`() {
        // Given
        val budgetId = 1L
        val entryId = 5L

        every { budgetService.deleteEntry(budgetId, entryId, testUserEmail) } just Runs

        // When
        val response = budgetController.deleteEntry(budgetId, entryId, authentication)

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertNull(response.body)
        verify(exactly = 1) { budgetService.deleteEntry(budgetId, entryId, testUserEmail) }
    }

    @Test
    fun `deleteEntry should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L
        val entryId = 5L

        every { budgetService.deleteEntry(budgetId, entryId, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.deleteEntry(budgetId, entryId, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.deleteEntry(budgetId, entryId, testUserEmail) }
    }

    @Test
    fun `deleteEntry should propagate exception when entry not found`() {
        // Given
        val budgetId = 1L
        val entryId = 999L

        every { budgetService.deleteEntry(budgetId, entryId, testUserEmail) } throws
            IllegalArgumentException("Budget entry not found with id: $entryId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.deleteEntry(budgetId, entryId, authentication)
        }

        assertTrue(exception.message!!.contains("Budget entry not found"))
        verify(exactly = 1) { budgetService.deleteEntry(budgetId, entryId, testUserEmail) }
    }

    @Test
    fun `deleteEntry should propagate exception when entry does not belong to budget`() {
        // Given
        val budgetId = 1L
        val entryId = 5L

        every { budgetService.deleteEntry(budgetId, entryId, testUserEmail) } throws
            IllegalArgumentException("Budget entry $entryId does not belong to budget $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.deleteEntry(budgetId, entryId, authentication)
        }

        assertTrue(exception.message!!.contains("does not belong to budget"))
        verify(exactly = 1) { budgetService.deleteEntry(budgetId, entryId, testUserEmail) }
    }

    // RemoveCollaborator Tests

    @Test
    fun `removeCollaborator should return no content status when collaborator removed successfully`() {
        // Given
        val budgetId = 1L
        val collaboratorEmail = "collaborator@example.com"

        every { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) } just Runs

        // When
        val response = budgetController.removeCollaborator(budgetId, collaboratorEmail, authentication)

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertNull(response.body)
        verify(exactly = 1) { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) }
    }

    @Test
    fun `removeCollaborator should propagate exception when user has no access`() {
        // Given
        val budgetId = 999L
        val collaboratorEmail = "collaborator@example.com"

        every { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.removeCollaborator(budgetId, collaboratorEmail, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) }
    }

    @Test
    fun `removeCollaborator should propagate exception when collaborator not found`() {
        // Given
        val budgetId = 1L
        val collaboratorEmail = "nonexistent@example.com"

        every { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) } throws
            IllegalArgumentException("User $collaboratorEmail is not a collaborator on budget $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.removeCollaborator(budgetId, collaboratorEmail, authentication)
        }

        assertTrue(exception.message!!.contains("is not a collaborator"))
        verify(exactly = 1) { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) }
    }

    @Test
    fun `removeCollaborator should propagate exception when trying to remove last collaborator`() {
        // Given
        val budgetId = 1L
        val collaboratorEmail = "last@example.com"

        every { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) } throws
            IllegalStateException("Cannot remove the last collaborator from budget $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            budgetController.removeCollaborator(budgetId, collaboratorEmail, authentication)
        }

        assertTrue(exception.message!!.contains("Cannot remove the last collaborator"))
        verify(exactly = 1) { budgetService.removeCollaborator(budgetId, collaboratorEmail, testUserEmail) }
    }

    // Legacy SSE endpoint test
    @Test
    fun `newEntryLegacy should return Flux when user has access`() {
        // Given
        val budgetId = 1L

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs

        // When
        val flux = budgetController.newEntryLegacy(budgetId, authentication)

        // Then
        assertNotNull(flux)
        verify(exactly = 1) { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) }
    }

    // Integration-like Tests

    @Test
    fun `complete budget workflow should work correctly`() {
        // Given - Create a budget
        val createRequest = CreateBudgetRequest(
            name = "Test Budget",
            amount = BigDecimal("1000.00")
        )
        val budgetResponse = BudgetResponse(
            id = 1L,
            name = createRequest.name,
            amount = createRequest.amount
        )

        every { budgetService.createBudget(createRequest, testUserEmail) } returns budgetResponse

        // When - Create budget
        val createResponse = budgetController.createBudget(createRequest, authentication)

        // Then - Verify budget created
        assertEquals(HttpStatus.CREATED, createResponse.statusCode)
        val budgetId = createResponse.body?.id!!

        // Given - Add an entry
        val entryRequest = PutEntryRequest(
            id = null,
            budgetId = budgetId,
            amount = BigDecimal("50.00"),
            description = "Test Entry",
            category = "Test",
            type = EntryType.OUTCOME
        )
        val entryResponse = BudgetEntryResponse(
            id = 1L,
            budgetId = budgetId,
            amount = entryRequest.amount,
            description = entryRequest.description,
            category = entryRequest.category,
            type = entryRequest.type,
            createdByEmail = testUserEmail,
            updatedByEmail = null,
            creationDate = LocalDateTime.now(),
            modificationDate = LocalDateTime.now()
        )

        every {
            budgetService.createEntry(
                budgetId,
                match { it.amount == entryRequest.amount && it.description == entryRequest.description },
                testUserEmail
            )
        } returns entryResponse

        // When - Add entry
        val entryResult = budgetController.putEntryLegacy(entryRequest, authentication)

        // Then - Verify entry created
        assertEquals(HttpStatus.CREATED, entryResult.statusCode)
        assertEquals(entryRequest.amount, entryResult.body?.amount)

        verify(exactly = 1) { budgetService.createBudget(createRequest, testUserEmail) }
        verify(exactly = 1) { budgetService.createEntry(budgetId, any(), testUserEmail) }
    }
}

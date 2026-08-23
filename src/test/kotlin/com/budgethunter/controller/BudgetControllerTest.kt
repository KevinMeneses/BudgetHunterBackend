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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

class BudgetControllerTest {

    private lateinit var budgetService: BudgetService
    private lateinit var reactiveSseService: ReactiveSseService
    private lateinit var budgetController: BudgetController
    private lateinit var authentication: Authentication

    private val testUserEmail = "test@example.com"

    /** Window used to collect what a stream emits before the 15s heartbeat repeats. */
    private val COLLECT_WINDOW: Duration = Duration.ofMillis(500)

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
        val response = budgetController.getBudgets(null, null, "id", "asc", authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        @Suppress("UNCHECKED_CAST")
        val budgets = response.body as List<BudgetResponse>
        assertEquals(expectedBudgets, budgets)
        assertEquals(2, budgets.size)
        verify(exactly = 1) { budgetService.getBudgetsByUserEmail(testUserEmail) }
    }

    @Test
    fun `getBudgets should return empty list when user has no budgets`() {
        // Given
        every { budgetService.getBudgetsByUserEmail(testUserEmail) } returns emptyList()

        // When
        val response = budgetController.getBudgets(null, null, "id", "asc", authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        @Suppress("UNCHECKED_CAST")
        val budgets = response.body as List<BudgetResponse>
        assertTrue(budgets.isEmpty())
        verify(exactly = 1) { budgetService.getBudgetsByUserEmail(testUserEmail) }
    }

    // UpdateBudget Tests

    @Test
    fun `updateBudget should return ok status with updated budget response`() {
        // Given
        val budgetId = 1L
        val request = UpdateBudgetRequest(
            name = "Updated Budget Name",
            amount = BigDecimal("3500.00")
        )
        val expectedResponse = BudgetResponse(
            id = budgetId,
            name = request.name,
            amount = request.amount
        )

        every { budgetService.updateBudget(budgetId, request, testUserEmail) } returns expectedResponse

        // When
        val response = budgetController.updateBudget(budgetId, request, authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals(budgetId, response.body?.id)
        assertEquals(request.name, response.body?.name)
        assertEquals(request.amount, response.body?.amount)
        verify(exactly = 1) { budgetService.updateBudget(budgetId, request, testUserEmail) }
        verify(exactly = 1) { authentication.principal }
    }

    @Test
    fun `updateBudget should propagate exception when budget not found`() {
        // Given
        val budgetId = 999L
        val request = UpdateBudgetRequest(
            name = "Updated Budget",
            amount = BigDecimal("2000.00")
        )

        every { budgetService.updateBudget(budgetId, request, testUserEmail) } throws
            IllegalArgumentException("Budget not found with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.updateBudget(budgetId, request, authentication)
        }

        assertEquals("Budget not found with id: $budgetId", exception.message)
        verify(exactly = 1) { budgetService.updateBudget(budgetId, request, testUserEmail) }
    }

    @Test
    fun `updateBudget should propagate exception when user has no access`() {
        // Given
        val budgetId = 1L
        val request = UpdateBudgetRequest(
            name = "Updated Budget",
            amount = BigDecimal("2000.00")
        )

        every { budgetService.updateBudget(budgetId, request, testUserEmail) } throws
            IllegalArgumentException("You don't have access to budget with id: $budgetId")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            budgetController.updateBudget(budgetId, request, authentication)
        }

        assertTrue(exception.message!!.contains("don't have access"))
        verify(exactly = 1) { budgetService.updateBudget(budgetId, request, testUserEmail) }
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
        val response = budgetController.getEntries(budgetId, null, null, "modificationDate", "desc", authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        @Suppress("UNCHECKED_CAST")
        val entries = response.body as List<BudgetEntryResponse>
        assertEquals(expectedEntries, entries)
        assertEquals(2, entries.size)
        verify(exactly = 1) { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) }
    }

    @Test
    fun `getEntries should return empty list when budget has no entries`() {
        // Given
        val budgetId = 1L

        every { budgetService.getEntriesByBudgetId(budgetId, testUserEmail) } returns emptyList()

        // When
        val response = budgetController.getEntries(budgetId, null, null, "modificationDate", "desc", authentication)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        @Suppress("UNCHECKED_CAST")
        val entries = response.body as List<BudgetEntryResponse>
        assertTrue(entries.isEmpty())
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
            budgetController.getEntries(budgetId, null, null, "modificationDate", "desc", authentication)
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
        val response = MockHttpServletResponse()

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs

        // When
        val flux = budgetController.streamEntries(budgetId, authentication, response)

        // Then
        assertNotNull(flux)
        verify(exactly = 1) { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) }
    }

    @Test
    fun `streamEntries should disable proxy buffering via X-Accel-Buffering header`() {
        // Given
        val budgetId = 1L
        val response = MockHttpServletResponse()

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs

        // When
        budgetController.streamEntries(budgetId, authentication, response)

        // Then - nginx must not buffer this response, otherwise nothing reaches the client
        assertEquals("no", response.getHeader(BudgetController.X_ACCEL_BUFFERING_HEADER))
    }

    @Test
    fun `streamEntries should emit a keep-alive comment immediately on subscribe`() {
        // Given - no budget events at all, only the heartbeat can emit
        val budgetId = 1L
        val response = MockHttpServletResponse()

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs
        every { reactiveSseService.subscribeToEvents(budgetId) } returns Flux.never()

        // When
        val flux = budgetController.streamEntries(budgetId, authentication, response)

        // Then - the first heartbeat arrives right away, well before the 15s interval
        StepVerifier.create(flux)
            .assertNext { event -> assertEquals("keep-alive", event.comment()) }
            .thenCancel()
            .verify(Duration.ofSeconds(2))
    }

    @Test
    fun `streamEntries should not echo a user's own events back to them`() {
        // Given - the sink carries one event per action, all authored by the subscriber
        val budgetId = 1L
        val ownEvents = BudgetEntryAction.entries.map { action -> event(action, testUserEmail) }

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs
        every { reactiveSseService.subscribeToEvents(budgetId) } returns Flux.fromIterable(ownEvents)

        // When
        val flux = budgetController.streamEntries(budgetId, authentication, MockHttpServletResponse())
        val emitted = flux.take(COLLECT_WINDOW).collectList().block()!!

        // Then - only keep-alives get through; re-sending these would make the author
        // re-sync the list they just wrote and notify them about themselves
        assertTrue(
            emitted.all { it.data() == null },
            "Author received their own events: ${emitted.mapNotNull { it.data() }}"
        )
    }

    @Test
    fun `streamEntries should deliver events authored by other collaborators`() {
        // Given - same three actions, authored by somebody else
        val budgetId = 1L
        val otherEmail = "collaborator@example.com"
        val otherEvents = BudgetEntryAction.entries.map { action -> event(action, otherEmail) }

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs
        every { reactiveSseService.subscribeToEvents(budgetId) } returns Flux.fromIterable(otherEvents)

        // When
        val flux = budgetController.streamEntries(budgetId, authentication, MockHttpServletResponse())
        val emitted = flux.take(COLLECT_WINDOW).collectList().block()!!

        // Then - all three actions arrive, tagged as budget-entry events
        val delivered = emitted.mapNotNull { it.data() }
        assertEquals(BudgetEntryAction.entries.toList(), delivered.map { it.action })
        assertTrue(delivered.all { it.userInfo.email == otherEmail })
        assertTrue(emitted.filter { it.data() != null }.all { it.event() == "budget-entry" })
    }

    @Test
    fun `streamEntries should filter per subscriber, not per budget`() {
        // Given - a mixed stream: the subscriber's own event between two from other users
        val budgetId = 1L
        val mixed = listOf(
            event(BudgetEntryAction.CREATED, "first@example.com"),
            event(BudgetEntryAction.CREATED, testUserEmail),
            event(BudgetEntryAction.CREATED, "second@example.com")
        )

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } just Runs
        every { reactiveSseService.subscribeToEvents(budgetId) } returns Flux.fromIterable(mixed)

        // When
        val flux = budgetController.streamEntries(budgetId, authentication, MockHttpServletResponse())
        val emitted = flux.take(COLLECT_WINDOW).collectList().block()!!

        // Then - only the subscriber's own event is dropped
        assertEquals(
            listOf("first@example.com", "second@example.com"),
            emitted.mapNotNull { it.data() }.map { it.userInfo.email }
        )
    }

    private fun event(action: BudgetEntryAction, authorEmail: String) = BudgetEntryEvent(
        budgetId = 1L,
        entryId = 99L,
        action = action,
        userInfo = UserEventInfo(email = authorEmail, name = "Author")
    )

    @Test
    fun `streamEntries should not set X-Accel-Buffering header when access is denied`() {
        // Given
        val budgetId = 999L
        val response = MockHttpServletResponse()

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } throws
            com.budgethunter.exception.ForbiddenAccessException("You don't have access to budget with id: $budgetId")

        // When
        budgetController.streamEntries(budgetId, authentication, response)

        // Then - the header belongs to the stream, not to the error response
        assertNull(response.getHeader(BudgetController.X_ACCEL_BUFFERING_HEADER))
    }

    @Test
    fun `streamEntries should return error Flux when user has no access`() {
        // Given
        val budgetId = 999L
        val expectedException = com.budgethunter.exception.ForbiddenAccessException("You don't have access to budget with id: $budgetId")

        every { budgetService.verifyUserHasAccessToBudget(budgetId, testUserEmail) } throws expectedException

        // When
        val flux = budgetController.streamEntries(budgetId, authentication, MockHttpServletResponse())

        // Then - Flux should be an error Flux containing the exception
        assertNotNull(flux)
        flux.subscribe(
            { },
            { error ->
                // Verify the error is the expected exception
                assertTrue(error is com.budgethunter.exception.ForbiddenAccessException)
                assertTrue(error.message!!.contains("don't have access"))
            }
        )

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

}

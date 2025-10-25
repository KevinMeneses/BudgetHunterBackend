package com.budgethunter.controller

import com.budgethunter.dto.*
import com.budgethunter.service.BudgetService
import com.budgethunter.service.ReactiveSseService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
@RequestMapping("/api/budgets")
class BudgetController(
    private val budgetService: BudgetService,
    private val reactiveSseService: ReactiveSseService
) {

    @PostMapping
    fun createBudget(
        @Valid @RequestBody request: CreateBudgetRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.createBudget(request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    fun getBudgets(authentication: Authentication): ResponseEntity<List<BudgetResponse>> {
        val userEmail = authentication.principal as String
        val budgets = budgetService.getBudgetsByUserEmail(userEmail)
        return ResponseEntity.ok(budgets)
    }

    @PostMapping("/{budgetId}/collaborators")
    fun addCollaborator(
        @PathVariable budgetId: Long,
        @Valid @RequestBody request: AddCollaboratorRequest,
        authentication: Authentication
    ): ResponseEntity<CollaboratorResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.addCollaborator(budgetId, request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{budgetId}/collaborators")
    fun getCollaborators(
        @PathVariable budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<List<UserResponse>> {
        val userEmail = authentication.principal as String
        val collaborators = budgetService.getCollaboratorsByBudgetId(budgetId, userEmail)
        return ResponseEntity.ok(collaborators)
    }

    @PostMapping("/{budgetId}/entries")
    fun createEntry(
        @PathVariable budgetId: Long,
        @Valid @RequestBody request: CreateBudgetEntryRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetEntryResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.createEntry(budgetId, request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PutMapping("/{budgetId}/entries/{entryId}")
    fun updateEntry(
        @PathVariable budgetId: Long,
        @PathVariable entryId: Long,
        @Valid @RequestBody request: UpdateBudgetEntryRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetEntryResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.updateEntry(budgetId, entryId, request, userEmail)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{budgetId}/entries")
    fun getEntries(
        @PathVariable budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<List<BudgetEntryResponse>> {
        val userEmail = authentication.principal as String
        val entries = budgetService.getEntriesByBudgetId(budgetId, userEmail)
        return ResponseEntity.ok(entries)
    }

    @GetMapping("/{budgetId}/entries/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEntries(
        @PathVariable budgetId: Long,
        authentication: Authentication
    ): Flux<ServerSentEvent<BudgetEntryEvent>> {
        val userEmail = authentication.principal as String
        budgetService.verifyUserHasAccessToBudget(budgetId, userEmail)

        return reactiveSseService.subscribeToEvents(budgetId)
            .map { event ->
                ServerSentEvent.builder(event)
                    .event("budget-entry")
                    .build()
            }
    }

    @DeleteMapping("/{budgetId}")
    fun deleteBudget(
        @PathVariable budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val userEmail = authentication.principal as String
        budgetService.deleteBudget(budgetId, userEmail)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{budgetId}/entries/{entryId}")
    fun deleteEntry(
        @PathVariable budgetId: Long,
        @PathVariable entryId: Long,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val userEmail = authentication.principal as String
        budgetService.deleteEntry(budgetId, entryId, userEmail)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{budgetId}/collaborators/{collaboratorEmail}")
    fun removeCollaborator(
        @PathVariable budgetId: Long,
        @PathVariable collaboratorEmail: String,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val userEmail = authentication.principal as String
        budgetService.removeCollaborator(budgetId, collaboratorEmail, userEmail)
        return ResponseEntity.noContent().build()
    }
}

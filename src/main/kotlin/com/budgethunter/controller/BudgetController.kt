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

    // Legacy endpoints for backward compatibility
    @Deprecated("Use POST /{budgetId}/collaborators instead")
    @PostMapping("/add_collaborator")
    fun addCollaboratorLegacy(
        @Valid @RequestBody request: AddCollaboratorRequest,
        authentication: Authentication
    ): ResponseEntity<CollaboratorResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.addCollaborator(request.budgetId, request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @Deprecated("Use GET /{budgetId}/collaborators instead")
    @GetMapping("/get_collaborators")
    fun getCollaboratorsLegacy(
        @RequestParam budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<List<UserResponse>> {
        val userEmail = authentication.principal as String
        val collaborators = budgetService.getCollaboratorsByBudgetId(budgetId, userEmail)
        return ResponseEntity.ok(collaborators)
    }

    @Deprecated("Use GET /{budgetId}/entries instead")
    @GetMapping("/get_entries")
    fun getEntriesLegacy(
        @RequestParam budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<List<BudgetEntryResponse>> {
        val userEmail = authentication.principal as String
        val entries = budgetService.getEntriesByBudgetId(budgetId, userEmail)
        return ResponseEntity.ok(entries)
    }

    @Deprecated("Use POST /api/budgets instead")
    @PostMapping("/create_budget")
    fun createBudgetLegacy(
        @Valid @RequestBody request: CreateBudgetRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.createBudget(request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @Deprecated("Use GET /api/budgets instead")
    @GetMapping("/get_budgets")
    fun getBudgetsLegacy(authentication: Authentication): ResponseEntity<List<BudgetResponse>> {
        val userEmail = authentication.principal as String
        val budgets = budgetService.getBudgetsByUserEmail(userEmail)
        return ResponseEntity.ok(budgets)
    }

    @Deprecated("Use PUT /{budgetId}/entries/{entryId} or POST /{budgetId}/entries")
    @PutMapping("/put_entry")
    fun putEntryLegacy(
        @Valid @RequestBody request: PutEntryRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetEntryResponse> {
        val userEmail = authentication.principal as String
        if (request.id == null) {
            val createRequest = CreateBudgetEntryRequest(
                amount = request.amount,
                description = request.description,
                category = request.category,
                type = request.type
            )
            val response = budgetService.createEntry(request.budgetId, createRequest, userEmail)
            return ResponseEntity.status(HttpStatus.CREATED).body(response)
        } else {
            val updateRequest = UpdateBudgetEntryRequest(
                amount = request.amount,
                description = request.description,
                category = request.category,
                type = request.type
            )
            val response = budgetService.updateEntry(request.budgetId, request.id, updateRequest, userEmail)
            return ResponseEntity.ok(response)
        }
    }

    @Deprecated("Use GET /{budgetId}/entries/stream instead")
    @GetMapping("/new_entry")
    fun newEntryLegacy(
        @RequestParam budgetId: Long,
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
}

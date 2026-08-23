package com.budgethunter.controller

import com.budgethunter.dto.*
import com.budgethunter.service.BudgetService
import com.budgethunter.service.ReactiveSseService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import java.time.Duration

@RestController
@RequestMapping("/api/budgets")
@Tag(name = "Budget Management", description = "Endpoints for managing budgets, collaborators, and budget entries with real-time SSE notifications")
@SecurityRequirement(name = "bearerAuth")
class BudgetController(
    private val budgetService: BudgetService,
    private val reactiveSseService: ReactiveSseService
) {

    companion object {
        /**
         * nginx-specific header: disables `proxy_buffering` for a single response,
         * without having to change the server configuration.
         */
        const val X_ACCEL_BUFFERING_HEADER = "X-Accel-Buffering"

        /** Interval between SSE keep-alive comments on the entries stream. */
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(15)
    }

    @PostMapping
    @Operation(
        summary = "Create a new budget",
        description = "Creates a new budget with the specified name and amount. The authenticated user becomes the budget owner and is automatically added as a collaborator."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Budget successfully created",
                content = [Content(schema = Schema(implementation = BudgetResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors (e.g., missing name, invalid amount)",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            )
        ]
    )
    fun createBudget(
        @Valid @RequestBody request: CreateBudgetRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.createBudget(request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    @Operation(
        summary = "Get all budgets for authenticated user",
        description = "Retrieves all budgets that the authenticated user has access to, including owned budgets and budgets where the user is a collaborator. Supports optional pagination via query parameters."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved budgets list",
                content = [Content(schema = Schema(implementation = BudgetResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            )
        ]
    )
    fun getBudgets(
        @Parameter(description = "Page number (0-indexed). If not provided, returns all results", required = false)
        @RequestParam(required = false) page: Int?,
        @Parameter(description = "Number of items per page", required = false, example = "20")
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "Field to sort by (id, name, amount)", required = false, example = "id")
        @RequestParam(required = false, defaultValue = "id") sortBy: String,
        @Parameter(description = "Sort direction (asc or desc)", required = false, example = "asc")
        @RequestParam(required = false, defaultValue = "asc") sortDirection: String,
        authentication: Authentication
    ): ResponseEntity<*> {
        val userEmail = authentication.principal as String

        return if (page != null && size != null) {
            // Return paginated response
            val paginatedBudgets = budgetService.getBudgetsByUserEmail(userEmail, page, size, sortBy, sortDirection)
            ResponseEntity.ok(paginatedBudgets)
        } else {
            // Return all results (legacy behavior)
            val budgets = budgetService.getBudgetsByUserEmail(userEmail)
            ResponseEntity.ok(budgets)
        }
    }

    @PutMapping("/{budgetId}")
    @Operation(
        summary = "Update a budget",
        description = "Updates an existing budget's name and/or amount. Only users with access to the budget can update it."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Budget successfully updated",
                content = [Content(schema = Schema(implementation = BudgetResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors (e.g., missing name, invalid amount)",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found",
                content = [Content()]
            )
        ]
    )
    fun updateBudget(
        @Parameter(description = "ID of the budget to update", required = true)
        @PathVariable budgetId: Long,
        @Valid @RequestBody request: UpdateBudgetRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.updateBudget(budgetId, request, userEmail)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/{budgetId}/collaborators")
    @Operation(
        summary = "Add a collaborator to a budget",
        description = "Adds a new collaborator to the budget by email. The user must exist in the system. Only users with access to the budget can add collaborators."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Collaborator successfully added",
                content = [Content(schema = Schema(implementation = CollaboratorResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors (e.g., invalid email format)",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found or collaborator user not found",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "409",
                description = "Collaborator already has access to this budget",
                content = [Content()]
            )
        ]
    )
    fun addCollaborator(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        @Valid @RequestBody request: AddCollaboratorRequest,
        authentication: Authentication
    ): ResponseEntity<CollaboratorResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.addCollaborator(budgetId, request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{budgetId}/collaborators")
    @Operation(
        summary = "Get all collaborators for a budget",
        description = "Retrieves the list of all users who have access to the specified budget. Only users with access to the budget can view its collaborators."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved collaborators list",
                content = [Content(schema = Schema(implementation = UserResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found",
                content = [Content()]
            )
        ]
    )
    fun getCollaborators(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<List<UserResponse>> {
        val userEmail = authentication.principal as String
        val collaborators = budgetService.getCollaboratorsByBudgetId(budgetId, userEmail)
        return ResponseEntity.ok(collaborators)
    }

    @PostMapping("/{budgetId}/entries")
    @Operation(
        summary = "Create a budget entry",
        description = "Creates a new income or expense entry within the budget. Type must be either INCOME or EXPENSE. Entry is tracked with creation timestamp and creator information. Triggers SSE notification to all connected clients."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Budget entry successfully created",
                content = [Content(schema = Schema(implementation = BudgetEntryResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors (e.g., missing amount, invalid type, invalid category)",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found",
                content = [Content()]
            )
        ]
    )
    fun createEntry(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        @Valid @RequestBody request: CreateBudgetEntryRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetEntryResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.createEntry(budgetId, request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PutMapping("/{budgetId}/entries/{entryId}")
    @Operation(
        summary = "Update a budget entry",
        description = "Updates an existing budget entry. Can modify amount, description, category, and type. Update is tracked with modification timestamp and updater information. Triggers SSE notification to all connected clients."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Budget entry successfully updated",
                content = [Content(schema = Schema(implementation = BudgetEntryResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors (e.g., invalid amount, invalid type, invalid category)",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget or entry not found",
                content = [Content()]
            )
        ]
    )
    fun updateEntry(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        @Parameter(description = "ID of the budget entry to update", required = true)
        @PathVariable entryId: Long,
        @Valid @RequestBody request: UpdateBudgetEntryRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetEntryResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.updateEntry(budgetId, entryId, request, userEmail)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{budgetId}/entries")
    @Operation(
        summary = "Get all budget entries",
        description = "Retrieves all income and expense entries for the specified budget. Only users with access to the budget can view its entries. Supports optional pagination via query parameters."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved budget entries list",
                content = [Content(schema = Schema(implementation = BudgetEntryResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found",
                content = [Content()]
            )
        ]
    )
    fun getEntries(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        @Parameter(description = "Page number (0-indexed). If not provided, returns all results", required = false)
        @RequestParam(required = false) page: Int?,
        @Parameter(description = "Number of items per page", required = false, example = "20")
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "Field to sort by (modificationDate, creationDate, amount, description, category, type)", required = false, example = "modificationDate")
        @RequestParam(required = false, defaultValue = "modificationDate") sortBy: String,
        @Parameter(description = "Sort direction (asc or desc)", required = false, example = "desc")
        @RequestParam(required = false, defaultValue = "desc") sortDirection: String,
        authentication: Authentication
    ): ResponseEntity<*> {
        val userEmail = authentication.principal as String

        return if (page != null && size != null) {
            // Return paginated response
            val paginatedEntries = budgetService.getEntriesByBudgetId(budgetId, userEmail, page, size, sortBy, sortDirection)
            ResponseEntity.ok(paginatedEntries)
        } else {
            // Return all results (legacy behavior)
            val entries = budgetService.getEntriesByBudgetId(budgetId, userEmail)
            ResponseEntity.ok(entries)
        }
    }

    @GetMapping("/{budgetId}/entries/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Subscribe to real-time budget entry updates",
        description = "Opens a Server-Sent Events (SSE) stream that pushes real-time notifications when budget entries are created, updated, or deleted by OTHER collaborators. Your own changes are never echoed back, so there is no need to de-duplicate them client-side. Connection stays open and events are sent as they occur; a ':keep-alive' comment is sent on subscribe and every 15 seconds. Event type is 'budget-entry'."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "SSE stream established. Returns a stream of budget entry events.",
                content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE)]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found",
                content = [Content()]
            )
        ]
    )
    fun streamEntries(
        @Parameter(description = "ID of the budget to subscribe to", required = true)
        @PathVariable budgetId: Long,
        authentication: Authentication,
        response: HttpServletResponse
    ): Flux<ServerSentEvent<BudgetEntryEvent>> {
        val userEmail = authentication.principal as String

        // Verify access synchronously in a completed transaction
        // before returning the reactive Flux
        try {
            budgetService.verifyUserHasAccessToBudget(budgetId, userEmail)
        } catch (e: Exception) {
            // If access check fails, return error as Flux
            return Flux.error(e)
        }

        // Tell nginx (and any other proxy that honours it) not to buffer this response.
        // With the default `proxy_buffering on`, nginx holds back headers and small chunks
        // until its buffer fills or the upstream closes, so the client receives nothing
        // for the entire lifetime of the stream.
        response.setHeader(X_ACCEL_BUFFERING_HEADER, "no")

        // Create event stream, excluding the subscriber's own changes.
        //
        // Echoing a change back to its author makes the client re-download the whole entry
        // list it just synced, and pop a "X added an entry" notification naming the user
        // themselves. Covers CREATED, UPDATED and DELETED alike.
        //
        // The filter belongs here, per subscription, and NOT in
        // ReactiveSseService.broadcastEvent: the sink is a multicast shared by every
        // subscriber of the budget and does not know who each one is, so a single
        // subscriber cannot be excluded from there.
        //
        // TRADE-OFF: this filters by USER, not by connection. With the same account open on
        // two devices, the second one will not receive in real time what is created on the
        // first - it catches up when the screen is reopened. If that ever matters, the
        // precise fix is for the client to send a connection identifier (e.g. an
        // X-Client-Id header on both the stream and the write requests) so the server can
        // exclude just that connection instead of the whole user.
        val eventStream = reactiveSseService.subscribeToEvents(budgetId)
            .filter { it.userInfo.email != userEmail }
            .map { event ->
                ServerSentEvent.builder(event)
                    .event("budget-entry")
                    .build()
            }

        // Create heartbeat stream (SSE comment used to keep the connection alive).
        // The first heartbeat is emitted immediately so the client can confirm the
        // connection right away and the response headers are flushed on subscribe;
        // the 15s interval keeps idle time below the typical 60s proxy read timeout.
        val heartbeatStream = Flux.interval(Duration.ZERO, HEARTBEAT_INTERVAL)
            .map {
                ServerSentEvent.builder<BudgetEntryEvent>()
                    .comment("keep-alive")
                    .build()
            }

        // Merge event stream with heartbeat stream
        return Flux.merge(eventStream, heartbeatStream)
    }

    @DeleteMapping("/{budgetId}")
    @Operation(
        summary = "Delete a budget",
        description = "Permanently deletes a budget and all its associated entries and collaborator relationships. Only users with access to the budget can delete it."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "204",
                description = "Budget successfully deleted",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found",
                content = [Content()]
            )
        ]
    )
    fun deleteBudget(
        @Parameter(description = "ID of the budget to delete", required = true)
        @PathVariable budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val userEmail = authentication.principal as String
        budgetService.deleteBudget(budgetId, userEmail)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{budgetId}/entries/{entryId}")
    @Operation(
        summary = "Delete a budget entry",
        description = "Permanently deletes a budget entry. Only users with access to the budget can delete its entries. Triggers SSE notification to all connected clients."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "204",
                description = "Budget entry successfully deleted",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget or entry not found",
                content = [Content()]
            )
        ]
    )
    fun deleteEntry(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        @Parameter(description = "ID of the budget entry to delete", required = true)
        @PathVariable entryId: Long,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val userEmail = authentication.principal as String
        budgetService.deleteEntry(budgetId, entryId, userEmail)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{budgetId}/collaborators/{collaboratorEmail}")
    @Operation(
        summary = "Remove a collaborator from a budget",
        description = "Removes a user's access to the budget by their email. Only users with access to the budget can remove collaborators."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "204",
                description = "Collaborator successfully removed",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid JWT token",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "403",
                description = "Forbidden - user does not have access to this budget",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found or collaborator not found in budget",
                content = [Content()]
            )
        ]
    )
    fun removeCollaborator(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        @Parameter(description = "Email of the collaborator to remove", required = true)
        @PathVariable collaboratorEmail: String,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val userEmail = authentication.principal as String
        budgetService.removeCollaborator(budgetId, collaboratorEmail, userEmail)
        return ResponseEntity.noContent().build()
    }
}

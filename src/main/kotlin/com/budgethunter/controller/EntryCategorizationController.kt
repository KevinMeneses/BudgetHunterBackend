package com.budgethunter.controller

import com.budgethunter.categorization.BudgetCategorizationService
import com.budgethunter.dto.CategorizeEntriesResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/budgets")
@ConditionalOnProperty(prefix = "categorization", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@Tag(name = "Entry Categorization", description = "On-demand automatic categorization of budget entries")
class EntryCategorizationController(
    private val categorizationService: BudgetCategorizationService
) {

    @PostMapping("/{budgetId}/entries/categorize")
    @Operation(
        summary = "Categorize the budget's waiting entries",
        description = "Gives an automatic category to the entries of the budget that were saved without one " +
            "(category UNCATEGORIZED, i.e. nobody chose one) and whose creator has AI processing turned on. The work is done " +
            "before the response returns. Entries a person categorised are never touched, and a second call " +
            "only looks at what is still waiting. Only the entries' descriptions are sent to the AI provider. " +
            "Clients should sync their own unsynced entries first and refresh the budget's entries afterwards. " +
            "What is sent to the AI counts against the caller's monthly allowance (one per distinct description; " +
            "keyword rules and the cache are free). Past it the run still answers 200: the rules and the cache " +
            "apply, the rest stays pending, and quotaExceeded is true. usage has the caller's count after the run."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Run finished; the counts say what changed and what is still waiting",
                content = [Content(schema = Schema(implementation = CategorizeEntriesResponse::class))]
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = [Content()]),
            ApiResponse(
                responseCode = "403",
                description = "The budget exists but the user is not a collaborator on it, " +
                    "or AI processing is turned off for the account",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Budget not found (it never existed, or it was deleted)",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "409",
                description = "Categorization is already running for this budget",
                content = [Content()]
            )
        ]
    )
    fun categorize(
        @Parameter(description = "ID of the budget", required = true)
        @PathVariable budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<CategorizeEntriesResponse> =
        ResponseEntity.ok(categorizationService.categorize(budgetId, authentication.principal as String))
}

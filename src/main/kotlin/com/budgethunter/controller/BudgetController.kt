package com.budgethunter.controller

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetEntryResponse
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CollaboratorResponse
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.PutEntryRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.service.BudgetService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/budgets")
class BudgetController(
    private val budgetService: BudgetService
) {

    @PostMapping("/create_budget")
    fun createBudget(
        @Valid @RequestBody request: CreateBudgetRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.createBudget(request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/get_budgets")
    fun getBudgets(authentication: Authentication): ResponseEntity<List<BudgetResponse>> {
        val userEmail = authentication.principal as String
        val budgets = budgetService.getBudgetsByUserEmail(userEmail)
        return ResponseEntity.ok(budgets)
    }

    @PostMapping("/add_collaborator")
    fun addCollaborator(
        @Valid @RequestBody request: AddCollaboratorRequest,
        authentication: Authentication
    ): ResponseEntity<CollaboratorResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.addCollaborator(request, userEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/get_collaborators")
    fun getCollaborators(
        @RequestParam budgetId: Long,
        authentication: Authentication
    ): ResponseEntity<List<UserResponse>> {
        val userEmail = authentication.principal as String
        val collaborators = budgetService.getCollaboratorsByBudgetId(budgetId, userEmail)
        return ResponseEntity.ok(collaborators)
    }

    @PutMapping("/put_entry")
    fun putEntry(
        @Valid @RequestBody request: PutEntryRequest,
        authentication: Authentication
    ): ResponseEntity<BudgetEntryResponse> {
        val userEmail = authentication.principal as String
        val response = budgetService.putEntry(request, userEmail)
        val httpStatus = if (request.id == null) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(httpStatus).body(response)
    }
}

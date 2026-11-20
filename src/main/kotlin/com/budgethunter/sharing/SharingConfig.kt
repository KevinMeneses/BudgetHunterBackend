package com.budgethunter.sharing

import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the sharing limits per plan. The defaults live here, so the test `application.properties` needs no
 * mirroring; production reads `SHARING_FREE_SHARED_BUDGETS` and `SHARING_FREE_COLLABORATORS_PER_BUDGET`
 * (see application-production.properties).
 */
@Configuration
class SharingConfig {

    @Bean
    fun sharingLimitService(
        userRepository: UserRepository,
        budgetRepository: BudgetRepository,
        userBudgetRepository: UserBudgetRepository,
        @Value("\${sharing.free-shared-budgets:$DEFAULT_FREE_SHARED_BUDGETS}") freeSharedBudgets: Int,
        @Value("\${sharing.free-collaborators-per-budget:$DEFAULT_FREE_COLLABORATORS_PER_BUDGET}")
        freeCollaboratorsPerBudget: Int
    ) = SharingLimitService(
        userRepository,
        budgetRepository,
        userBudgetRepository,
        SharingAllowance(freeSharedBudgets, freeCollaboratorsPerBudget)
    )

    private companion object {
        // One budget shared with one other person: enough for a couple, which is what most FREE users share with.
        const val DEFAULT_FREE_SHARED_BUDGETS = 1
        const val DEFAULT_FREE_COLLABORATORS_PER_BUDGET = 1
    }
}

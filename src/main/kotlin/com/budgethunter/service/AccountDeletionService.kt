package com.budgethunter.service

import com.budgethunter.repository.AiUsageRepository
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Deletes an account for good (`DELETE /api/users/me`), all or nothing:
 *
 * - every budget it owns goes to another member, or is deleted when nobody else is in it
 *   ([BudgetService.handOverOwnedBudgets]);
 * - it leaves every other budget;
 * - the entries it wrote stay in their budgets with no author;
 * - its AI usage goes, and so does the account, with its tokens, preferences and plan.
 *
 * The access token stops working at once: [com.budgethunter.filter.JwtAuthenticationFilter] only accepts tokens
 * of accounts that exist. A store subscription is not cancelled (only the store can): the app has to tell the
 * user to cancel it there. Its webhooks then find no account and change nothing.
 *
 * Each step is spelled out rather than left to the `ON DELETE` actions of production's schema, which the H2
 * schema of the tests does not have; it is also what `database/delete_account.sql` does by hand.
 */
@Service
class AccountDeletionService(
    private val userRepository: UserRepository,
    private val budgetService: BudgetService,
    private val userBudgetRepository: UserBudgetRepository,
    private val budgetEntryRepository: BudgetEntryRepository,
    private val aiUsageRepository: AiUsageRepository
) {

    private val logger = LoggerFactory.getLogger(AccountDeletionService::class.java)

    @Transactional
    fun deleteAccount(email: String) {
        // Locked first: a budget, entry or membership created for this account from here on waits, then fails,
        // instead of slipping in between the hand-over and the delete.
        userRepository.lockForDeletion(email) ?: throw BadCredentialsException("User not found")

        budgetService.handOverOwnedBudgets(email)
        val entries = budgetEntryRepository.forgetAuthor(email)
        val memberships = userBudgetRepository.deleteByUserEmail(email)
        aiUsageRepository.deleteByUserEmail(email)
        userRepository.deleteAccount(email)

        // No email: logs are kept longer than the account.
        logger.info("Account deleted: left {} budget(s), {} entr(ies) kept without author", memberships, entries)
    }
}

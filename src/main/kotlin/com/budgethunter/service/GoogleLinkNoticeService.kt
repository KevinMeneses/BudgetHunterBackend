package com.budgethunter.service

import com.budgethunter.dto.SharedBudgetNotice
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The notice a Google sign-in gives when it removed a password of unknown origin (UserService.linkGoogle): what goes
 * in it, and the app's confirmation that the user was told.
 *
 * The notice is answered on every Google sign-in while [com.budgethunter.model.User.linkNoticePending] is set, not
 * only on the one that removed the password: that answer can be lost on the way, and the user would then never learn
 * their password is gone.
 */
@Service
class GoogleLinkNoticeService(
    private val userRepository: UserRepository,
    private val userBudgetRepository: UserBudgetRepository
) {

    /** The budgets [email] belongs to that someone else belongs to as well, with those other members. */
    fun sharedBudgetsOf(email: String): List<SharedBudgetNotice> =
        userBudgetRepository.findOtherMembersOfBudgetsOf(email)
            .groupBy { it.budgetId }
            .map { (budgetId, rows) ->
                SharedBudgetNotice(budgetId, rows.first().budgetName, rows.first().ownerEmail, rows.map { it.memberEmail })
            }

    /** The app told the user: later sign-ins stop answering the notice. Doing it twice is harmless. */
    @Transactional
    fun acknowledge(email: String) {
        // Dirty checking with @DynamicUpdate writes this column alone
        userRepository.findById(email).ifPresent { it.linkNoticePending = false }
    }
}

package com.budgethunter.sharing

import com.budgethunter.dto.SharingLimitDetail
import com.budgethunter.dto.SharingStatusResponse
import com.budgethunter.exception.ResourceNotFoundException
import com.budgethunter.model.Plan
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.repository.planAt
import java.time.Clock

/** Which of the two sharing limits stopped an addition. */
enum class SharingLimitType {
    /** The owner already shares as many budgets as their plan allows. */
    SHARED_BUDGETS,

    /** This budget already has as many members besides its owner as the owner's plan allows. */
    COLLABORATORS_PER_BUDGET
}

/**
 * What a FREE owner may share. PREMIUM has no limit. Zero is allowed (sharing turned off for FREE); there is no
 * "unlimited" value here, since unlimited is what PREMIUM is for.
 */
data class SharingAllowance(val freeSharedBudgets: Int, val freeCollaboratorsPerBudget: Int) {
    init {
        require(freeSharedBudgets >= 0) { "sharing.free-shared-budgets must not be negative" }
        require(freeCollaboratorsPerBudget >= 0) { "sharing.free-collaborators-per-budget must not be negative" }
    }
}

/** Adding a member would take the budget's owner past their plan's sharing limit. Answered with HTTP 402. */
class SharingLimitExceededException(val detail: SharingLimitDetail) :
    RuntimeException(
        when (detail.limitType) {
            SharingLimitType.SHARED_BUDGETS.name -> "The budget's owner cannot share more budgets on their plan"
            else -> "This budget cannot have more collaborators on its owner's plan"
        }
    )

/**
 * The limits on shared budgets, by the plan of the budget's **owner** (never of whoever adds the member), read
 * with [com.budgethunter.model.User.planAt]:
 *
 * - a budget counts as shared when someone besides its owner is a member of it; only the budgets one *owns*
 *   count against one's limit, so being invited is free;
 * - FREE: [SharingAllowance.freeSharedBudgets] shared budgets, each with [SharingAllowance.freeCollaboratorsPerBudget]
 *   members besides the owner; PREMIUM: no limit.
 *
 * The limits only stop *additions*. Nothing already shared is taken away (a lapsed PREMIUM keeps everything,
 * an owner handed a budget when an account is deleted keeps it even past the limit), and removing members or
 * leaving always works. Nothing is stored: everything is counted from `user_budgets` and `budgets.owner_email`.
 */
class SharingLimitService(
    private val userRepository: UserRepository,
    private val budgetRepository: BudgetRepository,
    private val userBudgetRepository: UserBudgetRepository,
    private val allowance: SharingAllowance,
    private val clock: Clock = Clock.systemUTC()
) {

    /** What [email] can share as an owner, for the app to show before it tries. */
    fun status(email: String): SharingStatusResponse {
        val plan = userRepository.findPlanColumns(email)?.planAt(clock.instant()) ?: Plan.FREE
        val free = plan == Plan.FREE
        return SharingStatusResponse(
            plan = plan.name,
            sharedBudgetsLimit = allowance.freeSharedBudgets.takeIf { free },
            sharedBudgetsUsed = budgetRepository.countSharedBudgetsOwnedBy(email).toInt(),
            collaboratorsPerBudgetLimit = allowance.freeCollaboratorsPerBudget.takeIf { free }
        )
    }

    /**
     * Locks what an addition of a member to [budgetId] depends on and returns the budget's owner, now stable. Call
     * it first, in the transaction that then adds the member (the locks need one, and they hold back other
     * additions for the same owner until it commits); then check anything that a parallel addition could have
     * changed, such as whether the person is already a member, and then [checkCanAddMember].
     *
     * Concurrency: the owner's `users` row is locked (`PESSIMISTIC_WRITE`) before counting, so every addition
     * that counts against one owner - from any of their budgets, by any member, from any device - checks and
     * inserts one after another, and the second one counts the first. The owner's row is the one thing all
     * those additions have in common; locking the budget would not stop two additions to two different budgets
     * of the same owner from both taking the last shared budget. On PostgreSQL that lock is `FOR NO KEY UPDATE`,
     * which does not hold back the foreign key checks of entries or memberships pointing at the owner, so
     * nothing but other additions for the same owner waits. A conditional `UPDATE` would need a counter to
     * update, i.e. a migration and a stored count that can drift from `user_budgets`; the lock needs neither.
     *
     * Lock order is the owner's row, then the budget's (shared), the same order as account deletion (user row,
     * then each of its budgets), so the two cannot deadlock. The budget's lock keeps its owner from changing
     * until this commits; if it changed between reading it and locking it, the new owner is locked as well.
     *
     * Returns a [LockedOwner], which only this method can make and which [checkCanAddMember] requires, so the
     * counting cannot be called without the locks.
     */
    fun lockForAddition(budgetId: Long): LockedOwner {
        val firstOwner = ownerOf(budgetId)
        userRepository.lockForSharingChange(firstOwner)
        budgetRepository.findByIdForMembershipChange(budgetId) ?: throw budgetNotFound(budgetId)
        val owner = ownerOf(budgetId)
        if (owner != firstOwner) userRepository.lockForSharingChange(owner)
        return LockedOwner(budgetId, owner)
    }

    /**
     * Throws [SharingLimitExceededException] when one more member in the budget [locked] names would take its
     * owner past their plan's limits. Takes the result of [lockForAddition], in the same transaction.
     */
    fun checkCanAddMember(locked: LockedOwner, callerEmail: String) {
        val budgetId = locked.budgetId
        val ownerEmail = locked.ownerEmail
        // Read after the lock, and as columns: the entity may be in the persistence context with an older plan.
        val plan = userRepository.findPlanColumns(ownerEmail)?.planAt(clock.instant()) ?: Plan.FREE
        if (plan == Plan.PREMIUM) return

        val hit = limitHitOnFree(budgetId, ownerEmail) ?: return
        throw SharingLimitExceededException(
            SharingLimitDetail(
                limitType = hit.type.name,
                plan = plan.name,
                limit = hit.limit,
                used = hit.used,
                callerIsOwner = callerEmail == ownerEmail
            )
        )
    }

    private data class LimitHit(val type: SharingLimitType, val limit: Int, val used: Int)

    /** The FREE limit one more member in [budgetId] would pass, or null when there is room. */
    private fun limitHitOnFree(budgetId: Long, ownerEmail: String): LimitHit? {
        val collaborators = userBudgetRepository.countOthersThanOwner(budgetId, ownerEmail).toInt()
        return when {
            collaborators >= allowance.freeCollaboratorsPerBudget ->
                LimitHit(SharingLimitType.COLLABORATORS_PER_BUDGET, allowance.freeCollaboratorsPerBudget, collaborators)
            // Already shared, so this member does not make it one more shared budget.
            collaborators > 0 -> null
            else -> {
                val shared = budgetRepository.countSharedBudgetsOwnedBy(ownerEmail).toInt()
                LimitHit(SharingLimitType.SHARED_BUDGETS, allowance.freeSharedBudgets, shared)
                    .takeIf { shared >= allowance.freeSharedBudgets }
            }
        }
    }

    private fun ownerOf(budgetId: Long) = budgetRepository.findOwnerEmail(budgetId) ?: throw budgetNotFound(budgetId)

    private fun budgetNotFound(budgetId: Long) = ResourceNotFoundException("Budget not found with id: $budgetId")
}

/**
 * Proof that [SharingLimitService.lockForAddition] locked [budgetId]'s owner in the current transaction; the only
 * way to call [SharingLimitService.checkCanAddMember]. The constructor is internal, so only this module (that
 * method, and tests) can make one.
 */
class LockedOwner internal constructor(val budgetId: Long, val ownerEmail: String)

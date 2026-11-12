package com.budgethunter.repository

import com.budgethunter.model.Plan
import com.budgethunter.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, String>, UserInsertRepository {
    fun existsByEmail(email: String): Boolean
    fun findByRefreshToken(refreshToken: String): Optional<User>
    fun findByGoogleSubject(googleSubject: String): Optional<User>
    fun findByBillingIdIn(billingIds: Collection<String>): List<User>

    /**
     * Writes only the plan columns, so a purchase arriving while the user changes a preference cannot undo
     * that change (saving the whole entity would write back every column as it was read).
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.plan = :plan, u.planExpiresAt = :expiresAt WHERE u.email = :email")
    fun updatePlan(
        @Param("email") email: String,
        @Param("plan") plan: Plan,
        @Param("expiresAt") expiresAt: Instant?
    ): Int

    /**
     * Locks the account's row until the transaction ends and returns its email, or null when there is no such
     * account. Native `FOR UPDATE` on purpose: it is the lock that the foreign key checks of a new budget, entry or
     * membership pointing at the account (`FOR KEY SHARE`) wait for, so nothing new can be attached to it while it
     * is being deleted. `PESSIMISTIC_WRITE` would not do: on PostgreSQL Hibernate makes it `FOR NO KEY UPDATE`,
     * which lets them through.
     */
    @Query(value = "SELECT email FROM users WHERE email = :email FOR UPDATE", nativeQuery = true)
    fun lockForDeletion(@Param("email") email: String): String?

    /** Bulk delete, so nothing is cascaded from the entity: the caller has already removed what points here. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM User u WHERE u.email = :email")
    fun deleteAccount(@Param("email") email: String): Int
}

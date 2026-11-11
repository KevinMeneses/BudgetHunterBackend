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
}

package com.budgethunter.repository

import com.budgethunter.model.AiUsage
import com.budgethunter.model.AiUsageId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface AiUsageRepository : JpaRepository<AiUsage, AiUsageId> {

    /**
     * Takes one receipt scan from the month's allowance, only while some is left. Returns 1 when it
     * was taken and 0 when the limit was already reached (or the row does not exist yet). The check
     * and the increment are one statement, so concurrent requests cannot both pass the last unit.
     */
    @Modifying
    @Transactional
    @Query(
        "UPDATE AiUsage u SET u.receiptScans = u.receiptScans + 1 " +
            "WHERE u.id.userEmail = :email AND u.id.period = :period AND u.receiptScans < :limit"
    )
    fun tryConsumeReceiptScan(
        @Param("email") email: String,
        @Param("period") period: String,
        @Param("limit") limit: Int
    ): Int

    /**
     * Creates the month's row with nothing used, unless it exists. Never touches an existing row: saving the
     * entity instead would be a merge, which could write 0 over a counter another request just raised. Two
     * requests racing here make the second fail on the primary key, which the caller ignores.
     */
    @Modifying
    @Transactional
    @Query(
        value = "INSERT INTO ai_usage (user_email, period, receipt_scans) " +
            "SELECT :email, :period, 0 WHERE NOT EXISTS " +
            "(SELECT 1 FROM ai_usage WHERE user_email = :email AND period = :period)",
        nativeQuery = true
    )
    fun createIfMissing(@Param("email") email: String, @Param("period") period: String): Int

    /** Gives back a scan that was taken for a request that then failed on our side or the AI's. */
    @Modifying
    @Transactional
    @Query(
        "UPDATE AiUsage u SET u.receiptScans = u.receiptScans - 1 " +
            "WHERE u.id.userEmail = :email AND u.id.period = :period AND u.receiptScans > 0"
    )
    fun releaseReceiptScan(@Param("email") email: String, @Param("period") period: String): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM AiUsage u WHERE u.id.userEmail = :email")
    fun deleteByUserEmail(@Param("email") email: String): Int
}

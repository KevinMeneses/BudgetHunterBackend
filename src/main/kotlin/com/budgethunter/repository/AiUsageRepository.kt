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

    /** Gives back a scan that was taken for a request that then failed on our side or the AI's. */
    @Modifying
    @Transactional
    @Query(
        "UPDATE AiUsage u SET u.receiptScans = u.receiptScans - 1 " +
            "WHERE u.id.userEmail = :email AND u.id.period = :period AND u.receiptScans > 0"
    )
    fun releaseReceiptScan(@Param("email") email: String, @Param("period") period: String): Int
}

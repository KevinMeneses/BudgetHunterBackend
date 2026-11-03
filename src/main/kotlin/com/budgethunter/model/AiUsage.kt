package com.budgethunter.model

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

/**
 * How much of the paid AI an account used in one calendar month (UTC). One row per account and
 * month, created on first use; a new month simply starts a new row, so nothing ever has to reset.
 *
 * Counters only move through the conditional UPDATEs in `AiUsageRepository`, never by saving this
 * entity, so two requests at the same time cannot both slip under the limit.
 */
@Entity
@Table(name = "ai_usage")
class AiUsage(
    @field:EmbeddedId
    val id: AiUsageId = AiUsageId(),

    @field:Column(name = "receipt_scans", nullable = false)
    val receiptScans: Int = 0
)

@Embeddable
data class AiUsageId(
    @field:Column(name = "user_email", nullable = false)
    val userEmail: String = "",

    /** The month, as `YYYY-MM`. */
    @field:Column(name = "period", nullable = false, length = 7)
    val period: String = ""
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

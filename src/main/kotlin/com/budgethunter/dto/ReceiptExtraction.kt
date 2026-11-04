package com.budgethunter.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

@Schema(description = "A receipt, invoice or bill to read with AI")
data class ReceiptExtractionRequest(
    @field:NotBlank
    @field:Schema(description = "The file, Base64-encoded (no data: prefix)", requiredMode = Schema.RequiredMode.REQUIRED)
    val data: String = "",

    @field:NotBlank
    @field:Schema(
        description = "MIME type of the file",
        example = "image/jpeg",
        allowableValues = ["image/jpeg", "image/png", "image/webp", "application/pdf"]
    )
    val mimeType: String = "",

    @field:Schema(
        description = "The user's local date (YYYY-MM-DD), used when the document shows no date. Defaults to the server's (UTC).",
        example = "2026-11-03"
    )
    val today: String? = null
)

@Schema(description = "What the AI read from the document, plus the month's usage after this scan")
data class ReceiptExtractionResponse(
    @field:Schema(description = "False when the file is not a receipt, invoice or bill; the other fields are then null")
    val isReceipt: Boolean,
    @field:Schema(description = "Final total paid, positive", example = "45900")
    val amount: BigDecimal? = null,
    @field:Schema(description = "Short description of what was paid", example = "Supermercado Éxito")
    val description: String? = null,
    @field:Schema(description = "One of the assignable categories (never UNCATEGORIZED)", example = "GROCERIES")
    val category: String? = null,
    @field:Schema(description = "Purchase date, YYYY-MM-DD", example = "2026-11-02")
    val date: String? = null,
    val usage: AiUsageResponse
)

@Schema(description = "Why a receipt could not be read")
data class ReceiptErrorResponse(
    val status: Int,
    @field:Schema(
        description = "Machine-readable reason",
        allowableValues = [
            "AI_QUOTA_EXCEEDED", "TOO_LARGE", "UNSUPPORTED_TYPE", "AI_UNAVAILABLE", "AI_BUSY", "AI_TIMEOUT",
            "AI_BLOCKED", "AI_INVALID_RESPONSE", "AI_REJECTED"
        ]
    )
    val code: String,
    val message: String,
    @field:Schema(description = "Present with AI_QUOTA_EXCEEDED")
    val usage: AiUsageResponse? = null
)

package com.budgethunter.controller

import com.budgethunter.dto.AiUsageResponse
import com.budgethunter.dto.ReceiptErrorResponse
import com.budgethunter.dto.ReceiptExtractionRequest
import com.budgethunter.dto.ReceiptExtractionResponse
import com.budgethunter.receipts.ReceiptQuotaService
import com.budgethunter.receipts.ReceiptService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/receipts")
@ConditionalOnProperty(prefix = "receipts", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@Tag(name = "Receipts", description = "Reading receipts with AI, counted against the plan's monthly scans")
class ReceiptController(
    private val receiptService: ReceiptService,
    private val receiptQuotaService: ReceiptQuotaService
) {

    @PostMapping("/extract")
    @Operation(
        summary = "Read a receipt, invoice or bill",
        description = "Sends the file to the AI and answers what it read. Each call takes one scan from the " +
            "month's allowance (FREE and PREMIUM have different ones), including a file that turns out not to be " +
            "a receipt; a failure on the AI's side gives the scan back. An account may start a few scans per " +
            "minute. Nothing is stored: the client fills its form with the answer."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Read (isReceipt=true) or not a receipt (isReceipt=false)",
                content = [Content(schema = Schema(implementation = ReceiptExtractionResponse::class))]
            ),
            ApiResponse(responseCode = "400", description = "Missing fields or invalid Base64", content = [Content()]),
            ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = [Content()]),
            ApiResponse(
                responseCode = "402",
                description = "No scans left this month (code AI_QUOTA_EXCEEDED, with the usage)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "429",
                description = "Too many scans from this account in the last minute (TOO_MANY_SCANS)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "413",
                description = "File too large (TOO_LARGE)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "415",
                description = "Unsupported type (UNSUPPORTED_TYPE)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "422",
                description = "The AI refused the content (AI_BLOCKED)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "502",
                description = "The AI's answer could not be used (AI_INVALID_RESPONSE, AI_REJECTED)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "503",
                description = "AI unavailable or busy (AI_UNAVAILABLE, AI_BUSY)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "504",
                description = "The AI took too long (AI_TIMEOUT)",
                content = [Content(schema = Schema(implementation = ReceiptErrorResponse::class))]
            )
        ]
    )
    fun extract(
        @Valid @RequestBody request: ReceiptExtractionRequest,
        authentication: Authentication
    ): ResponseEntity<ReceiptExtractionResponse> =
        ResponseEntity.ok(receiptService.extract(request, authentication.principal as String))

    @GetMapping("/usage")
    @Operation(summary = "This month's receipt scans", description = "Plan, scans used and left, and when the count resets")
    fun usage(authentication: Authentication): ResponseEntity<AiUsageResponse> =
        ResponseEntity.ok(receiptQuotaService.usage(authentication.principal as String))
}

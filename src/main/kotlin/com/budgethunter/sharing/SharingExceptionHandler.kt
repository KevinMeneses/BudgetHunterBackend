package com.budgethunter.sharing

import com.budgethunter.dto.SharingErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * HTTP answer for the sharing limits; kept apart from GlobalExceptionHandler, which is already long. Ordered
 * first: otherwise GlobalExceptionHandler's catch-all for Exception may be asked first and answer 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SharingExceptionHandler {

    private val logger = LoggerFactory.getLogger(SharingExceptionHandler::class.java)

    /**
     * 402, the same shape as the receipts' (`status`, `code`, `message`), with the limit that was hit where
     * receipts put the AI usage.
     */
    @ExceptionHandler(SharingLimitExceededException::class)
    fun handleSharingLimitExceeded(ex: SharingLimitExceededException): ResponseEntity<SharingErrorResponse> {
        logger.info("Sharing limit reached: {} (plan {})", ex.detail.limitType, ex.detail.plan)
        val status = HttpStatus.PAYMENT_REQUIRED.value()
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(SharingErrorResponse(status, CODE, ex.message ?: "Sharing limit reached", ex.detail))
    }

    private companion object {
        const val CODE = "SHARING_LIMIT_REACHED"
    }
}

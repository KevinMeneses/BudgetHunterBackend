package com.budgethunter.billing

import com.budgethunter.exception.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** HTTP answers for the billing failures; kept apart from GlobalExceptionHandler, which is already long. */
@RestControllerAdvice
class BillingExceptionHandler {

    private val logger = LoggerFactory.getLogger(BillingExceptionHandler::class.java)

    /** 503: for the webhook this makes RevenueCat retry later. */
    @ExceptionHandler(BillingUnavailableException::class)
    fun handleUnavailable(ex: BillingUnavailableException): ResponseEntity<ErrorResponse> {
        logger.warn("Billing unavailable: {}", ex.message)
        return answer(HttpStatus.SERVICE_UNAVAILABLE, "Purchases are unavailable, try again later")
    }

    @ExceptionHandler(BillingSyncThrottledException::class)
    fun handleThrottled(ex: BillingSyncThrottledException): ResponseEntity<ErrorResponse> =
        answer(HttpStatus.TOO_MANY_REQUESTS, ex.message ?: "Too many requests")

    private fun answer(status: HttpStatus, message: String) =
        ResponseEntity.status(status).body(ErrorResponse(status = status.value(), message = message))
}

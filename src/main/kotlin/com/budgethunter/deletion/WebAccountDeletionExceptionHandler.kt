package com.budgethunter.deletion

import com.budgethunter.dto.WebAccountDeletionErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Ordered first, like SharingExceptionHandler, so GlobalExceptionHandler's catch-all cannot answer 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebAccountDeletionExceptionHandler {

    private val logger = LoggerFactory.getLogger(WebAccountDeletionExceptionHandler::class.java)

    @ExceptionHandler(WebAccountDeletionException::class)
    fun handle(ex: WebAccountDeletionException): ResponseEntity<WebAccountDeletionErrorResponse> {
        // The code only: never the email, the password or the token.
        logger.warn("Web account deletion refused: {}", ex.code)
        return ResponseEntity.status(ex.status).contentType(MediaType.APPLICATION_JSON)
            .body(WebAccountDeletionErrorResponse(ex.status, ex.code, ex.message ?: ex.code))
    }
}

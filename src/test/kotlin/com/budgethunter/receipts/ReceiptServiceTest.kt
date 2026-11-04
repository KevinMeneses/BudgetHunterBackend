package com.budgethunter.receipts

import com.budgethunter.dto.AiUsageResponse
import com.budgethunter.dto.ReceiptExtractionRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceiptServiceTest {

    private val usage = AiUsageResponse("FREE", "2026-11", 1, 10, 9, "2026-12-01")
    private val reservation = ReceiptQuotaService.Reservation("a@example.com", "2026-11")
    private val quota = mockk<ReceiptQuotaService> {
        every { reserve(any()) } returns (reservation to usage)
        every { release(any()) } returns Unit
    }
    private val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))
    private val request = ReceiptExtractionRequest(jpeg, "image/jpeg")
    private val read = ReceiptReading.Read(BigDecimal.TEN, "Taxi", "TRANSPORTATION", LocalDate.parse("2026-11-01"))

    private fun service(extractor: ReceiptExtractor, maxConcurrentScans: Int = 8) = ReceiptService(
        extractor,
        quota,
        ReceiptFileValidator(1024),
        ScanRateLimiter(100),
        maxConcurrentScans = maxConcurrentScans
    )

    @Test
    fun `past the in-flight limit a scan is busy and given back, and the slot frees up afterwards`() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val service = service(
            extractor = { _, _, _ ->
                started.countDown()
                finish.await(5, TimeUnit.SECONDS)
                read
            },
            maxConcurrentScans = 1
        )
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Boolean> { service.extract(request, "a@example.com").isReceipt }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            val busy = assertThrows<ReceiptException> { service.extract(request, "b@example.com") }
            assertEquals("AI_BUSY", busy.code)
            verify(exactly = 1) { quota.release(reservation) }

            finish.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertTrue(service.extract(request, "b@example.com").isReceipt)
            verify(exactly = 1) { quota.release(reservation) }
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a refund that fails does not hide the answer`() {
        every { quota.release(any()) } throws IllegalStateException("database down")
        val service = service({ _, _, _ -> ReceiptReading.Failed(ReceiptFailure.TIMEOUT) })

        assertEquals("AI_TIMEOUT", assertThrows<ReceiptException> { service.extract(request, "a@example.com") }.code)
    }
}

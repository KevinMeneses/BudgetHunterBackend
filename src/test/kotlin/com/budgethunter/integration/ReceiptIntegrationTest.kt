package com.budgethunter.integration

import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.Plan
import com.budgethunter.receipts.AiQuotaExceededException
import com.budgethunter.receipts.ReceiptExtractor
import com.budgethunter.receipts.ReceiptFailure
import com.budgethunter.receipts.ReceiptQuotaService
import com.budgethunter.receipts.ReceiptReading
import com.budgethunter.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * `POST /api/receipts/extract` and `GET /api/receipts/usage` through the whole stack, with a fake AI so the
 * quota rules can be checked: what counts, what is given back, and where each plan stops.
 */
@SpringBootTest(
    properties = [
        "receipts.free-monthly-scans=2",
        "receipts.premium-monthly-scans=3",
        "receipts.max-file-bytes=64",
        "receipts.max-scans-per-user-per-minute=5"
    ]
)
@AutoConfigureMockMvc
class ReceiptIntegrationTest {

    @TestConfiguration
    class FakeAi {
        @Bean
        @Primary
        fun fakeReceiptExtractor() = ReceiptExtractor { _, _, today ->
            seenToday = today
            if (throwNext) error("extractor bug")
            next
        }
    }

    companion object {
        @Volatile var next: ReceiptReading = ReceiptReading.NotAReceipt

        @Volatile var seenToday: LocalDate? = null

        @Volatile var throwNext = false

        const val PARALLEL_REQUESTS = 20

        /** The start of a JPEG: enough for the content check, which only reads the first bytes. */
        val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            "fake jpeg bytes".toByteArray()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var quotaService: ReceiptQuotaService

    private val email = "receipts-${UUID.randomUUID().toString().take(8)}@example.com"
    private lateinit var token: String
    private val file = Base64.getEncoder().encodeToString(JPEG)
    private val read = ReceiptReading.Read(BigDecimal("45900"), "Éxito", "GROCERIES", LocalDate.parse("2026-11-02"))

    @BeforeEach
    fun setup() {
        next = read
        seenToday = null
        throwNext = false
        mockMvc.perform(
            post("/api/users/sign_up").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(email, "User", "Password123!")))
        )
        val result = mockMvc.perform(
            post("/api/users/sign_in").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(email, "Password123!")))
        ).andReturn()
        token = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java).authToken
    }

    private fun extract(
        data: String = file,
        mimeType: String = "image/jpeg",
        today: String? = null,
        auth: String? = token
    ): ResultActions {
        val body = buildMap {
            put("data", data)
            put("mimeType", mimeType)
            today?.let { put("today", it) }
        }
        val request = post("/api/receipts/extract").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
        auth?.let { request.header("Authorization", "Bearer $it") }
        return mockMvc.perform(request)
    }

    private fun usage(): ResultActions =
        mockMvc.perform(get("/api/receipts/usage").header("Authorization", "Bearer $token"))

    private fun setPlan(plan: Plan) {
        val user = userRepository.findById(email).get()
        user.plan = plan
        userRepository.save(user)
    }

    @Test
    fun `a new account is FREE with the whole allowance`() {
        val month = YearMonth.now(ZoneOffset.UTC)
        usage().andExpect(status().isOk)
            .andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.period").value(month.toString()))
            .andExpect(jsonPath("$.receiptScansUsed").value(0))
            .andExpect(jsonPath("$.receiptScansLimit").value(2))
            .andExpect(jsonPath("$.receiptScansRemaining").value(2))
            .andExpect(jsonPath("$.resetsOn").value(month.plusMonths(1).atDay(1).toString()))
    }

    @Test
    fun `a read receipt is answered and counted`() {
        extract().andExpect(status().isOk)
            .andExpect(jsonPath("$.isReceipt").value(true))
            .andExpect(jsonPath("$.amount").value(45900))
            // The raw JSON, not just the number: 4.59E+4 is the same number but the app shows the text
            .andExpect(content().string(containsString("\"amount\":45900,")))
            .andExpect(jsonPath("$.description").value("Éxito"))
            .andExpect(jsonPath("$.category").value("GROCERIES"))
            .andExpect(jsonPath("$.date").value("2026-11-02"))
            .andExpect(jsonPath("$.usage.receiptScansUsed").value(1))
            .andExpect(jsonPath("$.usage.receiptScansRemaining").value(1))
        usage().andExpect(jsonPath("$.receiptScansUsed").value(1))
    }

    @Test
    fun `a file that is not a receipt still counts`() {
        next = ReceiptReading.NotAReceipt
        extract().andExpect(status().isOk)
            .andExpect(jsonPath("$.isReceipt").value(false))
            .andExpect(jsonPath("$.usage.receiptScansUsed").value(1))
    }

    @Test
    fun `a failure on the AI's side gives the scan back and says why`() {
        next = ReceiptReading.Failed(ReceiptFailure.TIMEOUT)
        extract().andExpect(status().isGatewayTimeout).andExpect(jsonPath("$.code").value("AI_TIMEOUT"))
        next = ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE)
        extract().andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
        usage().andExpect(jsonPath("$.receiptScansUsed").value(0))
    }

    @Test
    fun `past the free allowance the answer is 402 with the usage, and the AI is not asked`() {
        extract().andExpect(status().isOk)
        extract().andExpect(status().isOk)
        next = ReceiptReading.Failed(ReceiptFailure.REJECTED) // would show if the AI were asked

        extract().andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("AI_QUOTA_EXCEEDED"))
            .andExpect(jsonPath("$.usage.plan").value("FREE"))
            .andExpect(jsonPath("$.usage.receiptScansUsed").value(2))
            .andExpect(jsonPath("$.usage.receiptScansRemaining").value(0))
        usage().andExpect(jsonPath("$.receiptScansUsed").value(2))
    }

    @Test
    fun `premium gets its own, larger allowance`() {
        setPlan(Plan.PREMIUM)
        repeat(3) { extract().andExpect(status().isOk) }
        extract().andExpect(status().isPaymentRequired).andExpect(jsonPath("$.usage.plan").value("PREMIUM"))
    }

    @Test
    fun `upgrading after running out unlocks the rest of premium's allowance this month`() {
        repeat(2) { extract().andExpect(status().isOk) }
        extract().andExpect(status().isPaymentRequired)
        setPlan(Plan.PREMIUM)
        extract().andExpect(status().isOk).andExpect(jsonPath("$.usage.receiptScansRemaining").value(0))
    }

    @Test
    fun `the user's date is passed on when it is sane, the server's otherwise`() {
        val serverToday = LocalDate.now(ZoneOffset.UTC)
        extract(today = serverToday.minusDays(1).toString()).andExpect(status().isOk)
        assertEquals(serverToday.minusDays(1), seenToday)
        extract(today = "1999-01-01").andExpect(status().isOk)
        assertEquals(serverToday, seenToday)
    }

    @Test
    fun `an unsupported type is 415 and costs nothing`() {
        extract(mimeType = "text/plain").andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_TYPE"))
        usage().andExpect(jsonPath("$.receiptScansUsed").value(0))
    }

    @Test
    fun `a file over the limit is 413 and costs nothing`() {
        val big = Base64.getEncoder().encodeToString(ByteArray(65))
        extract(data = big).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("TOO_LARGE"))
        usage().andExpect(jsonPath("$.receiptScansUsed").value(0))
    }

    @Test
    fun `data that is not Base64 is 400 and costs nothing`() {
        extract(data = "not base64!").andExpect(status().isBadRequest)
        extract(data = "").andExpect(status().isBadRequest)
        usage().andExpect(jsonPath("$.receiptScansUsed").value(0))
    }

    @Test
    fun `without a token there is no scan`() {
        extract(auth = null).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an extractor that throws gives the scan back`() {
        throwNext = true
        extract().andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
        usage().andExpect(jsonPath("$.receiptScansUsed").value(0))
    }

    @Test
    fun `content that does not match the declared type is 415 and costs nothing`() {
        val pdf = Base64.getEncoder().encodeToString("%PDF-1.7 tiny".toByteArray())
        extract(data = pdf, mimeType = "image/jpeg").andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_TYPE"))
        extract(data = pdf, mimeType = "application/pdf").andExpect(status().isOk)
        usage().andExpect(jsonPath("$.receiptScansUsed").value(1))
    }

    @Test
    fun `an account is limited per minute, even when its scans are given back`() {
        next = ReceiptReading.Failed(ReceiptFailure.INVALID_RESPONSE)
        repeat(5) { extract().andExpect(status().isBadGateway) }
        extract().andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("TOO_MANY_SCANS"))
        usage().andExpect(jsonPath("$.receiptScansUsed").value(0))
    }

    @Test
    fun `parallel scans never go over the allowance`() {
        val pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        try {
            val attempts = (1..PARALLEL_REQUESTS).map {
                Callable {
                    try {
                        quotaService.reserve(email)
                        true
                    } catch (_: AiQuotaExceededException) {
                        false
                    }
                }
            }
            val granted = pool.invokeAll(attempts).count { it.get() }
            assertEquals(2, granted)
        } finally {
            pool.shutdown()
        }
        usage().andExpect(jsonPath("$.receiptScansUsed").value(2))
    }
}

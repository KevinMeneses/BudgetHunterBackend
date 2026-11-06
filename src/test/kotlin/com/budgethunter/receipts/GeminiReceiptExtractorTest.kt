package com.budgethunter.receipts

import com.budgethunter.model.EntryCategory
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.never
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDate

class GeminiReceiptExtractorTest {

    private val objectMapper = ObjectMapper()
    private val url = "https://gemini.test/v1beta/models/test-model:generateContent"
    private val today = LocalDate.parse("2026-11-03")

    private lateinit var server: MockRestServiceServer
    private lateinit var restClient: RestClient

    @BeforeEach
    fun setup() {
        val builder = RestClient.builder().baseUrl("https://gemini.test")
        server = MockRestServiceServer.bindTo(builder).build()
        restClient = builder.build()
    }

    private val fallbackUrl = "https://gemini.test/v1beta/models/backup-model:generateContent"

    private fun extractor(
        apiKey: String = "secret-key",
        maxRequestsPerMinute: Int = 100,
        fallbackModel: String = "",
        retryDelayMillis: Long = 0
    ) =
        GeminiReceiptExtractor(
            restClient,
            ReceiptGeminiSettings(apiKey, "test-model", maxRequestsPerMinute, fallbackModel, retryDelayMillis),
            objectMapper
        )

    /** A Gemini reply whose text part is [extraction]. */
    private fun reply(extraction: String): String = objectMapper.writeValueAsString(
        mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to extraction))))))
    )

    private fun expectReply(extraction: String) {
        server.expect(once(), requestTo(url))
            .andRespond(withSuccess(reply(extraction), MediaType.APPLICATION_JSON))
    }

    @Test
    fun `sends the file, the prompt and the key in a header and reads the answer`() {
        server.expect(once(), requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-goog-api-key", "secret-key"))
            .andExpect(jsonPath("$.contents[0].parts[1].inline_data.mime_type").value("image/jpeg"))
            .andExpect(jsonPath("$.contents[0].parts[1].inline_data.data").value("QUJD"))
            .andExpect(jsonPath("$.contents[0].parts[0].text", containsString("use 2026-11-03")))
            .andExpect(jsonPath("$.generationConfig.responseSchema.properties.category.enum.length()").value(EntryCategory.ALL.size))
            .andRespond(
                withSuccess(
                    reply("""{"isInvoice":true,"amount":45900.50,"description":" Éxito ","category":"GROCERIES","date":"2026-11-02"}"""),
                    MediaType.APPLICATION_JSON
                )
            )

        val reading = extractor().extract("QUJD", "image/jpeg", today)

        assertEquals(
            ReceiptReading.Read(BigDecimal("45900.5"), "Éxito", "GROCERIES", LocalDate.parse("2026-11-02")),
            reading
        )
        server.verify()
    }

    @Test
    fun `the key never goes in the URL`() {
        server.expect(once(), requestTo(url)).andExpect(content().string(not(containsString("secret-key"))))
            .andRespond(withSuccess(reply("""{"isInvoice":false}"""), MediaType.APPLICATION_JSON))
        extractor().extract("QUJD", "image/png", today)
        server.verify()
    }

    @Test
    fun `not a receipt`() {
        expectReply("""{"isInvoice":false}""")
        assertEquals(ReceiptReading.NotAReceipt, extractor().extract("QUJD", "image/jpeg", today))
    }

    @Test
    fun `an empty object is not a receipt`() {
        expectReply("{}")
        assertEquals(ReceiptReading.NotAReceipt, extractor().extract("QUJD", "image/jpeg", today))
    }

    @Test
    fun `an unknown category becomes OTHER, a missing date becomes today and a non-positive amount is dropped`() {
        expectReply("""{"isInvoice":true,"amount":-3,"description":"Algo","category":"PETS"}""")
        assertEquals(
            ReceiptReading.Read(null, "Algo", EntryCategory.OTHER, today),
            extractor().extract("QUJD", "image/jpeg", today)
        )
    }

    @Test
    fun `UNCATEGORIZED is never answered`() {
        expectReply("""{"isInvoice":true,"amount":10,"category":"UNCATEGORIZED"}""")
        val reading = extractor().extract("QUJD", "image/jpeg", today) as ReceiptReading.Read
        assertEquals(EntryCategory.OTHER, reading.category)
    }

    @Test
    fun `a receipt with neither amount nor description is an invalid answer`() {
        expectReply("""{"isInvoice":true,"category":"FOOD"}""")
        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.INVALID_RESPONSE),
            extractor().extract("QUJD", "image/jpeg", today)
        )
    }

    @Test
    fun `text that is not JSON is an invalid answer`() {
        expectReply("I think this is a receipt")
        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.INVALID_RESPONSE),
            extractor().extract("QUJD", "image/jpeg", today)
        )
    }

    @Test
    fun `a blocked prompt is BLOCKED`() {
        server.expect(once(), requestTo(url))
            .andRespond(withSuccess("""{"promptFeedback":{"blockReason":"SAFETY"}}""", MediaType.APPLICATION_JSON))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.BLOCKED), extractor().extract("QUJD", "image/jpeg", today))
    }

    @Test
    fun `a candidate stopped by safety is BLOCKED`() {
        server.expect(once(), requestTo(url))
            .andRespond(withSuccess("""{"candidates":[{"finishReason":"SAFETY"}]}""", MediaType.APPLICATION_JSON))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.BLOCKED), extractor().extract("QUJD", "image/jpeg", today))
    }

    @Test
    fun `a 429 is BUSY and is not retried`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.BUSY), extractor().extract("QUJD", "image/jpeg", today))
        server.verify()
    }

    @Test
    fun `a 5xx is retried once, then UNAVAILABLE`() {
        server.expect(times(2), requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE), extractor().extract("QUJD", "image/jpeg", today))
        server.verify()
    }

    @Test
    fun `a 5xx followed by an answer succeeds`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        expectReply("""{"isInvoice":true,"amount":12,"description":"Taxi","category":"TRANSPORTATION","date":"2026-11-01"}""")
        val reading = extractor().extract("QUJD", "image/jpeg", today)
        assertTrue(reading is ReceiptReading.Read)
    }

    @Test
    fun `another 4xx is REJECTED`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.BAD_REQUEST))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.REJECTED), extractor().extract("QUJD", "image/jpeg", today))
    }

    @Test
    fun `a timeout is TIMEOUT and is not retried`() {
        server.expect(once(), requestTo(url)).andRespond(withException(SocketTimeoutException("read timed out")))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.TIMEOUT), extractor().extract("QUJD", "image/jpeg", today))
        server.verify()
    }

    @Test
    fun `a transport error is retried, then UNAVAILABLE`() {
        server.expect(times(2), requestTo(url)).andRespond(withException(IOException("connection reset")))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE), extractor().extract("QUJD", "image/jpeg", today))
    }

    @Test
    fun `without a key nothing is sent`() {
        server.expect(never(), requestTo(url))
        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE),
            extractor(apiKey = "").extract("QUJD", "image/jpeg", today)
        )
        server.verify()
    }

    @Test
    fun `past the per-minute budget the request is BUSY without calling`() {
        expectReply("""{"isInvoice":false}""")
        val extractor = extractor(maxRequestsPerMinute = 1)
        extractor.extract("QUJD", "image/jpeg", today)
        assertEquals(ReceiptReading.Failed(ReceiptFailure.BUSY), extractor.extract("QUJD", "image/jpeg", today))
        server.verify()
    }

    @Test
    fun `the prompt lists every assignable category with its meaning`() {
        val prompt = GeminiReceiptExtractor.prompt(today)
        EntryCategory.ALL.forEach { assertTrue(prompt.contains("- $it: "), "missing $it") }
        assertTrue(!prompt.contains(EntryCategory.UNCATEGORIZED))
    }

    @Test
    fun `an overloaded model hands the receipt to the fallback model`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        server.expect(once(), requestTo(fallbackUrl))
            .andRespond(withSuccess(reply("""{"isInvoice":true,"amount":12,"description":"Taxi"}"""), MediaType.APPLICATION_JSON))

        val reading = extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today)

        assertTrue(reading is ReceiptReading.Read)
        server.verify()
    }

    @Test
    fun `an unreachable model hands the receipt to the fallback model`() {
        server.expect(once(), requestTo(url)).andRespond(withException(IOException("connection reset")))
        server.expect(once(), requestTo(fallbackUrl)).andRespond(withSuccess(reply("""{"isInvoice":false}"""), MediaType.APPLICATION_JSON))

        assertEquals(ReceiptReading.NotAReceipt, extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today))
        server.verify()
    }

    @Test
    fun `a rate limited model hands the receipt to the fallback model`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(once(), requestTo(fallbackUrl)).andRespond(withSuccess(reply("""{"isInvoice":false}"""), MediaType.APPLICATION_JSON))

        assertEquals(ReceiptReading.NotAReceipt, extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today))
        server.verify()
    }

    @Test
    fun `when both models fail the receipt is UNAVAILABLE after two attempts`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        server.expect(once(), requestTo(fallbackUrl)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE),
            extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today)
        )
        server.verify()
    }

    @Test
    fun `a rate limited fallback is BUSY`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        server.expect(once(), requestTo(fallbackUrl)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.BUSY),
            extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today)
        )
        server.verify()
    }

    @Test
    fun `a rejected request or a timeout is not handed to the fallback`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.BAD_REQUEST))
        server.expect(never(), requestTo(fallbackUrl))
        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.REJECTED),
            extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today)
        )
        server.verify()

        server.reset()
        server.expect(once(), requestTo(url)).andRespond(withException(SocketTimeoutException("read timed out")))
        server.expect(never(), requestTo(fallbackUrl))
        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.TIMEOUT),
            extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today)
        )
        server.verify()
    }

    @Test
    fun `a fallback equal to the main model behaves like no fallback`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.BUSY),
            extractor(fallbackModel = "test-model").extract("QUJD", "image/jpeg", today)
        )
        server.verify()
    }

    @Test
    fun `a fallback that rejects the request reports why the main model failed`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        server.expect(once(), requestTo(fallbackUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertEquals(
            ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE),
            extractor(fallbackModel = "backup-model").extract("QUJD", "image/jpeg", today)
        )
        server.verify()
    }

    @Test
    fun `an error body that is not JSON, or a long multi-line message, does not change the outcome`() {
        server.expect(once(), requestTo(url))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("<html>not json</html>").contentType(MediaType.TEXT_HTML))
        assertEquals(ReceiptReading.Failed(ReceiptFailure.REJECTED), extractor().extract("QUJD", "image/jpeg", today))
        server.verify()

        server.reset()
        val longMessage = "line one\nline two ".repeat(100)
        server.expect(once(), requestTo(url)).andRespond(
            withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(mapOf("error" to mapOf("status" to "INVALID", "message" to longMessage))))
        )
        assertEquals(ReceiptReading.Failed(ReceiptFailure.REJECTED), extractor().extract("QUJD", "image/jpeg", today))
        server.verify()
    }

    @Test
    fun `an interrupted pause gives up as UNAVAILABLE and keeps the interrupt`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        server.expect(never(), requestTo(fallbackUrl))
        Thread.currentThread().interrupt()
        try {
            val reading = extractor(fallbackModel = "backup-model", retryDelayMillis = 10_000)
                .extract("QUJD", "image/jpeg", today)
            assertEquals(ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE), reading)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        server.verify()
    }
}

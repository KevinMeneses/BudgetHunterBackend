package com.budgethunter.categorization

import com.budgethunter.model.EntryCategory
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.never
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class GeminiCategoryClassifierTest {

    private val objectMapper = ObjectMapper()
    private val url = "https://gemini.test/v1beta/models/test-model:generateContent"

    private lateinit var server: MockRestServiceServer
    private lateinit var restClient: RestClient
    private var now: Instant = Instant.parse("2026-10-06T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = now
    }

    @BeforeEach
    fun setup() {
        val builder = RestClient.builder().baseUrl("https://gemini.test")
        server = MockRestServiceServer.bindTo(builder).build()
        restClient = builder.build()
    }

    private fun classifier(
        apiKey: String = "secret-key",
        maxRequestsPerMinute: Int = 100,
        maxBatchSize: Int = 20
    ) = GeminiCategoryClassifier(
        restClient,
        GeminiSettings(apiKey, "test-model", maxRequestsPerMinute, maxBatchSize),
        objectMapper,
        clock
    )

    /** What Gemini returns: the model's JSON array travels as a string inside `candidates`. */
    private fun reply(answersJson: String): String =
        objectMapper.writeValueAsString(
            mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to answersJson))))))
        )

    private fun ok(answersJson: String) = withSuccess(reply(answersJson), MediaType.APPLICATION_JSON)

    @Test
    fun `sends one request with the key in a header and a schema bounded to the known categories`() {
        server.expect(once(), requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-goog-api-key", "secret-key"))
            .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
            .andExpect(jsonPath("$.generationConfig.responseSchema.items.properties.category.enum.length()").value(11))
            .andExpect(jsonPath("$.generationConfig.responseSchema.items.properties.category.enum[10]").value("OTHER"))
            .andExpect(jsonPath("$.systemInstruction.parts[0].text").value(containsString("GROCERIES")))
            .andRespond(ok("""[{"id":0,"category":"TRANSPORTATION"}]"""))

        val result = classifier().classify(listOf("Uber trip"))

        assertEquals(listOf<String?>("TRANSPORTATION"), result)
        server.verify()
    }

    @Test
    fun `sends only the normalised text, never the raw description or digits`() {
        server.expect(once(), requestTo(url))
            .andExpect(jsonPath("$.contents[0].parts[0].text").value(not(containsString("8841"))))
            .andExpect(jsonPath("$.contents[0].parts[0].text").value(containsString("rappi")))
            .andRespond(ok("""[{"id":0,"category":"FOOD"}]"""))

        classifier().classify(listOf("RAPPI 8841"))

        server.verify()
    }

    @Test
    fun `does not put the key in the url`() {
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"FOOD"}]"""))

        classifier().classify(listOf("pizza"))

        server.verify() // requestTo(url) is an exact match, so any ?key= would have failed it
    }

    @Test
    fun `maps answers back to descriptions by id and in order`() {
        server.expect(once(), requestTo(url))
            .andRespond(ok("""[{"id":1,"category":"HEALTH"},{"id":0,"category":"LEISURE"}]"""))

        val result = classifier().classify(listOf("netflix", "farmacia san jorge"))

        assertEquals(listOf<String?>("LEISURE", "HEALTH"), result)
    }

    @Test
    fun `identical normalised descriptions are sent once and share the answer`() {
        server.expect(once(), requestTo(url))
            .andExpect(jsonPath("$.contents[0].parts[0].text").value(not(containsString("id\":1"))))
            .andRespond(ok("""[{"id":0,"category":"FOOD"}]"""))

        val result = classifier().classify(listOf("Pizza 1", "PIZZA 22", "pizza"))

        assertEquals(listOf<String?>("FOOD", "FOOD", "FOOD"), result)
        server.verify()
    }

    @Test
    fun `blank descriptions are not sent and answer null`() {
        server.expect(never(), requestTo(url))

        val result = classifier().classify(listOf("", "   ", "12345"))

        assertEquals(listOf<String?>(null, null, null), result)
        server.verify()
    }

    @Test
    fun `splits a large list into batches`() {
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"FOOD"},{"id":1,"category":"TAXES"}]"""))
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"HEALTH"}]"""))

        val result = classifier(maxBatchSize = 2).classify(listOf("pizza", "impuesto", "farmacia"))

        assertEquals(listOf<String?>("FOOD", "TAXES", "HEALTH"), result)
        server.verify()
    }

    @Test
    fun `a category outside the closed list is dropped for that item only`() {
        server.expect(once(), requestTo(url))
            .andRespond(ok("""[{"id":0,"category":"Groceries"},{"id":1,"category":"TAXES"}]"""))

        val result = classifier().classify(listOf("exito", "impuesto"))

        assertEquals(listOf<String?>(null, "TAXES"), result)
    }

    @Test
    fun `the placeholder is never accepted as an answer`() {
        server.expect(once(), requestTo(url))
            .andRespond(ok("""[{"id":0,"category":"UNCATEGORIZED"},{"id":1,"category":"OTHER"}]"""))

        assertEquals(listOf<String?>(null, "OTHER"), classifier().classify(listOf("exito", "xyzzy")))
    }

    @Test
    fun `malformed or empty replies answer null instead of throwing`() {
        listOf(
            reply("not json at all"),
            reply("""{"id":0,"category":"FOOD"}"""), // an object, not an array
            reply("""[{"id":"zero","category":"FOOD"},{"id":99,"category":"FOOD"},{"category":"FOOD"}]"""),
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            "not even json",
            ""
        ).forEach { body ->
            server.reset()
            server.expect(once(), requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

            assertEquals(listOf<String?>(null), classifier().classify(listOf("pizza")), "body: $body")
        }
    }

    @Test
    fun `retries once on a server error`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"FOOD"}]"""))

        assertEquals(listOf<String?>("FOOD"), classifier().classify(listOf("pizza")))
        server.verify()
    }

    @Test
    fun `gives up after the retry and answers null`() {
        server.expect(times(2), requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertEquals(listOf<String?>(null), classifier().classify(listOf("pizza")))
        server.verify()
    }

    @Test
    fun `retries once on a network error`() {
        server.expect(once(), requestTo(url)).andRespond { throw IOException("timeout") }
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"FOOD"}]"""))

        assertEquals(listOf<String?>("FOOD"), classifier().classify(listOf("pizza")))
        server.verify()
    }

    @Test
    fun `a client error is not retried`() {
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.FORBIDDEN))

        assertEquals(listOf<String?>(null), classifier().classify(listOf("pizza")))
        server.verify()
    }

    @Test
    fun `a 429 starts a cool-down during which nothing is sent, then resumes`() {
        val classifier = classifier()
        server.expect(once(), requestTo(url)).andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(HttpHeaders().apply { set("Retry-After", "30") })
        )

        assertEquals(listOf<String?>(null), classifier.classify(listOf("pizza")))
        server.verify()

        // Within the 30 seconds: no request at all.
        server.reset()
        server.expect(never(), requestTo(url))
        now = now.plusSeconds(29)
        assertEquals(listOf<String?>(null), classifier.classify(listOf("pizza")))
        server.verify()

        // After it: back to normal.
        server.reset()
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"FOOD"}]"""))
        now = now.plusSeconds(2)
        assertEquals(listOf<String?>("FOOD"), classifier.classify(listOf("pizza")))
        server.verify()
    }

    @Test
    fun `a 429 without a usable Retry-After falls back to a default pause`() {
        val classifier = classifier()
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        classifier.classify(listOf("pizza"))

        server.reset()
        server.expect(never(), requestTo(url))
        now = now.plus(Duration.ofSeconds(59))
        classifier.classify(listOf("pizza"))
        server.verify()
    }

    @Test
    fun `never calls Gemini without an API key`() {
        server.expect(never(), requestTo(url))

        assertEquals(listOf<String?>(null, null), classifier(apiKey = "  ").classify(listOf("pizza", "netflix")))
        server.verify()
    }

    @Test
    fun `stops sending when the per-minute request budget is used up`() {
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"FOOD"}]"""))

        val result = classifier(maxRequestsPerMinute = 1, maxBatchSize = 1).classify(listOf("pizza", "netflix"))

        assertEquals(listOf<String?>("FOOD", null), result)
        server.verify()
    }

    @Test
    fun `answers one element per description in the same order`() {
        server.expect(once(), requestTo(url)).andRespond(ok("""[{"id":0,"category":"LEISURE"}]"""))

        val result = classifier().classify(listOf("", "netflix", "999"))

        assertEquals(listOf<String?>(null, "LEISURE", null), result)
        assertEquals(emptyList<String?>(), classifier().classify(emptyList()))
    }

    @Test
    fun `the guide covers exactly the closed list`() {
        assertEquals(EntryCategory.ALL, CategoryGuide.meanings.keys.toList())
        assertTrue(CategoryGuide.meanings.values.none { it.isBlank() })
        assertFalse(CategoryGuide.meanings.isEmpty())
    }
}

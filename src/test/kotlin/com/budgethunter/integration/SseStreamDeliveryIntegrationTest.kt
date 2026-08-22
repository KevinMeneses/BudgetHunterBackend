package com.budgethunter.integration

import com.budgethunter.controller.BudgetController
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * End-to-end test for the SSE entries stream over a real HTTP connection.
 *
 * This test exists because of a production bug: the stream never delivered a single
 * byte to clients. Two things were wrong and both are covered here:
 *
 * 1. The first keep-alive was only emitted after 30s, so nothing forced the response
 *    headers and first chunk out on subscribe.
 * 2. nginx buffers proxied responses by default, so even the keep-alive never reached
 *    the client. The `X-Accel-Buffering: no` header turns that off per response.
 *
 * MockMvc cannot verify this - it never writes to a socket. We need a real server and
 * a raw HTTP client that does no SSE decoding, so comment frames (`:keep-alive`) are
 * visible as-is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseStreamDeliveryIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val userEmail = "sse-stream-delivery@example.com"
    private val userPassword = "Password123!"
    private val userName = "SSE Stream Delivery User"

    private lateinit var authToken: String
    private var budgetId: Long = 0

    @BeforeEach
    fun setup() {
        authToken = createAndAuthenticateUser()
        budgetId = createTestBudget()
    }

    @Test
    fun `stream should deliver a keep-alive immediately instead of after the heartbeat interval`() {
        val response = openStream()

        assertEquals(200, response.statusCode())

        val startedAt = System.nanoTime()
        val firstLine = readFirstLine(response)
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        response.body().close()

        // An SSE comment frame - the keep-alive emitted on subscribe
        assertEquals(":keep-alive", firstLine)

        // The whole point: data arrives right away, not one heartbeat interval later
        assertTrue(
            elapsed < BudgetController.HEARTBEAT_INTERVAL,
            "First keep-alive took $elapsed, expected well under ${BudgetController.HEARTBEAT_INTERVAL}"
        )
    }

    @Test
    fun `stream response should tell nginx not to buffer`() {
        val response = openStream()

        val header = response.headers().firstValue(BudgetController.X_ACCEL_BUFFERING_HEADER)
        response.body().close()

        assertEquals("no", header.orElse(null))
    }

    @Test
    fun `stream response should be served as text event-stream`() {
        val response = openStream()

        val contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE)
        response.body().close()

        assertTrue(
            contentType.orElse("").startsWith(MediaType.TEXT_EVENT_STREAM_VALUE),
            "Expected text/event-stream, got ${contentType.orElse("<none>")}"
        )
    }

    @Test
    fun `stream should be rejected without a token`() {
        val response = openStream(token = null)
        response.body().close()

        assertEquals(401, response.statusCode())
    }

    @Test
    fun `stream should be rejected for a budget the user cannot access`() {
        val otherUserToken = createAndAuthenticateUser(
            email = "sse-stream-outsider@example.com",
            name = "Outsider"
        )

        val response = openStream(token = otherUserToken)
        response.body().close()

        assertEquals(EXPECTED_ACCESS_DENIED_STATUS, response.statusCode())
    }

    @Test
    fun `stream should be rejected for a budget that does not exist`() {
        val response = openStream(budgetId = 99999L)
        response.body().close()

        assertEquals(EXPECTED_ACCESS_DENIED_STATUS, response.statusCode())
    }

    /**
     * Opens the stream with a plain JDK HTTP client. Returns as soon as the response
     * headers are available - which, for a streaming response, only happens once the
     * server has actually flushed them.
     */
    private fun openStream(
        token: String? = authToken,
        budgetId: Long = this.budgetId
    ): HttpResponse<java.io.InputStream> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/budgets/$budgetId/entries/stream"))
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .apply { token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") } }
            .timeout(RESPONSE_TIMEOUT)
            .GET()
            .build()

        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    /**
     * Reads the first non-empty line off the stream, failing instead of hanging forever
     * if the server never writes anything.
     */
    private fun readFirstLine(response: HttpResponse<java.io.InputStream>): String {
        val reader = response.body().bufferedReader()
        return CompletableFuture
            .supplyAsync { generateSequence { reader.readLine() }.firstOrNull { it.isNotBlank() } }
            .get(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            ?: throw AssertionError("Stream closed without emitting anything")
    }

    private fun createAndAuthenticateUser(email: String = userEmail, name: String = userName): String {
        // The H2 database is shared across contexts in the same JVM, so the user may
        // already exist from an earlier test class; sign-in is what matters here.
        restTemplate.postForEntity(
            "/api/users/sign_up",
            HttpEntity(SignUpRequest(email = email, name = name, password = userPassword), jsonHeaders()),
            String::class.java
        )

        val response = restTemplate.postForEntity(
            "/api/users/sign_in",
            HttpEntity(SignInRequest(email = email, password = userPassword), jsonHeaders()),
            SignInResponse::class.java
        )

        return requireNotNull(response.body) { "Sign in failed: ${response.statusCode}" }.authToken
    }

    private fun createTestBudget(): Long {
        val headers = jsonHeaders()
        headers.setBearerAuth(authToken)

        val response = restTemplate.postForEntity(
            "/api/budgets",
            HttpEntity(CreateBudgetRequest(name = "SSE Stream Delivery Budget", amount = BigDecimal("1000.00")), headers),
            BudgetResponse::class.java
        )

        return requireNotNull(response.body) { "Budget creation failed: ${response.statusCode}" }.id
    }

    private fun jsonHeaders() = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    companion object {
        private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(10)

        /**
         * Documents current behaviour, which is arguably wrong: a valid token without
         * access to the budget gets **401**, not 403.
         *
         * `streamEntries` reports the failure by returning `Flux.error(ForbiddenAccessException)`,
         * so the exception only surfaces on the async error dispatch. The JWT filter is a
         * `OncePerRequestFilter` and does not re-run on that dispatch, so the SecurityContext
         * is empty by then and Spring Security's entry point answers 401 before the
         * `@ControllerAdvice` can map the exception to 403.
         *
         * Pre-existing and unrelated to the proxy-buffering fix, but asserted here so the
         * quirk stays visible. Fixing it means throwing before returning the Flux (or
         * verifying access in a filter/interceptor) rather than changing this constant.
         */
        private const val EXPECTED_ACCESS_DENIED_STATUS = 401
    }
}

package com.budgethunter.integration

import com.budgethunter.controller.BudgetController
import com.budgethunter.dto.*
import com.budgethunter.model.EntryType
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
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

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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

    @Test
    fun `subscriber receives create update and delete events from another collaborator`() {
        val collaboratorToken = addCollaborator("sse-stream-collaborator@example.com", "Collaborator")

        openStreamReader().use { stream ->
            // The immediate keep-alive proves the sink has a subscriber before we write
            stream.awaitKeepAlive()

            val entry = createEntry(collaboratorToken, "From the collaborator")
            assertEventReceived(stream, BudgetEntryAction.CREATED, "sse-stream-collaborator@example.com")

            updateEntry(collaboratorToken, entry.id, "Updated by the collaborator")
            assertEventReceived(stream, BudgetEntryAction.UPDATED, "sse-stream-collaborator@example.com")

            deleteEntry(collaboratorToken, entry.id)
            assertEventReceived(stream, BudgetEntryAction.DELETED, "sse-stream-collaborator@example.com")
        }
    }

    @Test
    fun `subscriber receives none of its own create update and delete events`() {
        openStreamReader().use { stream ->
            stream.awaitKeepAlive()

            val entry = createEntry(authToken, "From myself")
            updateEntry(authToken, entry.id, "Updated by myself")
            deleteEntry(authToken, entry.id)

            // Echoing these back would make the author re-sync the list they just wrote
            // and show a notification naming themselves.
            val echoed = stream.nextEvent(SELF_ECHO_WINDOW)
            assertNull(echoed, "The author must not receive their own events, but got: $echoed")
        }
    }

    @Test
    fun `own events are filtered per subscriber, so a collaborator still receives them`() {
        val collaboratorToken = addCollaborator("sse-stream-watcher@example.com", "Watcher")

        // Two streams on the same budget: the author's and the collaborator's
        openStreamReader().use { authorStream ->
            openStreamReader(token = collaboratorToken).use { collaboratorStream ->
                authorStream.awaitKeepAlive()
                collaboratorStream.awaitKeepAlive()

                createEntry(authToken, "Written by the author")

                // The collaborator sees it...
                assertEventReceived(collaboratorStream, BudgetEntryAction.CREATED, userEmail)
                // ...while the author does not get their own change echoed back
                assertNull(
                    authorStream.nextEvent(SELF_ECHO_WINDOW),
                    "The author must not receive their own event"
                )
            }
        }
    }

    private fun assertEventReceived(stream: StreamReader, action: BudgetEntryAction, authorEmail: String) {
        val event = stream.nextEvent(EVENT_TIMEOUT)
            ?: fail("No $action event arrived within $EVENT_TIMEOUT")

        assertEquals(action, event.action)
        assertEquals(authorEmail, event.userInfo.email)
        assertEquals(budgetId, event.budgetId)
    }

    /**
     * Reads an SSE stream off a background thread so a test can interleave writes with
     * reads. Comment frames (`:keep-alive`) and data frames are kept apart, because the
     * point of most of these tests is which data frames do *not* arrive.
     */
    private inner class StreamReader(
        private val response: HttpResponse<java.io.InputStream>
    ) : AutoCloseable {
        private val lines = LinkedBlockingQueue<String>()

        init {
            Thread {
                runCatching { response.body().bufferedReader().forEachLine { lines.put(it) } }
            }.apply { isDaemon = true }.start()
        }

        /** Waits for the keep-alive emitted on subscribe; proves the stream is live. */
        fun awaitKeepAlive() {
            val deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos()
            while (System.nanoTime() < deadline) {
                val line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
                if (line.startsWith(":")) return
            }
            fail<Unit>("Stream never emitted a keep-alive")
        }

        /** Next `data:` frame, or null if none arrives within [timeout]. */
        fun nextEvent(timeout: Duration): BudgetEntryEvent? {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                val line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: return null
                if (line.startsWith("data:")) {
                    return objectMapper.readValue(
                        line.removePrefix("data:").trim(),
                        BudgetEntryEvent::class.java
                    )
                }
            }
            return null
        }

        override fun close() = response.body().close()
    }

    private fun openStreamReader(token: String = authToken) = StreamReader(openStream(token = token))

    private fun addCollaborator(email: String, name: String): String {
        val collaboratorToken = createAndAuthenticateUser(email = email, name = name)

        val headers = jsonHeaders()
        headers.setBearerAuth(authToken)
        restTemplate.postForEntity(
            "/api/budgets/$budgetId/collaborators",
            HttpEntity(AddCollaboratorRequest(budgetId, email), headers),
            String::class.java
        )

        return collaboratorToken
    }

    private fun createEntry(token: String, description: String): BudgetEntryResponse {
        val headers = jsonHeaders()
        headers.setBearerAuth(token)
        val request = CreateBudgetEntryRequest(
            amount = BigDecimal("42.00"),
            description = description,
            category = "SSE",
            type = EntryType.OUTCOME
        )

        val response = restTemplate.postForEntity(
            "/api/budgets/$budgetId/entries",
            HttpEntity(request, headers),
            BudgetEntryResponse::class.java
        )

        return requireNotNull(response.body) { "Entry creation failed: ${response.statusCode}" }
    }

    private fun updateEntry(token: String, entryId: Long, description: String) {
        val headers = jsonHeaders()
        headers.setBearerAuth(token)
        val request = UpdateBudgetEntryRequest(
            amount = BigDecimal("84.00"),
            description = description,
            category = "SSE",
            type = EntryType.OUTCOME
        )

        restTemplate.exchange(
            "/api/budgets/$budgetId/entries/$entryId",
            HttpMethod.PUT,
            HttpEntity(request, headers),
            String::class.java
        )
    }

    private fun deleteEntry(token: String, entryId: Long) {
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        restTemplate.exchange(
            "/api/budgets/$budgetId/entries/$entryId",
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            String::class.java
        )
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

        /** How long to wait for an event that should arrive. */
        private val EVENT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * How long to wait to be convinced an event will NOT arrive. Deliberately shorter
         * than the 15s heartbeat, so a keep-alive never gets mistaken for a data frame.
         */
        private val SELF_ECHO_WINDOW: Duration = Duration.ofSeconds(3)

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

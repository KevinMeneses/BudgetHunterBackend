package com.budgethunter.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Boots the full context on purpose: the risk being guarded is Spring's own conversion of one
 * comma-separated property into a list, which a hand-built adapter would never exercise.
 */
@SpringBootTest(
    properties = [
        "google.auth.client-ids=web-client-id.apps.googleusercontent.com, ios-client-id.apps.googleusercontent.com"
    ]
)
class GoogleIdTokenVerifierAdapterTest {

    @Autowired
    private lateinit var adapter: GoogleIdTokenVerifierAdapter

    @Test
    fun `accepts every configured client id as an audience`() {
        // Android tokens carry the web client id and iOS tokens the iOS one. If the list collapsed
        // into a single "a, b" entry, one platform would be rejected with a perfectly valid token.
        assertEquals(
            listOf(
                "web-client-id.apps.googleusercontent.com",
                "ios-client-id.apps.googleusercontent.com"
            ),
            adapter.audiences
        )
    }
}

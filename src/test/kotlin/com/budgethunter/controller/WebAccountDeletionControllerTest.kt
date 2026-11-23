package com.budgethunter.controller

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebAccountDeletionControllerTest {

    private fun googleClientId(webClientId: String, clientIds: List<String>) =
        WebAccountDeletionController(mockk(), webClientId, clientIds).config().body!!.googleClientId

    @Test
    fun `the page gets the first client id, which is the web one`() {
        assertEquals("web-id", googleClientId("", listOf(" ", " web-id ", "ios-id")))
    }

    @Test
    fun `an explicit web client id wins`() {
        assertEquals("explicit", googleClientId(" explicit ", listOf("web-id", "ios-id")))
    }

    @Test
    fun `no client id hides the Google button`() {
        assertNull(googleClientId("", emptyList()))
    }
}

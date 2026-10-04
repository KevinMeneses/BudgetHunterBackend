package com.budgethunter.controller

import com.budgethunter.dto.UpdateUserPreferencesRequest
import com.budgethunter.dto.UserPreferencesResponse
import com.budgethunter.service.UserPreferencesService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication

class UserPreferencesControllerTest {

    private val service = mockk<UserPreferencesService>()
    private val controller = UserPreferencesController(service)
    private val authentication = mockk<Authentication> {
        every { principal } returns "test@example.com"
    }

    @Test
    fun `get returns the saved preferences of the caller`() {
        val prefs = UserPreferencesResponse(true, false, 4L, listOf("nequi"))
        every { service.get("test@example.com") } returns prefs

        assertEquals(prefs, controller.get(authentication).body)
    }

    @Test
    fun `update saves for the caller and returns what was stored`() {
        val request = UpdateUserPreferencesRequest(true, false, 4L, listOf("nequi"))
        val saved = UserPreferencesResponse(true, false, 4L, listOf("nequi"))
        every { service.update("test@example.com", request) } returns saved

        assertEquals(saved, controller.update(request, authentication).body)
    }
}

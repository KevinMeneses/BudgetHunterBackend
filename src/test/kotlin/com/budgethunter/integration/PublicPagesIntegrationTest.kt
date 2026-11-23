package com.budgethunter.integration

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The pages the Play Store listing links to must be reachable without an account. */
@SpringBootTest
@AutoConfigureMockMvc
class PublicPagesIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    fun `the privacy policy is public`() {
        mockMvc.perform(get("/privacidad"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/privacidad.html"))
        val page = mockMvc.perform(get("/privacidad.html"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andReturn().response.contentAsByteArray.toString(Charsets.UTF_8)
        // The file declares its charset itself (<meta charset="utf-8">), which is what browsers go by
        assertThat(page, containsString("<meta charset=\"utf-8\">"))
        assertThat(page, containsString("Política de privacidad de BudgetHunter"))
    }
}

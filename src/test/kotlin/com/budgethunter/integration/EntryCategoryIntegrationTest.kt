package com.budgethunter.integration

import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** The optional `category` and the UNCATEGORIZED placeholder it falls back to, as the app sees them over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EntryCategoryIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    private val email = "category@example.com"
    private lateinit var token: String
    private var budgetId = 0L

    @BeforeEach
    fun setup() {
        mockMvc.perform(
            post("/api/users/sign_up").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(email, "Category User", "Password123!")))
        )
        val signIn = mockMvc.perform(
            post("/api/users/sign_in").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(email, "Password123!")))
        ).andReturn()
        token = objectMapper.readValue(signIn.response.contentAsString, SignInResponse::class.java).authToken

        val budget = mockMvc.perform(
            post("/api/budgets").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateBudgetRequest("Budget", BigDecimal("100.00"))))
        ).andReturn()
        budgetId = objectMapper.readTree(budget.response.contentAsString)["id"].asLong()
    }

    private fun setAiProcessing(enabled: Boolean?) {
        val user = userRepository.findById(email).get()
        user.aiProcessingEnabled = enabled
        userRepository.save(user)
    }

    private fun postEntry(body: String) = mockMvc.perform(
        post("/api/budgets/$budgetId/entries").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content(body)
    )

    @Test
    fun `an entry created with a category echoes it back`() {
        setAiProcessing(true)

        postEntry("""{"amount":5.00,"description":"cab","category":"TRANSPORTATION","type":"OUTCOME"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.category").value("TRANSPORTATION"))
    }

    @Test
    fun `an entry created without a category is uncategorized`() {
        setAiProcessing(true)

        postEntry("""{"amount":5.00,"description":"cab","type":"OUTCOME"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.category").value("UNCATEGORIZED"))
    }

    @Test
    fun `an entry created without a category is uncategorized even when AI processing was never saved`() {
        setAiProcessing(null)

        postEntry("""{"amount":5.00,"description":"cab","type":"OUTCOME"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.category").value("UNCATEGORIZED"))
    }

    @Test
    fun `a category longer than the column is rejected instead of failing in the database`() {
        postEntry("""{"amount":5.00,"category":"${"x".repeat(256)}","type":"OUTCOME"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `old clients that always send a category keep working through update and list`() {
        setAiProcessing(true)
        val created = postEntry("""{"amount":5.00,"description":"cab","category":"Food","type":"OUTCOME"}""")
            .andReturn()
        val entryId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        mockMvc.perform(
            put("/api/budgets/$budgetId/entries/$entryId").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":6.00,"description":"cab","category":"Transport","type":"OUTCOME"}""")
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/budgets/$budgetId/entries").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].category").value("Transport"))

        mockMvc.perform(
            get("/api/budgets/$budgetId/entries").header("Authorization", "Bearer $token")
                .param("page", "0").param("size", "10").param("sortBy", "category")
        ).andExpect(status().isOk).andExpect(jsonPath("$.content[0].category").value("Transport"))
    }
}

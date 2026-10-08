package com.budgethunter.integration

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.repository.UserRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.UUID

/**
 * `POST /api/budgets/{id}/entries/categorize` through the whole stack. No Gemini key is configured, so only
 * the keyword rules answer ("Netflix" is LEISURE, "Farmacia" is HEALTH) - enough to prove the behaviour
 * without the network.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BudgetCategorizationIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    private val run = UUID.randomUUID().toString().take(8)
    private val owner = "owner-$run@example.com"
    private val partner = "partner-$run@example.com"
    private val stranger = "stranger-$run@example.com"
    private lateinit var ownerToken: String
    private lateinit var partnerToken: String
    private lateinit var strangerToken: String
    private var budgetId = 0L

    @BeforeEach
    fun setup() {
        ownerToken = signUpAndIn(owner)
        partnerToken = signUpAndIn(partner)
        strangerToken = signUpAndIn(stranger)
        val budget = mockMvc.perform(
            post("/api/budgets").header("Authorization", "Bearer $ownerToken").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateBudgetRequest("Budget", BigDecimal("100.00"))))
        ).andReturn()
        budgetId = objectMapper.readTree(budget.response.contentAsString)["id"].asLong()
    }

    private fun signUpAndIn(email: String): String {
        mockMvc.perform(
            post("/api/users/sign_up").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(email, "User", "Password123!")))
        )
        val result = mockMvc.perform(
            post("/api/users/sign_in").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(email, "Password123!")))
        ).andReturn()
        return objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java).authToken
    }

    private fun setAiProcessing(email: String, enabled: Boolean?) {
        val user = userRepository.findById(email).get()
        user.aiProcessingEnabled = enabled
        userRepository.save(user)
    }

    private fun createEntry(token: String, description: String, category: String? = null): Long {
        val categoryJson = category?.let { ""","category":"$it"""" }.orEmpty()
        val result = mockMvc.perform(
            post("/api/budgets/$budgetId/entries").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":5.00,"description":"$description"$categoryJson,"type":"OUTCOME"}""")
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asLong()
    }

    private fun categorize(token: String): ResultActions =
        mockMvc.perform(post("/api/budgets/$budgetId/entries/categorize").header("Authorization", "Bearer $token"))

    private fun entries(): Map<Long, JsonNode> {
        val body = mockMvc.perform(get("/api/budgets/$budgetId/entries").header("Authorization", "Bearer $ownerToken"))
            .andReturn().response.contentAsString
        return objectMapper.readTree(body).associateBy { it["id"].asLong() }
    }

    @Test
    fun `categorises only the waiting entries and never what a person chose`() {
        setAiProcessing(owner, true)
        val netflix = createEntry(ownerToken, "Netflix")
        val pharmacy = createEntry(ownerToken, "Farmacia Cruz Verde")
        val unknown = createEntry(ownerToken, "zzz unknown shop")
        val chosen = createEntry(ownerToken, "Netflix", category = "Food")

        categorize(ownerToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.categorized").value(2))
            .andExpect(jsonPath("$.pending").value(1))

        val after = entries()
        assertEquals("LEISURE", after.getValue(netflix)["category"].asText())
        assertEquals("HEALTH", after.getValue(pharmacy)["category"].asText())
        assertEquals("AUTO", after.getValue(netflix)["categorySource"].asText())
        assertEquals("OTHER", after.getValue(unknown)["category"].asText())
        assertEquals("Food", after.getValue(chosen)["category"].asText())
        assertEquals("USER", after.getValue(chosen)["categorySource"].asText())
        // Compared by value: JSON drops the trailing zero of 5.00.
        assertEquals(0, BigDecimal("5.00").compareTo(after.getValue(netflix)["amount"].decimalValue()))
    }

    @Test
    fun `a second run does not redo the first`() {
        setAiProcessing(owner, true)
        createEntry(ownerToken, "Netflix")
        categorize(ownerToken).andExpect(jsonPath("$.categorized").value(1))

        categorize(ownerToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.categorized").value(0))
            .andExpect(jsonPath("$.pending").value(0))
    }

    @Test
    fun `an entry the person edited since keeps their category`() {
        setAiProcessing(owner, true)
        val entryId = createEntry(ownerToken, "Netflix")
        mockMvc.perform(
            put("/api/budgets/$budgetId/entries/$entryId").header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":5.00,"description":"Netflix","category":"Health","type":"OUTCOME"}""")
        ).andExpect(status().isOk)

        categorize(ownerToken).andExpect(jsonPath("$.categorized").value(0))

        assertEquals("Health", entries().getValue(entryId)["category"].asText())
    }

    @Test
    fun `is refused when the account has not turned AI processing on`() {
        setAiProcessing(owner, true)
        val entryId = createEntry(ownerToken, "Netflix")

        listOf(false, null).forEach { preference ->
            setAiProcessing(owner, preference)
            categorize(ownerToken).andExpect(status().isForbidden)
        }

        assertEquals("OTHER", entries().getValue(entryId)["category"].asText())
    }

    @Test
    fun `needs access to the budget`() {
        setAiProcessing(stranger, true)
        categorize(strangerToken).andExpect(status().isForbidden)
    }

    @Test
    fun `needs a token`() {
        mockMvc.perform(post("/api/budgets/$budgetId/entries/categorize")).andExpect(status().is4xxClientError)
    }

    @Test
    fun `leaves alone the entries of a collaborator who has since turned AI processing off`() {
        setAiProcessing(owner, true)
        setAiProcessing(partner, true)
        mockMvc.perform(
            post("/api/budgets/$budgetId/collaborators").header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddCollaboratorRequest(budgetId, partner)))
        ).andExpect(status().isCreated)
        val ownersEntry = createEntry(ownerToken, "Netflix")
        val partnersEntry = createEntry(partnerToken, "Farmacia")
        setAiProcessing(partner, false)

        categorize(ownerToken).andExpect(jsonPath("$.categorized").value(1))

        val after = entries()
        assertEquals("LEISURE", after.getValue(ownersEntry)["category"].asText())
        assertEquals("OTHER", after.getValue(partnersEntry)["category"].asText())
    }
}

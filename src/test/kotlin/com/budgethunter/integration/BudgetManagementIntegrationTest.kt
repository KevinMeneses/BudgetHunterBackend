package com.budgethunter.integration

import com.budgethunter.dto.*
import com.budgethunter.model.EntryType
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BudgetManagementIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val user1Email = "user1@example.com"
    private val user1Password = "Password123!"
    private val user1Name = "User One"

    private val user2Email = "user2@example.com"
    private val user2Password = "Password123!"
    private val user2Name = "User Two"

    private lateinit var user1AuthToken: String
    private lateinit var user2AuthToken: String

    @BeforeEach
    fun setup() {
        // Create and authenticate user 1
        user1AuthToken = createAndAuthenticateUser(user1Email, user1Name, user1Password)

        // Create and authenticate user 2
        user2AuthToken = createAndAuthenticateUser(user2Email, user2Name, user2Password)
    }

    private fun createAndAuthenticateUser(email: String, name: String, password: String): String {
        val signUpRequest = SignUpRequest(email = email, name = name, password = password)

        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        )

        val signInRequest = SignInRequest(email = email, password = password)

        val result = mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInRequest))
        ).andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)
        return response.authToken
    }

    // Budget Creation Tests

    @Test
    fun `should create budget successfully with authentication`() {
        // Given
        val request = CreateBudgetRequest(
            name = "Monthly Budget",
            amount = BigDecimal("2500.00")
        )

        // When & Then
        val result = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.name").value("Monthly Budget"))
            .andExpect(jsonPath("$.amount").value(2500.00))
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java)
        assertNotNull(response.id)
        assertTrue(response.id > 0)
    }

    @Test
    fun `should return 403 when creating budget without authentication`() {
        // Given
        val request = CreateBudgetRequest(
            name = "Test Budget",
            amount = BigDecimal("1000.00")
        )

        // When & Then
        mockMvc.perform(
            post("/api/budgets/create_budget")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isForbidden)
    }

    // Get Budgets Tests

    @Test
    fun `should get all budgets for authenticated user`() {
        // Given - Create two budgets
        val budget1 = CreateBudgetRequest(name = "Budget 1", amount = BigDecimal("1000.00"))
        val budget2 = CreateBudgetRequest(name = "Budget 2", amount = BigDecimal("2000.00"))

        mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(budget1))
        )

        mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(budget2))
        )

        // When & Then
        val result = mockMvc.perform(
            get("/api/budgets/get_budgets")
                .header("Authorization", "Bearer $user1AuthToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn()

        val budgets = objectMapper.readValue(result.response.contentAsString, Array<BudgetResponse>::class.java)
        assertEquals(2, budgets.size)
    }

    @Test
    fun `should return empty list when user has no budgets`() {
        // When & Then
        mockMvc.perform(
            get("/api/budgets/get_budgets")
                .header("Authorization", "Bearer $user1AuthToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(0))
    }

    // Collaborator Tests

    @Test
    fun `should add collaborator to budget successfully`() {
        // Given - User 1 creates a budget
        val createRequest = CreateBudgetRequest(name = "Shared Budget", amount = BigDecimal("3000.00"))

        val createResult = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        ).andReturn()

        val budget = objectMapper.readValue(createResult.response.contentAsString, BudgetResponse::class.java)

        // When - User 1 adds User 2 as collaborator
        val addCollaboratorRequest = AddCollaboratorRequest(
            budgetId = budget.id,
            email = user2Email
        )

        mockMvc.perform(
            post("/api/budgets/add_collaborator")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addCollaboratorRequest))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.budgetId").value(budget.id))
            .andExpect(jsonPath("$.collaboratorEmail").value(user2Email))
            .andExpect(jsonPath("$.collaboratorName").value(user2Name))

        // Then - User 2 should see the budget
        mockMvc.perform(
            get("/api/budgets/get_budgets")
                .header("Authorization", "Bearer $user2AuthToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(budget.id))
    }

    @Test
    fun `should not allow adding collaborator without budget access`() {
        // Given - User 1 creates a budget
        val createRequest = CreateBudgetRequest(name = "Private Budget", amount = BigDecimal("1000.00"))

        val createResult = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        ).andReturn()

        val budget = objectMapper.readValue(createResult.response.contentAsString, BudgetResponse::class.java)

        // When - User 2 tries to add collaborator to User 1's budget
        val addCollaboratorRequest = AddCollaboratorRequest(
            budgetId = budget.id,
            email = user2Email
        )

        mockMvc.perform(
            post("/api/budgets/add_collaborator")
                .header("Authorization", "Bearer $user2AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addCollaboratorRequest))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `should get all collaborators for a budget`() {
        // Given - Create budget and add collaborator
        val createRequest = CreateBudgetRequest(name = "Team Budget", amount = BigDecimal("5000.00"))

        val createResult = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        ).andReturn()

        val budget = objectMapper.readValue(createResult.response.contentAsString, BudgetResponse::class.java)

        val addCollaboratorRequest = AddCollaboratorRequest(budgetId = budget.id, email = user2Email)

        mockMvc.perform(
            post("/api/budgets/add_collaborator")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addCollaboratorRequest))
        )

        // When & Then
        val result = mockMvc.perform(
            get("/api/budgets/get_collaborators")
                .header("Authorization", "Bearer $user1AuthToken")
                .param("budgetId", budget.id.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2)) // User 1 and User 2
            .andReturn()

        val collaborators = objectMapper.readValue(result.response.contentAsString, Array<UserResponse>::class.java)
        assertEquals(2, collaborators.size)
        assertTrue(collaborators.any { it.email == user1Email })
        assertTrue(collaborators.any { it.email == user2Email })
    }

    // Budget Entry Tests

    @Test
    fun `should create budget entry successfully`() {
        // Given - Create a budget
        val createBudgetRequest = CreateBudgetRequest(name = "Expense Budget", amount = BigDecimal("1000.00"))

        val budgetResult = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createBudgetRequest))
        ).andReturn()

        val budget = objectMapper.readValue(budgetResult.response.contentAsString, BudgetResponse::class.java)

        // When - Create entry
        val entryRequest = PutEntryRequest(
            id = null,
            budgetId = budget.id,
            amount = BigDecimal("150.00"),
            description = "Groceries",
            category = "Food",
            type = EntryType.OUTCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entryRequest))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.budgetId").value(budget.id))
            .andExpect(jsonPath("$.amount").value(150.00))
            .andExpect(jsonPath("$.description").value("Groceries"))
            .andExpect(jsonPath("$.createdByEmail").value(user1Email))
    }

    @Test
    fun `should update budget entry successfully`() {
        // Given - Create budget and entry
        val createBudgetRequest = CreateBudgetRequest(name = "Test Budget", amount = BigDecimal("1000.00"))

        val budgetResult = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createBudgetRequest))
        ).andReturn()

        val budget = objectMapper.readValue(budgetResult.response.contentAsString, BudgetResponse::class.java)

        val createEntryRequest = PutEntryRequest(
            id = null,
            budgetId = budget.id,
            amount = BigDecimal("100.00"),
            description = "Original",
            category = "Test",
            type = EntryType.OUTCOME
        )

        val entryResult = mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createEntryRequest))
        ).andReturn()

        val entry = objectMapper.readValue(entryResult.response.contentAsString, BudgetEntryResponse::class.java)

        // When - Update entry
        val updateEntryRequest = PutEntryRequest(
            id = entry.id,
            budgetId = budget.id,
            amount = BigDecimal("200.00"),
            description = "Updated",
            category = "Updated Category",
            type = EntryType.INCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateEntryRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(entry.id))
            .andExpect(jsonPath("$.amount").value(200.00))
            .andExpect(jsonPath("$.description").value("Updated"))
            .andExpect(jsonPath("$.updatedByEmail").value(user1Email))
    }

    @Test
    fun `should get all entries for a budget`() {
        // Given - Create budget with entries
        val createBudgetRequest = CreateBudgetRequest(name = "Entry Budget", amount = BigDecimal("1000.00"))

        val budgetResult = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createBudgetRequest))
        ).andReturn()

        val budget = objectMapper.readValue(budgetResult.response.contentAsString, BudgetResponse::class.java)

        // Create two entries
        val entry1 = PutEntryRequest(null, budget.id, BigDecimal("100.00"), "Entry 1", "Cat1", EntryType.OUTCOME)
        val entry2 = PutEntryRequest(null, budget.id, BigDecimal("200.00"), "Entry 2", "Cat2", EntryType.INCOME)

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry1))
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry2))
        )

        // When & Then
        mockMvc.perform(
            get("/api/budgets/get_entries")
                .header("Authorization", "Bearer $user1AuthToken")
                .param("budgetId", budget.id.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    // Complete Workflow Test

    @Test
    fun `complete budget management workflow should work correctly`() {
        // Step 1: Create budget
        val createBudgetRequest = CreateBudgetRequest(name = "Family Budget", amount = BigDecimal("5000.00"))

        val budgetResult = mockMvc.perform(
            post("/api/budgets/create_budget")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createBudgetRequest))
        ).andReturn()

        val budget = objectMapper.readValue(budgetResult.response.contentAsString, BudgetResponse::class.java)

        // Step 2: Add collaborator
        val addCollaboratorRequest = AddCollaboratorRequest(budgetId = budget.id, email = user2Email)

        mockMvc.perform(
            post("/api/budgets/add_collaborator")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addCollaboratorRequest))
        ).andExpect(status().isCreated)

        // Step 3: User 1 adds entry
        val entry1Request = PutEntryRequest(
            null, budget.id, BigDecimal("300.00"), "User 1 Entry", "Shopping", EntryType.OUTCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user1AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry1Request))
        ).andExpect(status().isCreated)

        // Step 4: User 2 adds entry (as collaborator)
        val entry2Request = PutEntryRequest(
            null, budget.id, BigDecimal("500.00"), "User 2 Entry", "Income", EntryType.INCOME
        )

        mockMvc.perform(
            put("/api/budgets/put_entry")
                .header("Authorization", "Bearer $user2AuthToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry2Request))
        ).andExpect(status().isCreated)

        // Step 5: Both users can see all entries
        val user1Entries = mockMvc.perform(
            get("/api/budgets/get_entries")
                .header("Authorization", "Bearer $user1AuthToken")
                .param("budgetId", budget.id.toString())
        )
            .andExpect(status().isOk)
            .andReturn()

        val user2Entries = mockMvc.perform(
            get("/api/budgets/get_entries")
                .header("Authorization", "Bearer $user2AuthToken")
                .param("budgetId", budget.id.toString())
        )
            .andExpect(status().isOk)
            .andReturn()

        val entries1 = objectMapper.readValue(user1Entries.response.contentAsString, Array<BudgetEntryResponse>::class.java)
        val entries2 = objectMapper.readValue(user2Entries.response.contentAsString, Array<BudgetEntryResponse>::class.java)

        assertEquals(2, entries1.size)
        assertEquals(2, entries2.size)
        assertTrue(entries1.any { it.createdByEmail == user1Email })
        assertTrue(entries1.any { it.createdByEmail == user2Email })
    }
}

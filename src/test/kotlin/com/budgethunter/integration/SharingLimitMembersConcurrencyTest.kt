package com.budgethunter.integration

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.service.BudgetService
import com.budgethunter.service.UserService
import com.budgethunter.sharing.SharingLimitExceededException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Several members of one FREE owner's budget adding people at the same moment. With the default limit (one
 * person besides the owner) a budget with two members is already full, so this runs with room for three.
 */
@SpringBootTest(properties = ["sharing.free-collaborators-per-budget=3"])
class SharingLimitMembersConcurrencyTest {

    @Autowired private lateinit var userService: UserService

    @Autowired private lateinit var budgetService: BudgetService

    @Autowired private lateinit var userBudgetRepository: UserBudgetRepository

    private val run = UUID.randomUUID().toString().take(8)

    @Test
    fun `members adding at once never take the budget past its owner's limit`() {
        val owner = signUp("owner")
        val members = listOf(signUp("m1"), signUp("m2"))
        val budgetId = budgetService.createBudget(CreateBudgetRequest("Shared", BigDecimal.TEN), owner).id
        members.forEach { budgetService.addCollaborator(budgetId, AddCollaboratorRequest(budgetId, it), owner) }
        val callers = listOf(owner) + members
        val people = (1..PARALLEL_REQUESTS).map { signUp("p$it") }

        val pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        val added = try {
            val attempts = people.mapIndexed { i, person ->
                Callable {
                    try {
                        budgetService.addCollaborator(budgetId, AddCollaboratorRequest(budgetId, person), callers[i % callers.size])
                        true
                    } catch (_: SharingLimitExceededException) {
                        false
                    }
                }
            }
            pool.invokeAll(attempts).count { it.get() }
        } finally {
            pool.shutdown()
        }

        // Room for 3 besides the owner, 2 taken: exactly one more.
        assertEquals(1, added)
        assertEquals(4L, userBudgetRepository.countByBudgetId(budgetId))
    }

    private fun signUp(name: String): String {
        val email = "$name-$run@example.com"
        userService.signUp(SignUpRequest(email = email, name = name, password = "Password123!"))
        return email
    }

    private companion object {
        const val PARALLEL_REQUESTS = 20
    }
}

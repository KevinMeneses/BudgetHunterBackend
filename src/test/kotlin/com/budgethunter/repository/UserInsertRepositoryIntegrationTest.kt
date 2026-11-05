package com.budgethunter.repository

import com.budgethunter.model.AuthProvider
import com.budgethunter.model.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate

/**
 * Runs each step in its own committed transaction, the way two concurrent sign-ups would,
 * so it checks what actually reaches the database rather than a shared persistence context.
 */
@SpringBootTest
class UserInsertRepositoryIntegrationTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private val email = "insert-race@example.com"

    @AfterEach
    fun cleanup() {
        transactionTemplate.executeWithoutResult { userRepository.deleteById(email) }
    }

    @Test
    fun `insert of an email that already exists fails and leaves the first account untouched`() {
        // Given - the request that won the race has committed its account
        transactionTemplate.executeWithoutResult {
            userRepository.insert(User(email = email, name = "First", password = "first-hash"))
        }

        // When - the losing request tries to create the same account
        assertThrows<DataIntegrityViolationException> {
            transactionTemplate.executeWithoutResult {
                userRepository.insert(
                    User(email = email, name = "Second", password = null, authProvider = AuthProvider.GOOGLE)
                )
            }
        }

        // Then - with save() this would have been a merge that replaced the name and password
        val stored = transactionTemplate.execute { userRepository.findById(email).orElseThrow() }!!
        assertEquals("First", stored.name)
        assertEquals("first-hash", stored.password)
        assertEquals(AuthProvider.PASSWORD, stored.authProvider)
    }
}

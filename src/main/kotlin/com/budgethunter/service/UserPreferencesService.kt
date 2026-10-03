package com.budgethunter.service

import com.budgethunter.dto.UpdateUserPreferencesRequest
import com.budgethunter.dto.UserPreferencesResponse
import com.budgethunter.exception.ForbiddenAccessException
import com.budgethunter.model.User
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserPreferencesService(
    private val userRepository: UserRepository,
    private val userBudgetRepository: UserBudgetRepository
) {

    @Transactional(readOnly = true)
    fun get(email: String): UserPreferencesResponse {
        val user = findUser(email)
        return UserPreferencesResponse(
            smsReadingEnabled = user.smsReadingEnabled,
            aiProcessingEnabled = user.aiProcessingEnabled,
            // The budget may have been deleted, or the user removed from it, since it was saved.
            // Handing back an id they cannot open would only make the client chase a dead link.
            defaultBudgetId = user.defaultBudgetId?.takeIf { hasAccess(it, email) },
            selectedBankIds = user.selectedBankIds?.let(::decodeBankIds)
        )
    }

    @Transactional
    fun update(email: String, request: UpdateUserPreferencesRequest): UserPreferencesResponse {
        val user = findUser(email)

        request.defaultBudgetId?.let {
            if (!hasAccess(it, email)) {
                throw ForbiddenAccessException("User does not have access to budget $it")
            }
        }

        val bankIds = request.selectedBankIds.map { it.trim() }
        require(bankIds.none { it.isEmpty() || ',' in it }) { "Bank ids must be non-empty and contain no commas" }

        user.smsReadingEnabled = request.smsReadingEnabled
        user.aiProcessingEnabled = request.aiProcessingEnabled
        user.defaultBudgetId = request.defaultBudgetId
        user.selectedBankIds = bankIds.distinct().joinToString(",")
        userRepository.save(user)

        return get(email)
    }

    private fun findUser(email: String): User =
        userRepository.findById(email).orElseThrow { BadCredentialsException("User not found") }

    private fun hasAccess(budgetId: Long, email: String) =
        userBudgetRepository.existsById(UserBudgetId(budgetId, email))

    private fun decodeBankIds(stored: String) =
        stored.split(',').filter { it.isNotBlank() }
}

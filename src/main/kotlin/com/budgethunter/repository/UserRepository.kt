package com.budgethunter.repository

import com.budgethunter.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, String> {
    fun existsByEmail(email: String): Boolean
    fun findByRefreshToken(refreshToken: String): Optional<User>
}

package com.budgethunter.service

import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.model.User
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.JwtUtil
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil
) {

    @Transactional
    fun signUp(request: SignUpRequest): UserResponse {
        if (userRepository.existsByEmail(request.email)) {
            throw IllegalArgumentException("Email already exists")
        }

        val user = User(
            email = request.email,
            name = request.name,
            password = passwordEncoder.encode(request.password)
        )

        val savedUser = userRepository.save(user)

        return UserResponse(
            email = savedUser.email,
            name = savedUser.name
        )
    }

    @Transactional
    fun signIn(request: SignInRequest): SignInResponse {
        val user = userRepository.findById(request.email)
            .orElseThrow { BadCredentialsException("Invalid email or password") }

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw BadCredentialsException("Invalid email or password")
        }

        val authToken = jwtUtil.generateToken(user.email)
        val refreshToken = jwtUtil.generateRefreshToken()
        val refreshTokenExpiry = jwtUtil.getRefreshTokenExpiry()

        // Store refresh token in database
        user.refreshToken = refreshToken
        user.refreshTokenExpiry = refreshTokenExpiry
        userRepository.save(user)

        return SignInResponse(
            authToken = authToken,
            refreshToken = refreshToken,
            email = user.email,
            name = user.name
        )
    }

    @Transactional
    fun refreshToken(request: RefreshTokenRequest): SignInResponse {
        val user = userRepository.findByRefreshToken(request.refreshToken)
            .orElseThrow { BadCredentialsException("Invalid refresh token") }

        // Validate refresh token expiry
        if (user.refreshTokenExpiry == null || user.refreshTokenExpiry!!.isBefore(Instant.now())) {
            throw BadCredentialsException("Refresh token has expired")
        }

        val authToken = jwtUtil.generateToken(user.email)

        // Optional: Rotate refresh token for enhanced security
        val newRefreshToken = jwtUtil.generateRefreshToken()
        val newRefreshTokenExpiry = jwtUtil.getRefreshTokenExpiry()

        user.refreshToken = newRefreshToken
        user.refreshTokenExpiry = newRefreshTokenExpiry
        userRepository.save(user)

        return SignInResponse(
            authToken = authToken,
            refreshToken = newRefreshToken,
            email = user.email,
            name = user.name
        )
    }
}

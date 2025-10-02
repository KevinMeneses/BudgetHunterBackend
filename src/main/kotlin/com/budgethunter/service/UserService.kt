package com.budgethunter.service

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

    @Transactional(readOnly = true)
    fun signIn(request: SignInRequest): SignInResponse {
        val user = userRepository.findById(request.email)
            .orElseThrow { BadCredentialsException("Invalid email or password") }

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw BadCredentialsException("Invalid email or password")
        }

        val token = jwtUtil.generateToken(user.email)

        return SignInResponse(
            authToken = token,
            email = user.email,
            name = user.name
        )
    }
}

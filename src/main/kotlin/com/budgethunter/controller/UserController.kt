package com.budgethunter.controller

import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.service.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService
) {

    @PostMapping("/sign_up")
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<UserResponse> {
        val response = userService.signUp(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/sign_in")
    fun signIn(@Valid @RequestBody request: SignInRequest): ResponseEntity<SignInResponse> {
        val response = userService.signIn(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/refresh_token")
    fun refreshToken(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<SignInResponse> {
        val response = userService.refreshToken(request)
        return ResponseEntity.ok(response)
    }
}

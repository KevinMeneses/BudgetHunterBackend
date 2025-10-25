package com.budgethunter.controller

import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
@Tag(name = "User Management", description = "Endpoints for user registration, authentication, and token management")
class UserController(
    private val userService: UserService
) {

    @PostMapping("/sign_up")
    @Operation(
        summary = "Register a new user",
        description = "Creates a new user account with email and password. Email must be unique and valid. Password must meet security requirements."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "User successfully registered",
                content = [Content(schema = Schema(implementation = UserResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors (e.g., invalid email format, weak password)",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "409",
                description = "User with this email already exists",
                content = [Content()]
            )
        ]
    )
    @SecurityRequirements
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<UserResponse> {
        val response = userService.signUp(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/sign_in")
    @Operation(
        summary = "Authenticate user",
        description = "Authenticates a user with email and password. Returns JWT access token (15 minutes expiry) and refresh token (7 days expiry) for subsequent authenticated requests."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Successfully authenticated. Returns access token and refresh token.",
                content = [Content(schema = Schema(implementation = SignInResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Invalid credentials - incorrect email or password",
                content = [Content()]
            )
        ]
    )
    @SecurityRequirements
    fun signIn(@Valid @RequestBody request: SignInRequest): ResponseEntity<SignInResponse> {
        val response = userService.signIn(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/refresh_token")
    @Operation(
        summary = "Refresh authentication tokens",
        description = "Exchanges a valid refresh token for a new access token and refresh token pair. Implements token rotation - the old refresh token is invalidated and a new one is issued."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Successfully refreshed tokens. Returns new access token and refresh token.",
                content = [Content(schema = Schema(implementation = SignInResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request - validation errors",
                content = [Content()]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Invalid or expired refresh token",
                content = [Content()]
            )
        ]
    )
    @SecurityRequirements
    fun refreshToken(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<SignInResponse> {
        val response = userService.refreshToken(request)
        return ResponseEntity.ok(response)
    }
}

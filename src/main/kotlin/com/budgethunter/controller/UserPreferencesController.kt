package com.budgethunter.controller

import com.budgethunter.dto.UpdateUserPreferencesRequest
import com.budgethunter.dto.UserPreferencesResponse
import com.budgethunter.service.UserPreferencesService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users/me/preferences")
@Tag(name = "User Preferences", description = "Settings-screen preferences saved on the account")
class UserPreferencesController(
    private val userPreferencesService: UserPreferencesService
) {

    @GetMapping
    @Operation(
        summary = "Get the authenticated user's preferences",
        description = "Fields are null when the account has never saved them."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "The saved preferences",
                content = [Content(schema = Schema(implementation = UserPreferencesResponse::class))]
            ),
            ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = [Content()])
        ]
    )
    fun get(authentication: Authentication): ResponseEntity<UserPreferencesResponse> =
        ResponseEntity.ok(userPreferencesService.get(authentication.principal as String))

    @PutMapping
    @Operation(
        summary = "Save the authenticated user's preferences",
        description = "Replaces every preference. The default budget must be one the user can access."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "The preferences as saved",
                content = [Content(schema = Schema(implementation = UserPreferencesResponse::class))]
            ),
            ApiResponse(responseCode = "400", description = "Invalid request", content = [Content()]),
            ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = [Content()]),
            ApiResponse(responseCode = "403", description = "No access to the default budget", content = [Content()])
        ]
    )
    fun update(
        @Valid @RequestBody request: UpdateUserPreferencesRequest,
        authentication: Authentication
    ): ResponseEntity<UserPreferencesResponse> =
        ResponseEntity.ok(userPreferencesService.update(authentication.principal as String, request))
}

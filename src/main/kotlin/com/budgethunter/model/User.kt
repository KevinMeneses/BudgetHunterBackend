package com.budgethunter.model

import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

@Entity
@Table(name = "users")
data class User(
    @field:Id
    @field:Email
    @field:NotBlank
    @field:Column(nullable = false, unique = true)
    val email: String,

    @field:NotBlank
    @field:Column(nullable = false)
    val name: String,

    @field:NotBlank
    @field:Column(nullable = false)
    val password: String,

    @field:OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val userBudgets: MutableList<UserBudget> = mutableListOf()
)

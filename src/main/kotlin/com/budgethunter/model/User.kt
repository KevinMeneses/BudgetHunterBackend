package com.budgethunter.model

import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

@Entity
@Table(name = "users")
data class User(
    @Id
    @Email
    @NotBlank
    @Column(nullable = false, unique = true)
    val email: String,

    @NotBlank
    @Column(nullable = false)
    val name: String,

    @NotBlank
    @Column(nullable = false)
    val password: String,

    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val userBudgets: MutableList<UserBudget> = mutableListOf()
)

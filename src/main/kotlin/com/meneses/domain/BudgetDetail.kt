package com.meneses.domain

import kotlinx.serialization.Serializable

@Serializable
data class BudgetDetail(
    val budget: Budget,
    val entries: List<BudgetEntry>
)
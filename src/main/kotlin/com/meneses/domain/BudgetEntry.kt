package com.meneses.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.LocalDate

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BudgetEntry(
    val id: Int,
    val budgetId: Int,
    val amount: String,
    val description: String,
    @EncodeDefault
    val type: Type = Type.OUTCOME,
    val date: String = LocalDate.now().toString()
) {
    @Serializable
    enum class Type {
        OUTCOME,
        INCOME
    }
}

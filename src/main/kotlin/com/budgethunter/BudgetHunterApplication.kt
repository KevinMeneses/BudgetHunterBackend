package com.budgethunter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BudgetHunterApplication

fun main(args: Array<String>) {
    runApplication<BudgetHunterApplication>(*args)
}

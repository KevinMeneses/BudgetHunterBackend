package com.budgethunter.repository

import com.budgethunter.model.BudgetEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface BudgetEntryRepository : JpaRepository<BudgetEntry, Long>

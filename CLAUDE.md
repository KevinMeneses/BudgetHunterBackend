# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BudgetHunter Backend is a collaborative budget tracking application API built with Kotlin, Spring Boot 3.3.4, and JPA. The project enables users to create budgets, manage budget entries, and collaborate with other users on shared budgets.

## Tech Stack

- **Language**: Kotlin 2.0.20 (JVM target 17)
- **Framework**: Spring Boot 3.3.4 with Spring Security, Spring Data JPA, Spring Validation
- **Database**: H2 (development), PostgreSQL (production)
- **Testing**: JUnit 5, MockK 1.13.12, Spring Security Test
- **Build Tool**: Gradle with Kotlin DSL

## Build Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "FullyQualifiedClassName"

# Run a single test method
./gradlew test --tests "FullyQualifiedClassName.testMethodName"

# Clean build
./gradlew clean build
```

## Database Architecture

The application uses a relational database with the following core entities:

### Core Models (in `src/main/kotlin/com/budgethunter/model/`)

1. **User** - Primary entity for user authentication and identification
   - PK: `email` (String)
   - Fields: `name`, `password`
   - Relationships: One-to-many with `UserBudget`

2. **Budget** - Represents a budget that can be shared among multiple users
   - PK: `id` (Long, auto-generated)
   - Fields: `name`, `amount` (BigDecimal)
   - Relationships: One-to-many with `UserBudget` and `BudgetEntry`

3. **UserBudget** - Junction table for many-to-many relationship between User and Budget
   - Composite PK: `UserBudgetId` (budgetId, userEmail)
   - Enables collaborative budgets where multiple users can access the same budget

4. **BudgetEntry** - Individual income/expense entries within a budget
   - PK: `id` (Long, auto-generated)
   - FK: `budget`, `createdBy` (optional), `updatedBy` (optional)
   - Fields: `amount`, `description`, `category`, `type` (INCOME/EXPENSE enum)
   - Audit fields: `creationDate`, `modificationDate`

### Key Architectural Patterns

- **Composite Keys**: `UserBudget` uses an `@Embeddable` composite key (`UserBudgetId`) to represent the many-to-many relationship
- **Lazy Loading**: All `@ManyToOne` relationships use `FetchType.LAZY` to prevent N+1 queries
- **Audit Trail**: `BudgetEntry` tracks who created/updated entries with optional `createdBy`/`updatedBy` references
- **Enums**: `EntryType` enum distinguishes between INCOME and EXPENSE entries

## Development Database

The application is configured to use H2 in-memory database for development:
- **Console**: Access at `http://localhost:8080/h2-console` when app is running
- **JDBC URL**: `jdbc:h2:mem:budgethunter`
- **Username**: `sa`
- **Password**: (empty)
- **Schema**: Auto-generated via `spring.jpa.hibernate.ddl-auto=update`

## Code Organization

```
src/main/kotlin/com/budgethunter/
├── BudgetHunterApplication.kt    # Main Spring Boot application
└── model/                         # JPA entities
    ├── User.kt
    ├── Budget.kt
    ├── UserBudget.kt
    └── BudgetEntry.kt
```

## Future Development Considerations

Based on the project structure, expect to implement:
- Controllers/REST endpoints for CRUD operations
- Repository layer (Spring Data JPA repositories)
- Service layer for business logic
- DTOs for request/response objects
- Security configuration for authentication/authorization
- Migration from H2 to PostgreSQL for production

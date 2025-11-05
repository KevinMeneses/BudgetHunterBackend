# BudgetHunter Backend - Annotations Guide

Complete reference of all annotations used in this project with explanations and examples.

---

## Spring Boot & Spring Framework Annotations

### Application Configuration

#### `@SpringBootApplication`
**Package:** `org.springframework.boot.autoconfigure`
**Location:** `BudgetHunterApplication.kt`

Combines three annotations:
- `@Configuration` - Marks class as a source of bean definitions
- `@EnableAutoConfiguration` - Enables Spring Boot auto-configuration
- `@ComponentScan` - Scans for components in current package and sub-packages

**Example:**
```kotlin
@SpringBootApplication
class BudgetHunterApplication

fun main(args: Array<String>) {
    runApplication<BudgetHunterApplication>(*args)
}
```

---

### Spring MVC & REST Annotations

#### `@RestController`
**Package:** `org.springframework.web.bind.annotation`
**Location:** `BudgetController.kt`, `UserController.kt`

Combines `@Controller` and `@ResponseBody`. Marks a class as a REST controller where all methods return data (JSON/XML) rather than views.

**Example:**
```kotlin
@RestController
@RequestMapping("/api/budgets")
class BudgetController(...)
```

---

#### `@RequestMapping`
**Package:** `org.springframework.web.bind.annotation`
**Location:** `BudgetController.kt`, `UserController.kt`

Maps HTTP requests to handler methods or classes. Can specify path, HTTP method, headers, etc.

**Class-level example:**
```kotlin
@RestController
@RequestMapping("/api/budgets")
class BudgetController
```

**Method-level variants:**
- `@GetMapping` - Shortcut for `@RequestMapping(method = RequestMethod.GET)`
- `@PostMapping` - Shortcut for `@RequestMapping(method = RequestMethod.POST)`
- `@PutMapping` - Shortcut for `@RequestMapping(method = RequestMethod.PUT)`
- `@DeleteMapping` - Shortcut for `@RequestMapping(method = RequestMethod.DELETE)`

---

#### `@GetMapping`
**Package:** `org.springframework.web.bind.annotation`
**Location:** `BudgetController.kt`

Shorthand for `@RequestMapping(method = RequestMethod.GET)`. Maps HTTP GET requests.

**Example:**
```kotlin
@GetMapping("/get_budgets")
fun getBudgets(authentication: Authentication): ResponseEntity<List<BudgetResponse>>
```

**With produces attribute:**
```kotlin
@GetMapping("/new_entry", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun newEntry(@RequestParam budgetId: Long, authentication: Authentication): SseEmitter
```

---

#### `@PostMapping`
**Package:** `org.springframework.web.bind.annotation`
**Location:** `BudgetController.kt`, `UserController.kt`

Shorthand for `@RequestMapping(method = RequestMethod.POST)`. Maps HTTP POST requests.

**Example:**
```kotlin
@PostMapping("/create_budget")
fun createBudget(@Valid @RequestBody request: CreateBudgetRequest, authentication: Authentication)
```

---

#### `@PutMapping`
**Package:** `org.springframework.web.bind.annotation`
**Location:** `BudgetController.kt`

Shorthand for `@RequestMapping(method = RequestMethod.PUT)`. Maps HTTP PUT requests.

**Example:**
```kotlin
@PutMapping("/put_entry")
fun putEntry(@Valid @RequestBody request: PutEntryRequest, authentication: Authentication)
```

---

#### `@RequestBody`
**Package:** `org.springframework.web.bind.annotation`
**Location:** `BudgetController.kt`, `UserController.kt`

Binds the HTTP request body to a method parameter. Spring automatically deserializes JSON to the object type.

**Example:**
```kotlin
@PostMapping("/sign_up")
fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<UserResponse>
```

---

#### `@RequestParam`
**Package:** `org.springframework.web.bind.annotation`
**Location:** `BudgetController.kt`

Binds HTTP query parameters to method parameters.

**Example:**
```kotlin
@GetMapping("/get_collaborators")
fun getCollaborators(@RequestParam budgetId: Long, authentication: Authentication)
```

For URL: `/api/budgets/get_collaborators?budgetId=1`, the value `1` is bound to `budgetId`.

---

### Spring Dependency Injection

#### `@Component`
**Package:** `org.springframework.stereotype`
**Location:** `JwtUtil.kt`, `JwtAuthenticationFilter.kt`

Generic Spring-managed component. Spring will create and manage instances of this class.

**Example:**
```kotlin
@Component
class JwtUtil {
    fun generateToken(email: String): String { ... }
}
```

---

#### `@Service`
**Package:** `org.springframework.stereotype`
**Location:** `BudgetService.kt`, `UserService.kt`, `SseService.kt`

Specialized `@Component` for service layer classes. Contains business logic.

**Example:**
```kotlin
@Service
class BudgetService(
    private val budgetRepository: BudgetRepository,
    private val userBudgetRepository: UserBudgetRepository
) { ... }
```

---

#### `@Repository`
**Package:** `org.springframework.stereotype`
**Location:** `UserRepository.kt`, `BudgetRepository.kt`, `UserBudgetRepository.kt`, `BudgetEntryRepository.kt`

Specialized `@Component` for data access layer. Provides exception translation (converts database exceptions to Spring's DataAccessException).

**Example:**
```kotlin
@Repository
interface UserRepository : JpaRepository<User, String>
```

---

#### `@Configuration`
**Package:** `org.springframework.context.annotation`
**Location:** `SecurityConfig.kt`

Marks a class as a source of bean definitions. Methods annotated with `@Bean` will be managed by Spring container.

**Example:**
```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
```

---

### Spring Security

#### `@EnableWebSecurity`
**Package:** `org.springframework.security.config.annotation.web.configuration`
**Location:** `SecurityConfig.kt`

Enables Spring Security's web security support and provides Spring MVC integration.

**Example:**
```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig { ... }
```

---

### Spring Transaction Management

#### `@Transactional`
**Package:** `org.springframework.transaction.annotation`
**Location:** `BudgetService.kt`, `UserService.kt`

Marks a method or class as transactional. Spring manages transaction boundaries (begin, commit, rollback).

**Attributes:**
- `readOnly = true` - Optimization for read-only operations (no data modification)

**Examples:**
```kotlin
// Read-write transaction (default)
@Transactional
fun createBudget(request: CreateBudgetRequest, userEmail: String): BudgetResponse

// Read-only transaction (optimized)
@Transactional(readOnly = true)
fun getBudgetsByUserEmail(userEmail: String): List<BudgetResponse>
```

---

## JPA (Jakarta Persistence) Annotations

### Entity & Table Mapping

#### `@Entity`
**Package:** `jakarta.persistence`
**Location:** All model classes (`User.kt`, `Budget.kt`, `BudgetEntry.kt`, `UserBudget.kt`)

Marks a class as a JPA entity (database table).

**Example:**
```kotlin
@Entity
@Table(name = "users")
data class User(...)
```

---

#### `@Table`
**Package:** `jakarta.persistence`
**Location:** All model classes

Specifies the database table name for the entity.

**Attributes:**
- `name` - Table name in database

**Example:**
```kotlin
@Entity
@Table(name = "budget_entries")
data class BudgetEntry(...)
```

---

### Primary Key Annotations

#### `@Id`
**Package:** `jakarta.persistence`
**Location:** All model classes

Marks a field as the primary key.

**Example:**
```kotlin
@Entity
data class User(
    @field:Id
    @field:Email
    val email: String,
    ...
)
```

---

#### `@GeneratedValue`
**Package:** `jakarta.persistence`
**Location:** `Budget.kt`, `BudgetEntry.kt`

Specifies how the primary key should be generated.

**Attributes:**
- `strategy = GenerationType.IDENTITY` - Database auto-increment
- `strategy = GenerationType.AUTO` - JPA provider chooses strategy
- `strategy = GenerationType.SEQUENCE` - Database sequence
- `strategy = GenerationType.TABLE` - Database table

**Example:**
```kotlin
@field:Id
@field:GeneratedValue(strategy = GenerationType.IDENTITY)
val id: Long? = null
```

---

#### `@EmbeddedId`
**Package:** `jakarta.persistence`
**Location:** `UserBudget.kt`

Marks a composite primary key that is defined as an `@Embeddable` class.

**Example:**
```kotlin
@Entity
data class UserBudget(
    @EmbeddedId
    val id: UserBudgetId,
    ...
)
```

---

#### `@Embeddable`
**Package:** `jakarta.persistence`
**Location:** `UserBudget.kt`

Marks a class as an embeddable component (composite primary key).

**Example:**
```kotlin
@Embeddable
data class UserBudgetId(
    @Column(name = "budget_id")
    val budgetId: Long,

    @Column(name = "user_email")
    val userEmail: String
) : Serializable
```

---

### Column Mapping

#### `@Column`
**Package:** `jakarta.persistence`
**Location:** All model classes

Maps a field to a database column with additional constraints.

**Attributes:**
- `name` - Column name in database
- `nullable` - Whether column accepts NULL values
- `unique` - Whether column values must be unique
- `updatable` - Whether column can be updated
- `precision` - Total number of digits (for decimal types)
- `scale` - Number of digits after decimal point

**Examples:**
```kotlin
@field:Column(nullable = false, unique = true)
val email: String

@field:Column(nullable = false, precision = 19, scale = 2)
val amount: BigDecimal

@field:Column(nullable = false, updatable = false)
val creationDate: LocalDateTime
```

---

### Relationship Annotations

#### `@OneToMany`
**Package:** `jakarta.persistence`
**Location:** `User.kt`, `Budget.kt`

Defines a one-to-many relationship. One entity is related to many instances of another entity.

**Attributes:**
- `mappedBy` - Field in the child entity that owns the relationship
- `cascade` - Operations to cascade (persist, remove, etc.)
- `orphanRemoval` - Delete child when removed from parent collection

**Example:**
```kotlin
@field:OneToMany(mappedBy = "budget", cascade = [CascadeType.ALL], orphanRemoval = true)
val budgetEntries: MutableList<BudgetEntry> = mutableListOf()
```

---

#### `@ManyToOne`
**Package:** `jakarta.persistence`
**Location:** `BudgetEntry.kt`, `UserBudget.kt`

Defines a many-to-one relationship. Many instances of this entity relate to one instance of another entity.

**Attributes:**
- `fetch` - Lazy or eager loading strategy

**Example:**
```kotlin
@field:ManyToOne(fetch = FetchType.LAZY)
@field:JoinColumn(name = "budget_id", nullable = false)
val budget: Budget
```

---

#### `@JoinColumn`
**Package:** `jakarta.persistence`
**Location:** `BudgetEntry.kt`, `UserBudget.kt`

Specifies the foreign key column for a relationship.

**Attributes:**
- `name` - Foreign key column name
- `nullable` - Whether foreign key can be NULL
- `referencedColumnName` - Column in referenced table (default is primary key)

**Example:**
```kotlin
@field:ManyToOne(fetch = FetchType.LAZY)
@field:JoinColumn(name = "created_by")
val createdBy: User? = null
```

---

### Enumeration Mapping

#### `@Enumerated`
**Package:** `jakarta.persistence`
**Location:** `BudgetEntry.kt`

Specifies how an enum should be persisted.

**Values:**
- `EnumType.STRING` - Store enum name as string (recommended)
- `EnumType.ORDINAL` - Store enum position as integer (not recommended)

**Example:**
```kotlin
@field:Enumerated(EnumType.STRING)
val type: EntryType  // Stores "INCOME" or "OUTCOME" in database

enum class EntryType {
    INCOME,
    OUTCOME
}
```

---

### Query Annotations

#### `@Query`
**Package:** `org.springframework.data.jpa.repository`
**Location:** `UserBudgetRepository.kt`

Defines a custom JPQL or native SQL query for repository methods.

**Example:**
```kotlin
@Query("""
    SELECT b FROM Budget b
    JOIN UserBudget ub ON b.id = ub.id.budgetId
    WHERE ub.id.userEmail = :userEmail
""")
fun findBudgetsByUserEmail(@Param("userEmail") userEmail: String): List<Budget>
```

---

#### `@Param`
**Package:** `org.springframework.data.repository.query`
**Location:** `UserBudgetRepository.kt`

Binds a method parameter to a named parameter in a `@Query`.

**Example:**
```kotlin
fun findBudgetsByUserEmail(@Param("userEmail") userEmail: String): List<Budget>
```

---

## Jakarta Validation Annotations

### Field Validation

#### `@Valid`
**Package:** `jakarta.validation`
**Location:** Controller methods

Triggers validation of the annotated object. Used with `@RequestBody`.

**Example:**
```kotlin
@PostMapping("/sign_up")
fun signUp(@Valid @RequestBody request: SignUpRequest)
```

---

#### `@NotNull`
**Package:** `jakarta.validation.constraints`
**Location:** Model classes, DTOs

Field cannot be null.

**Example:**
```kotlin
@field:NotNull
val amount: BigDecimal
```

---

#### `@NotBlank`
**Package:** `jakarta.validation.constraints`
**Location:** Model classes, DTOs

String field cannot be null, empty, or contain only whitespace.

**Example:**
```kotlin
@field:NotBlank(message = "Name is required")
val name: String
```

---

#### `@Email`
**Package:** `jakarta.validation.constraints`
**Location:** `User.kt`, `SignUpRequest.kt`, DTOs

Validates that a string is a valid email address.

**Example:**
```kotlin
@field:Email(message = "Email must be valid")
@field:NotBlank(message = "Email is required")
val email: String
```

---

#### `@Size`
**Package:** `jakarta.validation.constraints`
**Location:** `SignUpRequest.kt`, DTOs

Validates the size of a string, collection, map, or array.

**Attributes:**
- `min` - Minimum length/size
- `max` - Maximum length/size
- `message` - Custom error message

**Example:**
```kotlin
@field:Size(min = 6, message = "Password must be at least 6 characters")
val password: String
```

---

#### `@PositiveOrZero`
**Package:** `jakarta.validation.constraints`
**Location:** `Budget.kt`, `CreateBudgetRequest.kt`

Validates that a number is zero or positive.

**Example:**
```kotlin
@field:PositiveOrZero(message = "Amount must be zero or positive")
val amount: BigDecimal
```

---

#### `@Positive`
**Package:** `jakarta.validation.constraints`
**Location:** `AddCollaboratorRequest.kt`

Validates that a number is strictly positive (greater than zero).

**Example:**
```kotlin
@field:Positive(message = "Budget ID must be positive")
val budgetId: Long
```

---

## Kotlin-Specific Annotations

### `@field:` Target

**Purpose:** In Kotlin, annotations on properties can target different elements (field, getter, setter, etc.). `@field:` ensures the annotation is applied to the backing field.

**Why it matters:** JPA and validation annotations need to be on the field, not the property itself.

**Example:**
```kotlin
// Correct - annotation on field
@field:NotBlank
val name: String

// Incorrect - annotation on property (won't work)
@NotBlank
val name: String
```

---

## Annotation Summary by File Type

### Model Classes (`User.kt`, `Budget.kt`, `BudgetEntry.kt`, `UserBudget.kt`)
- **JPA:** `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column`, `@OneToMany`, `@ManyToOne`, `@JoinColumn`, `@Enumerated`, `@Embeddable`, `@EmbeddedId`
- **Validation:** `@NotNull`, `@NotBlank`, `@Email`, `@PositiveOrZero`
- **Kotlin:** `@field:`

### DTOs (`SignUpRequest.kt`, etc.)
- **Validation:** `@NotNull`, `@NotBlank`, `@Email`, `@Size`, `@PositiveOrZero`, `@Positive`
- **Kotlin:** `@field:`

### Controllers (`BudgetController.kt`, `UserController.kt`)
- **Spring MVC:** `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@RequestBody`, `@RequestParam`
- **Validation:** `@Valid`

### Services (`BudgetService.kt`, `UserService.kt`, `SseService.kt`)
- **Spring:** `@Service`
- **Transaction:** `@Transactional`

### Repositories (`UserRepository.kt`, etc.)
- **Spring:** `@Repository`
- **JPA:** `@Query`, `@Param`

### Configuration (`SecurityConfig.kt`)
- **Spring:** `@Configuration`, `@EnableWebSecurity`

### Utilities (`JwtUtil.kt`, `JwtAuthenticationFilter.kt`)
- **Spring:** `@Component`

---

## Common Annotation Patterns

### REST Endpoint Pattern
```kotlin
@RestController
@RequestMapping("/api/resource")
class ResourceController(private val service: ResourceService) {

    @PostMapping("/create")
    fun create(@Valid @RequestBody request: CreateRequest): ResponseEntity<Response> {
        val result = service.create(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }
}
```

### Entity Pattern
```kotlin
@Entity
@Table(name = "resources")
data class Resource(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @field:NotBlank
    @field:Column(nullable = false)
    val name: String,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "parent_id")
    val parent: Parent
)
```

### Service Pattern
```kotlin
@Service
class ResourceService(
    private val repository: ResourceRepository
) {
    @Transactional
    fun create(request: CreateRequest): Response { ... }

    @Transactional(readOnly = true)
    fun findAll(): List<Response> { ... }
}
```

### Repository Pattern
```kotlin
@Repository
interface ResourceRepository : JpaRepository<Resource, Long> {
    @Query("SELECT r FROM Resource r WHERE r.name = :name")
    fun findByName(@Param("name") name: String): Resource?
}
```

---

## Best Practices

1. **Always use `@field:` in Kotlin** for JPA and validation annotations
2. **Use `@Transactional(readOnly = true)`** for read-only operations
3. **Use `FetchType.LAZY`** for `@ManyToOne` and `@OneToOne` to avoid N+1 queries
4. **Use `@Valid`** on `@RequestBody` to trigger validation
5. **Prefer `@NotBlank` over `@NotNull`** for strings
6. **Use `EnumType.STRING`** instead of `EnumType.ORDINAL` for enums
7. **Add meaningful error messages** to validation annotations
8. **Use specific HTTP status codes** (201 CREATED, 200 OK, etc.)

---

**Last Updated:** 2025-10-03

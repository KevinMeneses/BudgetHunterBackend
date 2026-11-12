package com.budgethunter.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * Runs V6__add_budget_owner.sql itself, and the pre-deploy report next to it, on the schema it migrates.
 *
 * The suite has no PostgreSQL (H2, Flyway off), so this uses H2 in PostgreSQL mode on a hand-built copy of
 * the tables V6 touches, as they were before it. The migration is kept to SQL both databases accept for
 * exactly this reason; it was also run on PostgreSQL 16 by hand when written.
 */
class BudgetOwnerMigrationTest {

    private val migration = Path.of("src/main/resources/db/migration/V6__add_budget_owner.sql")
    private val preview = Path.of("database/reports/budget_owner_preview.sql")

    private lateinit var connection: Connection

    @BeforeEach
    fun createSchemaBeforeV6() {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:v6-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        )
        execute(
            """
            CREATE TABLE users (email VARCHAR(255) PRIMARY KEY, name VARCHAR(255) NOT NULL);
            CREATE TABLE budgets (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, amount DECIMAL(19, 2) NOT NULL, date DATE);
            CREATE TABLE user_budgets (
                budget_id BIGINT NOT NULL REFERENCES budgets (id) ON DELETE CASCADE,
                user_email VARCHAR(255) NOT NULL REFERENCES users (email) ON DELETE CASCADE,
                PRIMARY KEY (budget_id, user_email)
            );
            CREATE TABLE budget_entries (
                id BIGINT PRIMARY KEY,
                budget_id BIGINT NOT NULL REFERENCES budgets (id) ON DELETE CASCADE,
                created_by VARCHAR(255) REFERENCES users (email) ON DELETE SET NULL,
                creation_date TIMESTAMP NOT NULL
            );
            INSERT INTO users VALUES ('a@x.com', 'A'), ('b@x.com', 'B'), ('c@x.com', 'C'), ('gone@x.com', 'Gone');
            """
        )
    }

    @AfterEach
    fun close() = connection.close()

    @Test
    fun `a budget with a single member goes to that member`() {
        budget(1, members = listOf("b@x.com"))
        entry(1, budgetId = 1, by = "a@x.com", at = "2025-01-01") // by someone who is no longer a member

        migrate()

        assertEquals(mapOf(1L to "b@x.com"), owners())
    }

    @Test
    fun `a shared budget goes to the member who wrote its oldest entry`() {
        budget(1, members = listOf("a@x.com", "b@x.com", "c@x.com"))
        entry(1, budgetId = 1, by = "a@x.com", at = "2026-03-01")
        entry(2, budgetId = 1, by = "c@x.com", at = "2026-01-01")
        entry(3, budgetId = 1, by = null, at = "2025-01-01") // creator's account deleted: not a member
        entry(4, budgetId = 1, by = "gone@x.com", at = "2025-06-01") // a former member
        // Same instant: the lower id came first.
        budget(2, members = listOf("a@x.com", "b@x.com"))
        entry(6, budgetId = 2, by = "a@x.com", at = "2026-01-01")
        entry(5, budgetId = 2, by = "b@x.com", at = "2026-01-01")

        migrate()

        assertEquals(mapOf(1L to "c@x.com", 2L to "b@x.com"), owners())
    }

    @Test
    fun `a shared budget with no entries by its members goes to the lowest email`() {
        budget(1, members = listOf("c@x.com", "b@x.com"))
        budget(2, members = listOf("c@x.com", "b@x.com"))
        entry(1, budgetId = 2, by = "gone@x.com", at = "2025-01-01")

        migrate()

        assertEquals(mapOf(1L to "b@x.com", 2L to "b@x.com"), owners())
    }

    @Test
    fun `a budget with no members is deleted with its entries`() {
        budget(1, members = emptyList())
        entry(1, budgetId = 1, by = "a@x.com", at = "2025-01-01")
        budget(2, members = listOf("a@x.com"))

        migrate()

        assertEquals(mapOf(2L to "a@x.com"), owners())
        assertEquals(0L, count("SELECT COUNT(*) FROM budget_entries"))
    }

    @Test
    fun `the preview report counts the budgets each rule will take`() {
        budget(1, members = emptyList())
        budget(2, members = listOf("a@x.com"))
        budget(3, members = listOf("a@x.com", "b@x.com"))
        entry(1, budgetId = 3, by = "b@x.com", at = "2025-01-01")
        budget(4, members = listOf("a@x.com", "b@x.com"))
        budget(5, members = listOf("b@x.com", "c@x.com"))

        val report = query(statements(preview).single())

        assertEquals(
            mapOf(
                "0. no members: deleted" to 1L,
                "1. single member" to 1L,
                "2. creator of the oldest entry" to 1L,
                "3. lowest email" to 2L
            ),
            report
        )
    }

    @Test
    fun `the migration can run again, as it does on a database built from schema sql`() {
        budget(1, members = listOf("a@x.com", "b@x.com"))
        migrate()
        execute("INSERT INTO budgets (id, name, amount, owner_email) VALUES (2, 'new', 1, 'c@x.com')")
        execute("INSERT INTO user_budgets VALUES (2, 'c@x.com')")

        migrate()

        assertEquals(mapOf(1L to "a@x.com", 2L to "c@x.com"), owners())
    }

    @Test
    fun `afterwards every budget needs an owner, and an owner's account cannot be deleted`() {
        budget(1, members = listOf("a@x.com", "b@x.com"))
        migrate()

        assertThrows(SQLException::class.java) {
            execute("INSERT INTO budgets (id, name, amount) VALUES (2, 'no owner', 1)")
        }
        assertThrows(SQLException::class.java) { execute("DELETE FROM users WHERE email = 'a@x.com'") }
        // A member who owns nothing still can.
        execute("DELETE FROM users WHERE email = 'b@x.com'")
        assertEquals(1L, count("SELECT COUNT(*) FROM user_budgets"))
    }

    private fun migrate() = statements(migration).forEach { execute(it) }

    /** The file's statements: comments dropped, split on the semicolons that end them. */
    private fun statements(file: Path): List<String> =
        Files.readAllLines(file)
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun budget(id: Long, members: List<String>) {
        execute("INSERT INTO budgets (id, name, amount) VALUES ($id, 'budget $id', 100)")
        members.forEach { execute("INSERT INTO user_budgets VALUES ($id, '$it')") }
    }

    private fun entry(id: Long, budgetId: Long, by: String?, at: String) {
        val creator = by?.let { "'$it'" } ?: "NULL"
        execute("INSERT INTO budget_entries VALUES ($id, $budgetId, $creator, TIMESTAMP '$at 00:00:00')")
    }

    private fun owners(): Map<Long, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, owner_email FROM budgets ORDER BY id").use { rows ->
                buildMap { while (rows.next()) put(rows.getLong(1), rows.getString(2)) }
            }
        }

    private fun query(sql: String): Map<String, Long> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
            }
        }

    private fun count(sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    private fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }
}

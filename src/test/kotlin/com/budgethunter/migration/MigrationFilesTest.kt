package com.budgethunter.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Guards the migration files themselves, which no other test reaches: the suite runs on H2 with
 * Flyway disabled, so a file that Flyway would reject is otherwise only discovered by a
 * production deploy refusing to start.
 */
class MigrationFilesTest {

    private val migrationsDir: Path = Path.of("src/main/resources/db/migration")

    private fun migrationFiles(): List<Path> =
        Files.list(migrationsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .sorted()
                .toList()
        }

    @Test
    fun `migrations live where Flyway looks for them`() {
        assertTrue(Files.isDirectory(migrationsDir), "$migrationsDir is missing")
        assertTrue(migrationFiles().isNotEmpty(), "no migrations found in $migrationsDir")
    }

    @Test
    fun `every migration is named the way Flyway requires`() {
        val naming = Regex("""^V(\d+)__[A-Za-z0-9_]+\.sql$""")
        migrationFiles().forEach { file ->
            assertTrue(
                naming.matches(file.name),
                "${file.name} does not match V<version>__<description>.sql, so Flyway ignores it"
            )
        }
    }

    @Test
    fun `no two migrations claim the same version`() {
        val versions = migrationFiles().map { file ->
            Regex("""^V(\d+)__""").find(file.name)!!.groupValues[1].toInt()
        }
        assertEquals(versions.size, versions.distinct().size, "duplicate version among $versions")
    }

    @Test
    fun `no migration takes transaction control away from Flyway`() {
        // Flyway wraps each migration in its own transaction. A stray COMMIT inside one ends that
        // transaction early, so a later failure in the same file leaves the schema half-applied.
        val transactionControl = Regex("""(?im)^\s*(begin|commit|rollback)\s*;""")
        migrationFiles().forEach { file ->
            val offending = transactionControl.find(Files.readString(file))
            assertTrue(
                offending == null,
                "${file.name} contains '${offending?.value?.trim()}'; let Flyway own the transaction"
            )
        }
    }
}

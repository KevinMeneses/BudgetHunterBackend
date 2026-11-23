package com.budgethunter.privacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * The version the server records ([PRIVACY_POLICY_VERSION]) must be the policy the page shows: otherwise users
 * would be accepting, as far as the records go, a text they were never shown. A change to the policy's date
 * without the constant, or the other way round, fails here.
 */
class PrivacyPolicyPageTest {

    private val page: String = Files.readString(Path.of("src/main/resources/static/privacidad.html"))

    private val months = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )

    @Test
    fun `the version is a date`() {
        // The app and the database get it as 'YYYY-MM-DD'; parse fails on anything else.
        LocalDate.parse(PRIVACY_POLICY_VERSION)
    }

    @Test
    fun `the page's last update is the version`() {
        val match = Regex("""Última actualización: (\d{1,2}) de (\p{L}+) de (\d{4})""").find(page)
        assertNotNull(match, "the page has no 'Última actualización: <día> de <mes> de <año>'")
        val (day, month, year) = match!!.destructured
        val monthNumber = months.indexOf(month.lowercase()) + 1
        assertTrue(monthNumber > 0, "unknown month '$month'")

        assertEquals(LocalDate.parse(PRIVACY_POLICY_VERSION), LocalDate.of(year.toInt(), monthNumber, day.toInt()))
    }

    @Test
    fun `the page shows the version`() {
        val shown = Regex("""<span id="policy-version">([^<]*)</span>""").find(page)
        assertNotNull(shown, "the page does not show its version (<span id=\"policy-version\">)")
        assertEquals(PRIVACY_POLICY_VERSION, shown!!.groupValues[1])
    }
}

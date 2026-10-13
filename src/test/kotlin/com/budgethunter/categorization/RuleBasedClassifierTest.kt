package com.budgethunter.categorization

import com.budgethunter.model.EntryCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RuleBasedClassifierTest {

    private val classifier = RuleBasedClassifier()

    private fun one(description: String): String? = classifier.classify(listOf(description)).single()

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
        "Almuerzo en el Corral, FOOD",
        "McDonald's #4421, FOOD",
        "COMPRA EXITO 8841, GROCERIES",
        "Supermercado Jumbo, GROCERIES",
        "Peluquería Carlos, SELF_CARE",
        "Gym membership, SELF_CARE",
        "UBER *TRIP 2026, TRANSPORTATION",
        "Gasolina Terpel, TRANSPORTATION",
        "Peaje autopista, TRANSPORTATION",
        "Compra en Homecenter, HOUSEHOLD_ITEMS",
        "IKEA sofa, HOUSEHOLD_ITEMS",
        "Pago internet Claro, SERVICES",
        "Arriendo octubre, SERVICES",
        "Matrícula universidad, EDUCATION",
        "Curso Udemy, EDUCATION",
        "Farmacia Cruz Verde, HEALTH",
        "Consulta dentista, HEALTH",
        "Netflix, LEISURE",
        "Cine Colombia boletas, LEISURE",
        "Impuesto predial, TAXES",
        "DIAN declaración de renta, TAXES"
    )
    fun `recognises well-known merchants and words`(description: String, expected: String) {
        assertEquals(expected, one(description))
    }

    @Test
    fun `matches whole words only`() {
        assertEquals("TRANSPORTATION", one("taxi al aeropuerto"))
        assertEquals("TAXES", one("tax return"))
        assertNull(one("barbie dreamhouse"))
        assertNull(one("taxidermia"))
    }

    @Test
    fun `the longest keyword wins`() {
        assertEquals("FOOD", one("Uber Eats pedido"))
        assertEquals("TRANSPORTATION", one("rent a car Bogota"))
        assertEquals("FOOD", one("Didi Food"))
        assertEquals("TRANSPORTATION", one("Didi"))
    }

    @Test
    fun `ignores case, accents, punctuation and digits`() {
        assertEquals("HEALTH", one("  FARMACIA!!! 000123  "))
        assertEquals(one("clínica"), one("CLINICA"))
    }

    @Test
    fun `has no opinion on ambiguous or unknown descriptions`() {
        assertNull(one("Rappi"))
        assertNull(one("metro"))
        assertNull(one("regalo para mamá"))
    }

    @Test
    fun `has no opinion on blank descriptions`() {
        assertEquals(listOf<String?>(null, null, null), classifier.classify(listOf("", "   ", "12345")))
    }

    @Test
    fun `answers one element per description in the same order`() {
        val result = classifier.classify(listOf("netflix", "???", "farmacia", "exito"))

        assertEquals(listOf("LEISURE", null, "HEALTH", "GROCERIES"), result)
        assertEquals(emptyList<String?>(), classifier.classify(emptyList()))
    }

    @Test
    fun `only ever answers a known category and never OTHER`() {
        val answers = listOf("netflix", "farmacia", "exito", "uber", "dian", "arriendo", "udemy", "gym", "ikea", "pizza")
            .let(classifier::classify)

        assertTrue(answers.all { it != null && EntryCategory.isKnown(it) && it != EntryCategory.OTHER })
    }
}

package com.budgethunter.categorization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DescriptionNormalizerTest {

    @Test
    fun `lower-cases and strips accents`() {
        assertEquals("cafe jose", DescriptionNormalizer.normalize("CAFÉ José"))
        assertEquals("ano nuevo", DescriptionNormalizer.normalize("Año Nuevo"))
    }

    @Test
    fun `drops tokens with digits so reference numbers do not split the key`() {
        assertEquals("rappi", DescriptionNormalizer.normalize("RAPPI 8841"))
        assertEquals(DescriptionNormalizer.normalize("rappi 9921"), DescriptionNormalizer.normalize("RAPPI 8841"))
        assertEquals("uber trip", DescriptionNormalizer.normalize("UBER *TRIP 2026-10-05 x1234"))
    }

    @Test
    fun `treats punctuation as separators and collapses whitespace`() {
        assertEquals("pago tarjeta exito", DescriptionNormalizer.normalize("  pago--tarjeta  /  EXITO  "))
    }

    @Test
    fun `keeps an apostrophe inside the word`() {
        assertEquals("mcdonalds", DescriptionNormalizer.normalize("McDonald's"))
        assertEquals("mcdonalds", DescriptionNormalizer.normalize("McDonald’s"))
    }

    @Test
    fun `returns empty when nothing identifying is left`() {
        assertEquals("", DescriptionNormalizer.normalize(""))
        assertEquals("", DescriptionNormalizer.normalize("   "))
        assertEquals("", DescriptionNormalizer.normalize("12345 *** 99"))
    }

    @Test
    fun `bounds the length of the result`() {
        val result = DescriptionNormalizer.normalize("palabra ".repeat(100))

        assertEquals(true, result.length <= 120)
        assertEquals(result, result.trim())
    }
}

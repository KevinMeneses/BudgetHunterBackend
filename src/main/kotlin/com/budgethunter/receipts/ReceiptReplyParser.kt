package com.budgethunter.receipts

import com.budgethunter.model.EntryCategory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Turns a Gemini `generateContent` reply to a receipt into a [ReceiptReading], trusting nothing in it:
 * the category must be one of [EntryCategory.ALL] (else OTHER), the amount positive, the date a real
 * `YYYY-MM-DD` (else [today][parse]). Never throws.
 */
internal class ReceiptReplyParser(private val objectMapper: ObjectMapper) {

    fun parse(body: String, today: LocalDate): ReceiptReading {
        val root = readJson(body)
        val candidate = root?.path("candidates")?.path(0)
        val text = candidate?.path("content")?.path("parts")?.firstOrNull { it.path("text").isTextual }
            ?.path("text")?.asText()
        return when {
            root == null -> INVALID
            root.path("promptFeedback").hasNonNull("blockReason") -> BLOCKED
            text != null -> parseExtraction(text, today)
            candidate?.path("finishReason")?.asText() in BLOCKED_FINISH_REASONS -> BLOCKED
            else -> INVALID
        }
    }

    private fun parseExtraction(text: String, today: LocalDate): ReceiptReading {
        // Structured output should already be plain JSON; strip code fences defensively.
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val empty = cleaned.isBlank() || cleaned == "{}"
        val extraction = if (empty) null else readJson(cleaned)
        return when {
            empty -> ReceiptReading.NotAReceipt
            extraction == null || !extraction.isObject -> INVALID
            !extraction.path("isInvoice").asBoolean(false) -> ReceiptReading.NotAReceipt
            else -> toReading(extraction, today)
        }
    }

    private fun toReading(extraction: JsonNode, today: LocalDate): ReceiptReading {
        val amount = extraction.path("amount").toPositiveAmount()
        val description = extraction.path("description").asText("").trim().take(MAX_DESCRIPTION_LENGTH)
            .ifEmpty { null }
        val category = extraction.path("category").asText("").trim().uppercase()
            .takeIf { it in EntryCategory.ALL } ?: EntryCategory.OTHER
        return if (amount == null && description == null) {
            INVALID
        } else {
            ReceiptReading.Read(amount, description, category, extraction.path("date").toDate() ?: today)
        }
    }

    private fun JsonNode.toPositiveAmount(): BigDecimal? {
        val value = when {
            isNumber -> decimalValue()
            isTextual -> asText().trim().toBigDecimalOrNull()
            else -> null
        }
        return value?.takeIf { it.signum() > 0 }?.plain()
    }

    /**
     * Without trailing decimal zeros, but never in scientific notation: `stripTrailingZeros` alone turns 55730 into
     * 5.573E+4 (a negative scale), which Jackson writes as is and the app showed verbatim in the amount field.
     */
    private fun BigDecimal.plain(): BigDecimal {
        val stripped = stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    private fun JsonNode.toDate(): LocalDate? = asText("").trim().takeIf { ISO_DATE.matches(it) }?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun readJson(text: String): JsonNode? = try {
        objectMapper.readTree(text)
    } catch (_: JsonProcessingException) {
        null
    }

    private companion object {
        const val MAX_DESCRIPTION_LENGTH = 255
        val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        val BLOCKED_FINISH_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")
        val INVALID = ReceiptReading.Failed(ReceiptFailure.INVALID_RESPONSE)
        val BLOCKED = ReceiptReading.Failed(ReceiptFailure.BLOCKED)
    }
}

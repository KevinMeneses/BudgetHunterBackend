package com.budgethunter.categorization

import com.budgethunter.model.EntryCategory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Turns a Gemini `generateContent` reply into answers for a batch, trusting nothing in it.
 *
 * The model's JSON array travels as a string inside `candidates[0].content.parts[0].text`. Anything
 * that is not a well-formed `{id, category}` for an item of the batch, with a category from
 * [EntryCategory.ALL], is dropped, so a malformed, blocked or partly wrong reply costs only the
 * items it got wrong. Never throws.
 */
internal class GeminiReplyParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(GeminiReplyParser::class.java)

    /** The valid answers keyed by the text they are for. When an item is answered twice, the first wins. */
    fun parse(body: String, batch: List<String>): Map<String, String> = try {
        readItems(body)?.mapNotNull { answerOf(it, batch) }?.distinctBy { it.first }?.toMap().orEmpty()
    } catch (_: JsonProcessingException) {
        log.warn("Gemini reply was not valid JSON; not classifying this batch")
        emptyMap()
    }

    private fun readItems(body: String): JsonNode? {
        val text = objectMapper.readTree(body)
            .path("candidates").path(0).path("content").path("parts").path(0).path("text")
        return if (text.isTextual) objectMapper.readTree(text.asText()).takeIf { it.isArray } else null
    }

    private fun answerOf(item: JsonNode, batch: List<String>): Pair<String, String>? {
        val id = item.path("id").takeIf { it.isIntegralNumber }?.asInt()
        val category = item.path("category").takeIf { it.isTextual }?.asText()
        val valid = id != null && id in batch.indices && category != null && EntryCategory.isKnown(category)
        return if (valid) batch[id!!] to category!! else null
    }
}

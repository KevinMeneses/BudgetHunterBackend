package com.budgethunter.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Generic paginated response wrapper
 * @param T The type of content in the page
 */
@Schema(description = "Paginated response containing a list of items and pagination metadata")
data class PageResponse<T>(
    @Schema(description = "List of items in the current page")
    val content: List<T>,

    @Schema(description = "Current page number (0-indexed)", example = "0")
    val page: Int,

    @Schema(description = "Number of items per page", example = "20")
    val size: Int,

    @Schema(description = "Total number of items across all pages", example = "150")
    val totalElements: Long,

    @Schema(description = "Total number of pages", example = "8")
    val totalPages: Int,

    @Schema(description = "Whether this is the first page", example = "true")
    val isFirst: Boolean,

    @Schema(description = "Whether this is the last page", example = "false")
    val isLast: Boolean
)

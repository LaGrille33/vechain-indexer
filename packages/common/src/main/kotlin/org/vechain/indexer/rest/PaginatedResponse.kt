package org.vechain.indexer.rest

import com.fasterxml.jackson.annotation.JsonView
import org.springframework.data.domain.Slice
import org.vechain.indexer.thor.model.Views

/** An API query can return at most this number of elements per page */
const val PAGE_SIZE_LIMIT = 150

/** An API query can count at most this number of elements per response */
const val COUNT_LIMIT = 500L

/** API response wrapper object */
@JsonView(Views.Public::class)
data class PaginatedResponse<T>(val data: List<T>, val pagination: PaginationDetail) where T : Any

/** Wrapper that holds pagination data inside a response */
@JsonView(Views.Public::class)
data class PaginationDetail(val hasNext: Boolean, val cursor: String? = null)

/**
 * Builds a paginated API response based on a single results page.
 *
 * hasNext flag provides an indication whether it's useful to keep querying for more pages.
 */
fun <T : Any> paginatedResponse(slice: Slice<T>): PaginatedResponse<T> {
    return PaginatedResponse(
        data = slice.content,
        pagination = PaginationDetail(hasNext = slice.hasNext(), cursor = null),
    )
}

/**
 * Builds a paginated API response with cursor support.
 *
 * @param data The list of items to return
 * @param hasNext Whether there are more items
 * @param cursor Optional cursor for fetching next page
 */
fun <T : Any> paginatedResponse(
    data: List<T>,
    hasNext: Boolean,
    cursor: String? = null,
): PaginatedResponse<T> {
    return PaginatedResponse(
        data = data,
        pagination = PaginationDetail(hasNext = hasNext, cursor = cursor),
    )
}

/** API response wrapper that includes a total count */
@JsonView(Views.Public::class)
data class PaginatedCountResponse<T>(
    val data: List<T>,
    val pagination: PaginationCountDetail,
) where T : Any

/** Pagination metadata with full page info */
@JsonView(Views.Public::class)
data class PaginationCountDetail(
    val page: Int,
    val size: Int,
    val total: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

/**
 * Builds a paginated API response with full page info.
 *
 * @param data The list of items to return
 * @param page Current page number
 * @param size Page size
 * @param total Total number of items across all pages
 * @param hasNext Whether there are more items
 */
fun <T : Any> paginatedCountResponse(
    data: List<T>,
    page: Int,
    size: Int,
    total: Long,
    hasNext: Boolean,
): PaginatedCountResponse<T> {
    val totalPages = if (size > 0) ((total + size - 1) / size).toInt() else 0
    return PaginatedCountResponse(
        data = data,
        pagination =
            PaginationCountDetail(
                page = page,
                size = size,
                total = total,
                totalPages = totalPages,
                hasNext = hasNext,
            ),
    )
}

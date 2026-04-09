package com.contenido.domain.search.service

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import com.contenido.domain.search.document.ChannelDocument
import com.contenido.domain.search.document.ContentDocument
import com.contenido.domain.search.dto.SearchChannelResponse
import com.contenido.domain.search.dto.SearchContentResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.query.NativeQuery
import org.springframework.stereotype.Service

@Service
class SearchService(
    private val elasticsearchOperations: ElasticsearchOperations,
) {

    fun searchChannels(
        keyword: String,
        category: String?,
        page: Int,
        size: Int,
        sortBy: String?,
    ): Page<SearchChannelResponse> {
        val query = NativeQuery.builder()
            .withQuery(buildChannelQuery(keyword, category))
            .apply {
                when (sortBy) {
                    "subscriberCount" -> withSort(Sort.by(Sort.Direction.DESC, "subscriberCount"))
                    else -> { /* 기본 relevance 정렬 (_score DESC) */ }
                }
            }
            .withPageable(PageRequest.of(page, size))
            .build()

        val hits = elasticsearchOperations.search(query, ChannelDocument::class.java)
        val content = hits.searchHits.map { it.content.toResponse() }
        return PageImpl(content, PageRequest.of(page, size), hits.totalHits)
    }

    fun searchContents(
        keyword: String,
        category: String?,
        sourceType: String?,
        page: Int,
        size: Int,
        sortBy: String?,
    ): Page<SearchContentResponse> {
        val query = NativeQuery.builder()
            .withQuery(buildContentQuery(keyword, category, sourceType))
            .apply {
                when (sortBy) {
                    "viewCount" -> withSort(Sort.by(Sort.Direction.DESC, "viewCount"))
                    "likeCount" -> withSort(Sort.by(Sort.Direction.DESC, "likeCount"))
                    else -> { /* 기본 relevance 정렬 */ }
                }
            }
            .withPageable(PageRequest.of(page, size))
            .build()

        val hits = elasticsearchOperations.search(query, ContentDocument::class.java)
        val content = hits.searchHits.map { it.content.toResponse() }
        return PageImpl(content, PageRequest.of(page, size), hits.totalHits)
    }

    // ── query builders ────────────────────────────────────────────────────────

    private fun buildChannelQuery(keyword: String, category: String?): Query =
        Query.of { q ->
            q.bool { b ->
                // name 필드에 2배 가중치
                b.must { m ->
                    m.multiMatch { mm ->
                        mm.query(keyword).fields(listOf("name^2", "description"))
                    }
                }
                category?.let { cat ->
                    b.filter { f -> f.term { t -> t.field("category").value(cat) } }
                }
                b
            }
        }

    private fun buildContentQuery(keyword: String, category: String?, sourceType: String?): Query =
        Query.of { q ->
            q.bool { b ->
                // title 필드에 3배 가중치
                b.must { m ->
                    m.multiMatch { mm ->
                        mm.query(keyword).fields(listOf("title^3", "description"))
                    }
                }
                category?.let { cat ->
                    b.filter { f -> f.term { t -> t.field("category").value(cat) } }
                }
                sourceType?.let { st ->
                    b.filter { f -> f.term { t -> t.field("sourceType").value(st.uppercase()) } }
                }
                b
            }
        }

    // ── mappers ───────────────────────────────────────────────────────────────

    private fun ChannelDocument.toResponse() = SearchChannelResponse(
        id = id,
        name = name,
        category = category,
        categoryDisplayName = categoryDisplayName,
        ownerNickname = ownerNickname,
        subscriberCount = subscriberCount,
        thumbnailUrl = thumbnailUrl,
    )

    private fun ContentDocument.toResponse() = SearchContentResponse(
        id = id,
        sourceType = sourceType,
        channelId = channelId,
        channelName = channelName,
        category = category,
        title = title,
        description = description,
        authorNickname = authorNickname,
        viewCount = viewCount,
        likeCount = likeCount,
        createdAt = createdAt,
    )
}

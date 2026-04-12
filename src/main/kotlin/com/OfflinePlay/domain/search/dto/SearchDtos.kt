package com.contenido.domain.search.dto

data class SearchChannelResponse(
    val id: String,
    val name: String,
    val category: String,
    val categoryDisplayName: String,
    val ownerNickname: String,
    val subscriberCount: Long,
    val thumbnailUrl: String?,
)

data class SearchContentResponse(
    val id: String,
    val sourceType: String,
    val channelId: Long,
    val channelName: String,
    val category: String,
    val title: String,
    val description: String,
    val authorNickname: String,
    val viewCount: Long,
    val likeCount: Long,
    val createdAt: String,
)

data class SearchRequest(
    val keyword: String,
    val category: String? = null,
    val sourceType: String? = null,
    val sortBy: String? = null,   // "relevance" | "subscriberCount" | "viewCount" | "likeCount"
    val page: Int = 0,
    val size: Int = 10,
)

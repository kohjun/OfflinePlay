package com.contenido.domain.search.document

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType

@Document(indexName = "channels")
data class ChannelDocument(

    @Id
    val id: String,

    /** 채널명 — 전문 검색 (name^2 boost) */
    @Field(type = FieldType.Text, analyzer = "standard")
    val name: String,

    /** 채널 설명 — 전문 검색 */
    @Field(type = FieldType.Text, analyzer = "standard")
    val description: String,

    /** 카테고리 enum name — 정확 매치 필터용 */
    @Field(type = FieldType.Keyword)
    val category: String,

    @Field(type = FieldType.Keyword)
    val categoryDisplayName: String,

    @Field(type = FieldType.Keyword)
    val ownerNickname: String,

    /** 구독자 수 — 정렬용 */
    @Field(type = FieldType.Long)
    val subscriberCount: Long,

    /** 썸네일 URL — 검색/정렬 불필요 */
    @Field(type = FieldType.Keyword, index = false)
    val thumbnailUrl: String? = null,

    /** ISO-8601 문자열 */
    @Field(type = FieldType.Keyword)
    val createdAt: String,
)

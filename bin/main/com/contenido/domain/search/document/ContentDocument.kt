package com.contenido.domain.search.document

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType

/** Event + Post를 통합 인덱싱하는 도큐먼트. ID = "{sourceType}_{entityId}" */
@Document(indexName = "contents")
data class ContentDocument(

    @Id
    val id: String,

    /** "EVENT" 또는 "POST" */
    @Field(type = FieldType.Keyword)
    val sourceType: String,

    @Field(type = FieldType.Long)
    val channelId: Long,

    @Field(type = FieldType.Keyword)
    val channelName: String,

    /** 채널 카테고리 enum name */
    @Field(type = FieldType.Keyword)
    val category: String,

    /** 제목 — 전문 검색 (title^3 boost) */
    @Field(type = FieldType.Text, analyzer = "standard")
    val title: String,

    /** 본문/설명 — 전문 검색 */
    @Field(type = FieldType.Text, analyzer = "standard")
    val description: String,

    @Field(type = FieldType.Keyword)
    val authorNickname: String,

    @Field(type = FieldType.Long)
    val viewCount: Long,

    @Field(type = FieldType.Long)
    val likeCount: Long,

    @Field(type = FieldType.Keyword)
    val createdAt: String,
)

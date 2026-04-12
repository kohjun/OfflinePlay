package com.contenido.global.event

enum class ContentSyncAction { SYNC, DELETE }

data class ContentSyncEvent(
    val action: ContentSyncAction,
    val sourceType: String,
    val entityId: Long,
)

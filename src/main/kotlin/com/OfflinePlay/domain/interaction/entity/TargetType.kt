package com.contenido.domain.interaction.entity

enum class TargetType(val pathSegment: String) {
    EVENT("events"),
    POST("posts"),
    COMMENT("comments"),
}

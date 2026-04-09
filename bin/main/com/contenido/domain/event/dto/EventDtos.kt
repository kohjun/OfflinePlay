package com.contenido.domain.event.dto

import com.contenido.domain.event.entity.EventStatus
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.LocalDateTime

data class CreateEventRequest(
    @field:NotBlank(message = "이벤트 제목은 필수입니다.")
    val title: String,

    @field:NotBlank(message = "이벤트 설명은 필수입니다.")
    val description: String,

    val thumbnailUrl: String? = null,

    @field:Future(message = "시작 시간은 현재 이후여야 합니다.")
    val startAt: LocalDateTime,

    @field:Future(message = "종료 시간은 현재 이후여야 합니다.")
    val endAt: LocalDateTime,

    @field:Positive(message = "최대 참여 인원은 양수여야 합니다.")
    val maxParticipants: Int? = null,
)

data class EventResponse(
    val id: Long,
    val channelId: Long,
    val channelName: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val maxParticipants: Int?,
    val currentParticipants: Int,
    val status: EventStatus,
    val createdAt: LocalDateTime,
)

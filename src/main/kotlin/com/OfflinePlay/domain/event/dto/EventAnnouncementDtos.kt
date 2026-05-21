package com.contenido.domain.event.dto

import com.contenido.domain.event.entity.EventAnnouncement
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * PR141 — 이벤트 공지 생성/응답 DTO.
 */
data class CreateEventAnnouncementRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 200)
    val title: String,

    @field:NotBlank
    @field:Size(min = 1, max = 5000)
    val content: String,
)

data class EventAnnouncementResponse(
    val id: Long,
    val eventId: Long,
    val authorId: Long,
    val authorNickname: String,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(announcement: EventAnnouncement) = EventAnnouncementResponse(
            id = announcement.id,
            eventId = announcement.event.id,
            authorId = announcement.author.id,
            authorNickname = announcement.author.nickname,
            title = announcement.title,
            content = announcement.content,
            createdAt = announcement.createdAt,
            updatedAt = announcement.updatedAt,
        )
    }
}

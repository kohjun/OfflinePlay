package com.contenido.domain.event.dto

import com.contenido.domain.event.entity.EventAnnouncement
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * PR141 — 이벤트 공지 생성/응답 DTO.
 *
 * PR152 — `imageUrls` 추가. 최대 3장, 각 URL 은 max 500자 (S3Service 가 발급한 fully qualified URL).
 */
data class CreateEventAnnouncementRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 200)
    val title: String,

    @field:NotBlank
    @field:Size(min = 1, max = 5000)
    val content: String,

    @field:Size(max = 3)
    val imageUrls: List<@Size(min = 1, max = 500) String> = emptyList(),
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
    /** PR151 — pin 표시. UI 상단 고정용. */
    val pinned: Boolean = false,
    /** PR151 — viewer 가 본 공지인지. 미인증 viewer 또는 read 가 없으면 false. */
    val read: Boolean = false,
    /** PR152 — 첨부 이미지 url. displayOrder asc 정렬. 빈 리스트면 표시 안 함. */
    val imageUrls: List<String> = emptyList(),
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
            pinned = announcement.isPinned,
            read = false,
            imageUrls = emptyList(),
        )

        fun from(
            announcement: EventAnnouncement,
            read: Boolean,
            imageUrls: List<String> = emptyList(),
        ) = from(announcement).copy(read = read, imageUrls = imageUrls)
    }
}

/** PR151 — 이벤트의 unread 공지 카운트 응답. EventRoomSection 의 공지 탭 dot 에 사용. */
data class UnreadAnnouncementCountResponse(
    val eventId: Long,
    val unreadCount: Long,
)

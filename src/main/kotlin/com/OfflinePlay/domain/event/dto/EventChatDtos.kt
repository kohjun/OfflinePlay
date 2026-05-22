package com.contenido.domain.event.dto

import com.contenido.domain.event.entity.EventChatMessage
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * PR160 — 이벤트룸 채팅 메시지 DTO.
 *
 *  - SSE 'event-chat' event 의 payload 와 동일 shape 라 backend / frontend / SSE 모두가 같은 type 을 본다.
 *  - `senderId` / `senderNickname` 만 노출 (avatar / role 등은 후속).
 */
data class EventChatMessageResponse(
    val id: Long,
    val eventId: Long,
    val senderId: Long,
    val senderNickname: String,
    val content: String,
    val isAnnouncement: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(message: EventChatMessage) = EventChatMessageResponse(
            id = message.id,
            eventId = message.event.id,
            senderId = message.sender.id,
            senderNickname = message.sender.nickname,
            content = message.content,
            isAnnouncement = message.isAnnouncement,
            createdAt = message.createdAt,
        )
    }
}

/**
 * 메시지 송신 request. isAnnouncement 는 owner/STAFF/ADMIN 만 허용 — service 가 권한 가드.
 * 일반 참가자가 true 로 보내면 silently false 로 강등 (또는 service 가 명시 거부 — 본 PR 은 거부).
 */
data class SendEventChatMessageRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 500)
    val content: String,

    val isAnnouncement: Boolean = false,
)

/**
 * 히스토리 페이지 응답. cursor 페이징.
 *  - `items` 는 시간 오름차순 (오래된 메시지가 위 → 새 메시지가 아래) — 카톡식.
 *  - `nextBefore*` 는 더 과거 메시지를 받을 때 사용할 cursor. null 이면 더 이상 없음.
 */
data class EventChatHistoryResponse(
    val items: List<EventChatMessageResponse>,
    val nextBeforeCreatedAt: LocalDateTime?,
    val nextBeforeId: Long?,
)

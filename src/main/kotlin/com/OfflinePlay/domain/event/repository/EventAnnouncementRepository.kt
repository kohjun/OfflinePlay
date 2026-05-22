package com.contenido.domain.event.repository

import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventAnnouncement
import org.springframework.data.jpa.repository.JpaRepository

/**
 * PR141 — 이벤트 공지 저장소.
 *  - 목록은 created_at desc 정렬 — UI 가 최신 공지를 위에 보여준다.
 *  - count 는 EventDetailPage 의 "공지 N개" 뱃지 등 보조 신호.
 */
interface EventAnnouncementRepository : JpaRepository<EventAnnouncement, Long> {

    fun findByEventOrderByCreatedAtDesc(event: Event): List<EventAnnouncement>

    fun countByEvent(event: Event): Long

    /** PR151 — pin 토글 시 같은 이벤트의 다른 pinned 해제용. */
    fun findByEventAndPinnedAtIsNotNull(event: Event): List<EventAnnouncement>
}

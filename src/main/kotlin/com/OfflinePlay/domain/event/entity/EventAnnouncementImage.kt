package com.contenido.domain.event.entity

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR152 — 이벤트 공지 inline 이미지.
 *
 *  - announcement_id FK + url + display_order + created_at.
 *  - service / DTO 가 최대 3장 enforce — 본 entity 자체는 갯수 제한 없음.
 *  - URL 은 기존 S3Service.upload 결과 그대로 저장.
 */
@Entity
@Table(
    name = "event_announcement_images",
    indexes = [
        Index(name = "idx_event_announcement_images_announcement", columnList = "announcement_id, display_order"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class EventAnnouncementImage(

    @Column(name = "announcement_id", nullable = false)
    val announcementId: Long,

    @Column(nullable = false, length = 500)
    val url: String,

    @Column(name = "display_order", nullable = false)
    val displayOrder: Int = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set
}

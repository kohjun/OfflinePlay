package com.contenido.domain.event.entity

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

/**
 * PR151 — 공지 read receipt.
 *
 *  - composite PK (announcement_id, user_id) — service 가 upsert 멱등 처리.
 *  - read_at 만 갱신 — 사용자가 같은 공지를 여러 번 펼치면 가장 최근 시각.
 *  - notification.isRead 와 별도: 알림 push 가 꺼져 있어도 공지 read 추적 가능.
 */
@Entity
@Table(
    name = "event_announcement_reads",
    indexes = [
        Index(name = "idx_event_announcement_reads_user", columnList = "user_id"),
    ],
)
@IdClass(EventAnnouncementReadId::class)
class EventAnnouncementRead(

    @Id
    @Column(name = "announcement_id", nullable = false)
    val announcementId: Long,

    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "read_at", nullable = false)
    var readAt: LocalDateTime = LocalDateTime.now(),
)

data class EventAnnouncementReadId(
    val announcementId: Long = 0,
    val userId: Long = 0,
) : Serializable

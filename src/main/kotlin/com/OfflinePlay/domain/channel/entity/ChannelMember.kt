package com.contenido.domain.channel.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 채널 팀원. 채널은 "기획자(planner)의 팀/브랜드"이므로 향후 OWNER 외에 STAFF
 * 권한을 가진 팀원이 합류할 수 있도록 설계한다.
 *
 * 현 단계(MVP)에서는 채널 생성 시 owner = OWNER 한 명만 들어가도 충분하지만,
 * 엔티티/Repository는 미리 두어 이후 작업이 매끄럽도록 한다.
 */
@Entity
@Table(
    name = "channel_members",
    uniqueConstraints = [UniqueConstraint(columnNames = ["channel_id", "user_id"])],
)
@EntityListeners(AuditingEntityListener::class)
class ChannelMember(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    val channel: Channel,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: ChannelMemberRole = ChannelMemberRole.STAFF,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "joined_at", nullable = false, updatable = false)
    lateinit var joinedAt: LocalDateTime
        protected set
}

package com.contenido.domain.user.entity

import com.contenido.domain.region.entity.Region
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR144 — 사용자 확장 프로필 (1:1).
 *
 * 정책:
 *  - User 는 인증 hot path (email/role 만 SELECT 되는 경우 다수). avatar/bio/region 같은 wide
 *    nullable column 을 합치면 row 캐시 효율 저하 + DTO 직렬화 오버헤드. 별도 1:1 entity 로 분리.
 *  - lazy create — 사용자가 처음 PATCH 할 때 service 가 row 를 만든다. 신규 가입자는 row 없음 =
 *    visibility=PUBLIC + 모든 필드 null 인 응답.
 *  - visibility 는 String enum 대신 [ProfileVisibility] Kotlin enum + JPA STRING 변환.
 */
@Entity
@Table(
    name = "user_profiles",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_user_profiles_user", columnNames = ["user_id"]),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class UserProfile(

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    @Column(name = "avatar_url", length = 500)
    var avatarUrl: String? = null,

    @Column(length = 500)
    var bio: String? = null,

    @Column(name = "region_sido", length = 50)
    var regionSido: String? = null,

    @Column(name = "region_sigungu", length = 50)
    var regionSigungu: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var visibility: ProfileVisibility = ProfileVisibility.PUBLIC,

    /**
     * PR147 — 정규화된 region. PR144 의 free-form regionSido/regionSigungu 와 병존하며 점진 backfill.
     * null 허용 — 기존 사용자는 region_code 가 채워질 때까지 free-form 값으로 표시된다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_code")
    var region: Region? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set
}

/**
 * PR144 — 공개 프로필 가시성 정책.
 *
 *  - PUBLIC  : 비로그인 포함 모두에게 모든 공개 필드 노출.
 *  - MEMBERS : 로그인 사용자에게만 (본 cycle 에선 PUBLIC 과 동일하게 다루고, 후속 PR 에서
 *              실제 분기 — 컬럼만 미리 확보).
 *  - PRIVATE : 공개 응답에서 nickname / role / joinedAt 만 노출, bio / avatar / region / interests
 *              는 모두 숨김.
 */
enum class ProfileVisibility {
    PUBLIC, MEMBERS, PRIVATE,
}

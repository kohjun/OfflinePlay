package com.contenido.domain.impact.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * Planner Impact Index (skeleton).
 *
 * 기획자의 활동·이벤트 성과를 5개 차원으로 환산한 점수 모델. 실제 계산 로직은
 * 후속 작업에서 결정한다(데이터 소스 확정 후 weighted score 정의).
 *
 * 현재는 엔티티 + 5 dimensions + total score + tier 만 두고, 실제 갱신 로직은
 * TODO 로 남긴다.
 */
@Entity
@Table(name = "impact_indexes")
@EntityListeners(AuditingEntityListener::class)
class ImpactIndex(

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planner_id", nullable = false, unique = true)
    val planner: User,

    @Column(nullable = false)
    var totalScore: Double = 0.0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var tier: ImpactTier = ImpactTier.BRONZE,

    /** Dimension 1: 기획력 (이벤트 기획 빈도/품질). */
    @Column(name = "dim_planning", nullable = false)
    var dimensionPlanning: Double = 0.0,

    /** Dimension 2: 실행력 (이벤트 진행 성공률/완수율). */
    @Column(name = "dim_execution", nullable = false)
    var dimensionExecution: Double = 0.0,

    /** Dimension 3: 참여 만족도 (참가자 평가). */
    @Column(name = "dim_satisfaction", nullable = false)
    var dimensionSatisfaction: Double = 0.0,

    /** Dimension 4: 팬덤 (구독자 규모/유지율). */
    @Column(name = "dim_fandom", nullable = false)
    var dimensionFandom: Double = 0.0,

    /** Dimension 5: 화제성 (검색·공유·언급량). */
    @Column(name = "dim_buzz", nullable = false)
    var dimensionBuzz: Double = 0.0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set

    // TODO(impact-calc): 실제 산식이 정해지면 아래에 weightedSum/normalize 메서드를 구현하고
    //  ImpactIndexService.recalculate(plannerId)에서 호출한다.
}

enum class ImpactTier {
    BRONZE, SILVER, GOLD, PLATINUM, DIAMOND,
}

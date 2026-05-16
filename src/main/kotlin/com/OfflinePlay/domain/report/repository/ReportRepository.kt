package com.contenido.domain.report.repository

import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<Report, Long> {

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Report>

    fun findByStatus(status: ReportStatus, pageable: Pageable): Page<Report>

    /** Admin 목록 필터 — targetType 별 페이지. */
    fun findByTargetTypeOrderByCreatedAtDesc(
        targetType: ReportTargetType,
        pageable: Pageable,
    ): Page<Report>

    /**
     * 같은 reporter 가 같은 (targetType, targetId) 를 또 신고했는지 — service 가드.
     * PR48 추가: 중복 신고는 ReportAlreadyExistsException 으로 거절.
     *
     * 노트: 본 PR 은 schema 변경(UNIQUE 인덱스) 없이 service 레이어 가드만 둔다.
     * 동시성으로 인한 race 는 매우 드물고 발생해도 운영자가 dedup 가능.
     * Flyway 도입 (다음 PR) 이후 UNIQUE 인덱스를 baseline 에 추가하는 것이 정공.
     */
    fun existsByReporterAndTargetTypeAndTargetId(
        reporter: User,
        targetType: ReportTargetType,
        targetId: Long,
    ): Boolean

    /**
     * PR51 — 자동 조치 임계치 검사용. 한 (targetType, targetId) 의 PENDING 신고 누적 카운트.
     * RESOLVED/DISMISSED 처리된 신고는 제외해 운영자가 한 번 dismiss 한 케이스가 다시 자동 hide
     * 되지 않도록 한다.
     */
    fun countByTargetTypeAndTargetIdAndStatus(
        targetType: ReportTargetType,
        targetId: Long,
        status: ReportStatus,
    ): Long
}

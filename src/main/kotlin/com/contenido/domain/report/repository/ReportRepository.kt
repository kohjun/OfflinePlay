package com.contenido.domain.report.repository

import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<Report, Long> {

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Report>

    fun findByStatus(status: ReportStatus, pageable: Pageable): Page<Report>
}

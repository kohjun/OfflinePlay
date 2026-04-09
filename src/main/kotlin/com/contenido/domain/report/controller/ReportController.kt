package com.contenido.domain.report.controller

import com.contenido.domain.report.dto.CreateReportRequest
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.report.service.ReportService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val reportService: ReportService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createReport(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CreateReportRequest,
    ): ApiResponse<ReportResponse> {
        return ApiResponse.success(reportService.createReport(userId, request))
    }
}

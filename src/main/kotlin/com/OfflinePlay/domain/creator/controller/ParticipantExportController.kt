package com.contenido.domain.creator.controller

import com.contenido.domain.creator.service.ParticipantExportService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * PR154 — 신청자 CSV export.
 *
 * 응답 헤더:
 *  - Content-Type: text/csv; charset=UTF-8
 *  - Content-Disposition: attachment; filename="event-{id}-participants-{timestamp}.csv"
 *
 * BOM (﻿) prefix 로 Excel 한글 깨짐 방지.
 */
@RestController
@RequestMapping("/api/v1/creator/events/{eventId}/participants")
class ParticipantExportController(
    private val participantExportService: ParticipantExportService,
) {

    @GetMapping("/export")
    fun export(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ResponseEntity<ByteArray> {
        val csvBody = participantExportService.exportCsv(userId, eventId)
        // UTF-8 BOM 으로 Excel 호환.
        val bytes = "﻿$csvBody".toByteArray(StandardCharsets.UTF_8)
        val ts = java.time.LocalDateTime.now()
            .toString()
            .replace(":", "-")
            .substringBefore('.')
        val rawFilename = "event-${eventId}-participants-${ts}.csv"
        val encoded = URLEncoder.encode(rawFilename, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"$rawFilename\"; filename*=UTF-8''$encoded",
            )
            .body(bytes)
    }
}

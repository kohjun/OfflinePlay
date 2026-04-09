package com.contenido.domain.creator.controller

import com.contenido.domain.creator.service.CreatorApplicationService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

data class ApplyRequest(
    val reason: String,
    val portfolioUrl: String?
)

@RestController
@RequestMapping("/api/v1")
class CreatorApplicationController(
    private val applicationService: CreatorApplicationService
) {

    @PostMapping("/creator/apply")
    @PreAuthorize("hasRole('PARTICIPANT')")
    fun applyForCreator(
        // TODO: 실제 SecurityContext에서 userId 추출 애노테이션으로 변경 필요 (@AuthenticationPrincipal 등)
        @RequestAttribute("userId") userId: Long, 
        @RequestBody request: ApplyRequest
    ): ResponseEntity<Void> {
        applicationService.apply(userId, request.reason, request.portfolioUrl)
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/admin/creator/applications/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    fun approveApplication(
        @RequestAttribute("userId") adminId: Long,
        @PathVariable id: Long
    ): ResponseEntity<Void> {
        applicationService.approve(adminId, id)
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/admin/creator/applications/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    fun rejectApplication(
        @RequestAttribute("userId") adminId: Long,
        @PathVariable id: Long
    ): ResponseEntity<Void> {
        applicationService.reject(adminId, id, null)
        return ResponseEntity.ok().build()
    }
}
package com.contenido.domain.admin.dto

import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.user.entity.UserRole
import java.time.LocalDateTime

data class AdminUserResponse(
    val id: Long,
    val email: String,
    val nickname: String,
    val role: UserRole,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime,
)

data class AdminChannelResponse(
    val id: Long,
    val name: String,
    val ownerNickname: String,
    val category: ChannelCategory,
    val categoryDisplayName: String,
    val subscriberCount: Long,
    val isActive: Boolean,
    val createdAt: LocalDateTime,
)

data class AdminReportListResponse(
    val content: List<ReportResponse>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int,
)

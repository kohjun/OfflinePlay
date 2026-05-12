package com.contenido.domain.channel.dto

import com.contenido.domain.channel.entity.ChannelMemberRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

data class AddStaffRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "올바른 이메일 형식이 아닙니다.")
    val email: String,
)

/**
 * 채널 운영팀 카드. OWNER 1명 + STAFF N명을 한 응답에서 다룬다.
 */
data class ChannelMemberResponse(
    val id: Long,
    val userId: Long,
    val nickname: String,
    val email: String,
    val role: ChannelMemberRole,
    val joinedAt: LocalDateTime,
)

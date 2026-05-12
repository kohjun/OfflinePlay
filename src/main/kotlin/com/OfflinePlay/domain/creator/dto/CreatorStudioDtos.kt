package com.contenido.domain.creator.dto

import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.event.entity.EventStatus
import java.time.LocalDateTime

/**
 * Creator Studio 화면 한 번의 호출로 받는 묶음 응답.
 *
 *  - channel : 기획자의 채널. 아직 만들지 않은 경우 null.
 *  - events  : 채널에 등록된 이벤트 (시작 시각 내림차순), 각 항목에 신청 상태별 카운트 포함.
 *  - summary : 화면 상단의 요약 4-tile.
 */
data class CreatorStudioResponse(
    val channel: CreatorStudioChannel?,
    val events: List<CreatorStudioEvent>,
    val summary: CreatorStudioSummary,
)

data class CreatorStudioChannel(
    val id: Long,
    val name: String,
    val description: String,
    val category: ChannelCategory,
    val categoryDisplayName: String,
    val thumbnailUrl: String?,
    val subscriberCount: Long,
    val ownerNickname: String,
)

data class CreatorStudioEvent(
    val id: Long,
    val title: String,
    val status: EventStatus,
    val startAt: LocalDateTime,
    val location: String,
    val mainImageUrl: String,
    val currentParticipants: Int,
    val maxParticipants: Int,
    val pendingCount: Long,
    val approvedCount: Long,
    val rejectedCount: Long,
    val canceledCount: Long,
)

data class CreatorStudioSummary(
    val totalEvents: Int,
    val pendingApplicants: Long,
    val approvedParticipants: Long,
    val subscriberCount: Long,
)

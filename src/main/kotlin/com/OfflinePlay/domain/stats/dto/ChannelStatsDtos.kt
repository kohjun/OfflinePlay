package com.contenido.domain.stats.dto

data class ChannelStatsResponse(
    val channelId: Long,
    val channelName: String,
    val totalSubscribers: Long,
    val newSubscribersThisMonth: Long,
    val totalViewCount: Long,
    val totalLikeCount: Long,
    val eventStats: List<EventStatItem>,
)

data class EventStatItem(
    val eventId: Long,
    val eventTitle: String,
    val currentParticipants: Int,
    val maxParticipants: Int?,
    val participationRate: Double?,
)

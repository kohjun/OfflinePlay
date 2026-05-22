package com.contenido.domain.channel.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelSubscription
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface ChannelSubscriptionRepository : JpaRepository<ChannelSubscription, Long> {

    fun existsBySubscriberAndChannel(subscriber: User, channel: Channel): Boolean

    fun findByChannel(channel: Channel): List<ChannelSubscription>

    fun findBySubscriber(subscriber: User, pageable: Pageable): Page<ChannelSubscription>

    /** PR148 — 추천에서 사용자가 구독 중인 채널 id 묶음을 빠르게 가져오기 위한 derived query. */
    fun findBySubscriberId(subscriberId: Long): List<ChannelSubscription>

    fun deleteBySubscriberAndChannel(subscriber: User, channel: Channel)

    fun countByChannel(channel: Channel): Long

    fun countByChannelAndCreatedAtGreaterThanEqual(channel: Channel, since: LocalDateTime): Long
}

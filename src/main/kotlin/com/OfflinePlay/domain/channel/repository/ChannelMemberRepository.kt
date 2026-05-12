package com.contenido.domain.channel.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ChannelMemberRepository : JpaRepository<ChannelMember, Long> {

    fun findByChannel(channel: Channel): List<ChannelMember>

    fun findByUser(user: User): List<ChannelMember>

    fun findByChannelAndRole(channel: Channel, role: ChannelMemberRole): List<ChannelMember>

    fun findByChannelAndUser(channel: Channel, user: User): Optional<ChannelMember>

    fun existsByChannelAndUser(channel: Channel, user: User): Boolean
}

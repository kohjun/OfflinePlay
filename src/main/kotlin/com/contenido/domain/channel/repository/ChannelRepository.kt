package com.contenido.domain.channel.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ChannelRepository : JpaRepository<Channel, Long> {

    fun findByOwner(owner: User): Optional<Channel>

    fun existsByOwner(owner: User): Boolean

    fun findByCategoryOrderBySubscriberCountDesc(category: ChannelCategory, pageable: Pageable): Page<Channel>
}

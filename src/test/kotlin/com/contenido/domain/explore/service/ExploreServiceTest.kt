package com.contenido.domain.explore.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.ContentType
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@ExtendWith(MockKExtension::class)
class ExploreServiceTest {

    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var eventRepository: EventRepository

    private lateinit var service: ExploreService

    @BeforeEach
    fun setUp() {
        service = ExploreService(channelRepository, eventRepository)
        // 기본 stub — 각 테스트가 필요 시 verify 로 인자 검증.
        every {
            eventRepository.searchForExplore(any(), any(), any(), any())
        } returns PageImpl(emptyList<Event>(), PageRequest.of(0, 20), 0)
        every {
            channelRepository.searchForExplore(any(), any(), any())
        } returns PageImpl(emptyList<Channel>(), PageRequest.of(0, 20), 0)
    }

    // ── 정상 매핑 ─────────────────────────────────────────────────────────────

    @Test
    fun `explore keyword + category + contentType 가 그대로 repository 에 전달된다`() {
        val owner = createUser(id = 1L)
        val channel = createChannel(id = 10L, owner = owner, category = ChannelCategory.MUSIC)
        val event = createEvent(id = 100L, channel = channel, contentType = ContentType.ORIGINAL)

        every {
            eventRepository.searchForExplore(
                keyword = "hello",
                category = ChannelCategory.MUSIC,
                contentType = ContentType.ORIGINAL,
                pageable = any(),
            )
        } returns PageImpl(listOf(event), PageRequest.of(0, 20), 1)

        every {
            channelRepository.searchForExplore(
                keyword = "hello",
                category = ChannelCategory.MUSIC,
                pageable = any(),
            )
        } returns PageImpl(listOf(channel), PageRequest.of(0, 20), 1)

        val result = service.explore(
            keyword = "  hello  ",
            category = "MUSIC",
            contentType = "ORIGINAL",
            page = 0,
            size = 20,
        )

        // 정확히 위 stub 으로 호출됐는지 = trim + enum parse 가 맞는지
        verify(exactly = 1) {
            eventRepository.searchForExplore(
                keyword = "hello",
                category = ChannelCategory.MUSIC,
                contentType = ContentType.ORIGINAL,
                pageable = any(),
            )
        }
        verify(exactly = 1) {
            channelRepository.searchForExplore(
                keyword = "hello",
                category = ChannelCategory.MUSIC,
                pageable = any(),
            )
        }

        // 응답 매핑
        assertThat(result.events.content).hasSize(1)
        assertThat(result.events.content[0].id).isEqualTo(100L)
        assertThat(result.events.content[0].channelId).isEqualTo(10L)
        assertThat(result.events.content[0].channelOwnerId).isEqualTo(1L)
        assertThat(result.channels.content).hasSize(1)
        assertThat(result.channels.content[0].id).isEqualTo(10L)
        assertThat(result.channels.content[0].categoryDisplayName).isEqualTo(ChannelCategory.MUSIC.displayName)
    }

    @Test
    fun `explore 비어 있거나 잘못된 enum 은 null 로 무시된다`() {
        service.explore(
            keyword = "",
            category = "NOT_A_CATEGORY",
            contentType = "NOT_A_TYPE",
            page = 0,
            size = 20,
        )

        // keyword "" → null, 잘못된 enum → null 로 보정되어 repo 가 모두 null 인자로 호출돼야 한다.
        verify(exactly = 1) {
            eventRepository.searchForExplore(
                keyword = null,
                category = null,
                contentType = null,
                pageable = any(),
            )
        }
        verify(exactly = 1) {
            channelRepository.searchForExplore(
                keyword = null,
                category = null,
                pageable = any(),
            )
        }
    }

    @Test
    fun `explore 빈 결과면 PageResponse content 비어 있고 totalElements 0`() {
        val result = service.explore(keyword = "없는키워드", category = null, contentType = null, page = 0, size = 20)

        assertThat(result.events.content).isEmpty()
        assertThat(result.events.totalElements).isEqualTo(0)
        assertThat(result.channels.content).isEmpty()
        assertThat(result.channels.totalElements).isEqualTo(0)
    }

    @Test
    fun `explore size 999 는 50 으로 page 0 음수는 0 으로 clamp`() {
        val pageableSlot = slot<Pageable>()
        every {
            eventRepository.searchForExplore(any(), any(), any(), capture(pageableSlot))
        } returns PageImpl(emptyList<Event>(), PageRequest.of(0, 50), 0)

        service.explore(keyword = null, category = null, contentType = null, page = -5, size = 999)

        assertThat(pageableSlot.captured.pageSize).isEqualTo(50)
        assertThat(pageableSlot.captured.pageNumber).isEqualTo(0)
    }

    @Test
    fun `explore 모든 파라미터가 null 이면 repository 도 null 로 호출`() {
        service.explore(keyword = null, category = null, contentType = null, page = 0, size = 20)

        verify(exactly = 1) {
            eventRepository.searchForExplore(keyword = null, category = null, contentType = null, pageable = any())
        }
        verify(exactly = 1) {
            channelRepository.searchForExplore(keyword = null, category = null, pageable = any())
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(id: Long = 1L, role: UserRole = UserRole.CREATOR, nickname: String = "owner$id"): User {
            val u = User("u$id@test.com", "encoded", nickname, "01012345678").apply { updateRole(role) }
            ReflectionTestUtils.setField(u, "id", id)
            ReflectionTestUtils.setField(u, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(u, "updatedAt", LocalDateTime.now())
            return u
        }

        fun createChannel(
            id: Long,
            owner: User,
            category: ChannelCategory = ChannelCategory.MUSIC,
        ): Channel {
            val c = Channel(owner, "채널$id", "설명", category)
            ReflectionTestUtils.setField(c, "id", id)
            ReflectionTestUtils.setField(c, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(c, "updatedAt", LocalDateTime.now())
            return c
        }

        fun createEvent(
            id: Long,
            channel: Channel,
            contentType: ContentType? = null,
            status: EventStatus = EventStatus.UPCOMING,
        ): Event {
            val e = Event(
                channel = channel,
                title = "이벤트$id",
                description = "desc",
                location = "Seoul",
                mainImageUrl = "https://example.com/img.jpg",
                startAt = LocalDateTime.now().plusDays(1),
                endAt = LocalDateTime.now().plusDays(2),
                maxParticipants = 10,
                participationFee = 0L,
                refundPolicy = "환불",
                detailContent = "detail",
                status = status,
                contentType = contentType,
            )
            ReflectionTestUtils.setField(e, "id", id)
            ReflectionTestUtils.setField(e, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(e, "updatedAt", LocalDateTime.now())
            return e
        }
    }
}

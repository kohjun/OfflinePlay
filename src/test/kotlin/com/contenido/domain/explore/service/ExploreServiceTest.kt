package com.contenido.domain.explore.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.ContentType
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.search.service.PopularSearchService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
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
    @MockK lateinit var popularSearchService: PopularSearchService

    private lateinit var service: ExploreService

    /** 모든 필드 default 인 explore() 호출 — 테스트별로 필요한 인자만 override. */
    private fun callExplore(
        keyword: String? = null,
        category: String? = null,
        contentType: String? = null,
        location: String? = null,
        minFee: Long? = null,
        maxFee: Long? = null,
        startFrom: LocalDateTime? = null,
        startTo: LocalDateTime? = null,
        excludeClosed: Boolean = true,
        excludeFull: Boolean = false,
        page: Int = 0,
        size: Int = 20,
    ) = service.explore(
        keyword = keyword, category = category, contentType = contentType,
        location = location, minFee = minFee, maxFee = maxFee,
        startFrom = startFrom, startTo = startTo,
        excludeClosed = excludeClosed, excludeFull = excludeFull,
        page = page, size = size,
    )

    @BeforeEach
    fun setUp() {
        service = ExploreService(channelRepository, eventRepository, popularSearchService)
        // 기본 stub — 각 테스트가 필요 시 verify 로 인자 검증.
        every {
            eventRepository.searchForExplore(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns PageImpl(emptyList<Event>(), PageRequest.of(0, 20), 0)
        every {
            channelRepository.searchForExplore(any(), any(), any())
        } returns PageImpl(emptyList<Channel>(), PageRequest.of(0, 20), 0)
        // PopularSearchService 는 어떤 keyword 든 받아서 통과. 검색 흐름의 부수효과만.
        every { popularSearchService.recordKeyword(any()) } just Runs
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
                location = null,
                minFee = null, maxFee = null,
                startFrom = null, startTo = null,
                excludeClosed = true, excludeFull = false,
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

        val result = callExplore(keyword = "  hello  ", category = "MUSIC", contentType = "ORIGINAL")

        // 정확히 위 stub 으로 호출됐는지 = trim + enum parse 가 맞는지
        verify(exactly = 1) {
            eventRepository.searchForExplore(
                keyword = "hello",
                category = ChannelCategory.MUSIC,
                contentType = ContentType.ORIGINAL,
                location = null,
                minFee = null, maxFee = null,
                startFrom = null, startTo = null,
                excludeClosed = true, excludeFull = false,
                pageable = any(),
            )
        }
        verify(exactly = 1) {
            channelRepository.searchForExplore(keyword = "hello", category = ChannelCategory.MUSIC, pageable = any())
        }
        // PR45: 키워드 정규화 후 ZINCRBY.
        verify(exactly = 1) { popularSearchService.recordKeyword("hello") }

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
        callExplore(keyword = "", category = "NOT_A_CATEGORY", contentType = "NOT_A_TYPE")

        // keyword "" → null, 잘못된 enum → null 로 보정되어 repo 가 모두 null 인자로 호출돼야 한다.
        verify(exactly = 1) {
            eventRepository.searchForExplore(
                keyword = null, category = null, contentType = null,
                location = null, minFee = null, maxFee = null,
                startFrom = null, startTo = null,
                excludeClosed = true, excludeFull = false,
                pageable = any(),
            )
        }
        verify(exactly = 1) {
            channelRepository.searchForExplore(keyword = null, category = null, pageable = any())
        }
        // 키워드가 빈 문자열이면 PopularSearchService 는 호출되지 않는다.
        verify(exactly = 0) { popularSearchService.recordKeyword(any()) }
    }

    @Test
    fun `explore 빈 결과면 PageResponse content 비어 있고 totalElements 0`() {
        val result = callExplore(keyword = "없는키워드")

        assertThat(result.events.content).isEmpty()
        assertThat(result.events.totalElements).isEqualTo(0)
        assertThat(result.channels.content).isEmpty()
        assertThat(result.channels.totalElements).isEqualTo(0)
    }

    @Test
    fun `explore size 999 는 50 으로 page 0 음수는 0 으로 clamp`() {
        val pageableSlot = slot<Pageable>()
        every {
            eventRepository.searchForExplore(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(pageableSlot),
            )
        } returns PageImpl(emptyList<Event>(), PageRequest.of(0, 50), 0)

        callExplore(page = -5, size = 999)

        assertThat(pageableSlot.captured.pageSize).isEqualTo(50)
        assertThat(pageableSlot.captured.pageNumber).isEqualTo(0)
    }

    @Test
    fun `explore 모든 파라미터가 null 이면 repository 도 null 로 호출 + 인기검색어 기록 X`() {
        callExplore()

        verify(exactly = 1) {
            eventRepository.searchForExplore(
                keyword = null, category = null, contentType = null,
                location = null, minFee = null, maxFee = null,
                startFrom = null, startTo = null,
                excludeClosed = true, excludeFull = false,
                pageable = any(),
            )
        }
        verify(exactly = 1) {
            channelRepository.searchForExplore(keyword = null, category = null, pageable = any())
        }
        verify(exactly = 0) { popularSearchService.recordKeyword(any()) }
    }

    // ── PR45: 다중 필터 ──────────────────────────────────────────────────────

    @Test
    fun `explore location minFee maxFee startFrom startTo excludeFull 이 모두 그대로 전달된다`() {
        val from = LocalDateTime.now().plusDays(1)
        val to = LocalDateTime.now().plusDays(7)

        callExplore(
            location = "  강남  ", minFee = 0L, maxFee = 50_000L,
            startFrom = from, startTo = to,
            excludeClosed = true, excludeFull = true,
        )

        verify(exactly = 1) {
            eventRepository.searchForExplore(
                keyword = null, category = null, contentType = null,
                location = "강남",  // 양옆 공백 trim
                minFee = 0L, maxFee = 50_000L,
                startFrom = from, startTo = to,
                excludeClosed = true, excludeFull = true,
                pageable = any(),
            )
        }
    }

    @Test
    fun `explore 음수 minFee maxFee 는 null 로 보정 (잘못된 입력 방어)`() {
        callExplore(minFee = -100L, maxFee = -1L)

        verify(exactly = 1) {
            eventRepository.searchForExplore(
                keyword = null, category = null, contentType = null,
                location = null,
                minFee = null, maxFee = null,  // 음수는 모두 null 로 보정
                startFrom = null, startTo = null,
                excludeClosed = true, excludeFull = false,
                pageable = any(),
            )
        }
    }

    @Test
    fun `explore 키워드 정규화 후 PopularSearchService 에 trim 된 값이 전달된다`() {
        callExplore(keyword = "  주말 모임  ")
        verify(exactly = 1) { popularSearchService.recordKeyword("주말 모임") }
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

package com.contenido.domain.interaction.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.dto.CreateCommentRequest
import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.interaction.repository.LikeRepository
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.EventRoomAccessDeniedException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR140 — `TargetType.EVENT` 댓글(=이벤트 룸) 작성 권한 가드 검증.
 *  - 채널 owner / APPROVED participation / ADMIN 중 하나여야 통과
 *  - 그 외는 EventRoomAccessDeniedException
 *  - POST 등 다른 targetType 은 가드 영향 없음
 */
@ExtendWith(MockKExtension::class)
class CommentServiceTest {

    @MockK lateinit var commentRepository: CommentRepository
    @MockK lateinit var likeRepository: LikeRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var eventParticipationRepository: EventParticipationRepository
    @MockK(relaxed = true) lateinit var notificationService: NotificationService

    private lateinit var service: CommentService

    @BeforeEach
    fun setUp() {
        service = CommentService(
            commentRepository,
            likeRepository,
            userRepository,
            postRepository,
            eventRepository,
            eventParticipationRepository,
            notificationService,
        )
    }

    @Test
    fun `PR140 — EVENT 룸 댓글 작성 — APPROVED 참가자는 통과`() {
        val author = createUser(id = 2L)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 100L, owner = owner)
        val participation = EventParticipation(event = event, participant = author).apply {
            status = ParticipationStatus.APPROVED
            ReflectionTestUtils.setField(this, "id", 555L)
        }
        every { userRepository.findById(2L) } returns Optional.of(author)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, author) } returns
            Optional.of(participation)
        every { postRepository.findById(any()) } returns Optional.empty() // unused for EVENT path
        val saved = slot<Comment>()
        every { commentRepository.save(capture(saved)) } answers {
            saved.captured.also {
                ReflectionTestUtils.setField(it, "id", 999L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        service.createComment(2L, TargetType.EVENT, 100L, CreateCommentRequest("안녕하세요", null))

        assertThat(saved.captured.targetType).isEqualTo(TargetType.EVENT)
        assertThat(saved.captured.targetId).isEqualTo(100L)
    }

    @Test
    fun `PR140 — EVENT 룸 댓글 작성 — 채널 owner 는 통과 (참가 row 없어도)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 100L, owner = owner)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { postRepository.findById(any()) } returns Optional.empty()
        val saved = slot<Comment>()
        every { commentRepository.save(capture(saved)) } answers {
            saved.captured.also {
                ReflectionTestUtils.setField(it, "id", 999L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        service.createComment(1L, TargetType.EVENT, 100L, CreateCommentRequest("운영 공지", null))

        // owner 는 participation lookup 없이 owner.id 매칭만으로 통과 — 호출 횟수 검증.
        verify(exactly = 0) { eventParticipationRepository.findByEventAndParticipant(any(), any()) }
    }

    @Test
    fun `PR140 — EVENT 룸 댓글 작성 — ADMIN 은 통과 (참가자 lookup 없음)`() {
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { postRepository.findById(any()) } returns Optional.empty()
        // 알림 path 가 owner 조회를 위해 호출하지만 ADMIN 가드 안에서는 호출되지 않는다.
        every { eventRepository.findById(100L) } returns Optional.empty()
        val saved = slot<Comment>()
        every { commentRepository.save(capture(saved)) } answers {
            saved.captured.also {
                ReflectionTestUtils.setField(it, "id", 999L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        service.createComment(99L, TargetType.EVENT, 100L, CreateCommentRequest("admin", null))

        // ADMIN 은 권한 가드에서 participation 조회 없이 통과 — 핵심 invariant.
        verify(exactly = 0) { eventParticipationRepository.findByEventAndParticipant(any(), any()) }
    }

    @Test
    fun `PR140 — EVENT 룸 댓글 작성 — PENDING 참가자는 차단`() {
        val author = createUser(id = 2L)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 100L, owner = owner)
        val pending = EventParticipation(event = event, participant = author).apply {
            status = ParticipationStatus.PENDING
            ReflectionTestUtils.setField(this, "id", 555L)
        }
        every { userRepository.findById(2L) } returns Optional.of(author)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, author) } returns
            Optional.of(pending)

        assertThrows<EventRoomAccessDeniedException> {
            service.createComment(2L, TargetType.EVENT, 100L, CreateCommentRequest("hi", null))
        }
        verify(exactly = 0) { commentRepository.save(any()) }
    }

    @Test
    fun `PR140 — EVENT 룸 댓글 작성 — 참가 row 자체가 없어도 차단`() {
        val author = createUser(id = 3L)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 100L, owner = owner)
        every { userRepository.findById(3L) } returns Optional.of(author)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, author) } returns
            Optional.empty()

        assertThrows<EventRoomAccessDeniedException> {
            service.createComment(3L, TargetType.EVENT, 100L, CreateCommentRequest("hi", null))
        }
        verify(exactly = 0) { commentRepository.save(any()) }
    }

    @Test
    fun `PR140 — EVENT 룸 댓글 작성 — REJECTED 참가자도 차단`() {
        val author = createUser(id = 4L)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 100L, owner = owner)
        val rejected = EventParticipation(event = event, participant = author).apply {
            status = ParticipationStatus.REJECTED
            ReflectionTestUtils.setField(this, "id", 555L)
        }
        every { userRepository.findById(4L) } returns Optional.of(author)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, author) } returns
            Optional.of(rejected)

        assertThrows<EventRoomAccessDeniedException> {
            service.createComment(4L, TargetType.EVENT, 100L, CreateCommentRequest("hi", null))
        }
    }

    @Test
    fun `PR140 — POST 타겟 댓글은 가드 없음 (회귀 가드)`() {
        // PR140 의 가드가 EVENT 만 잡고 POST/COMMENT 는 기존 동작 유지.
        val author = createUser(id = 2L)
        every { userRepository.findById(2L) } returns Optional.of(author)
        every { postRepository.findById(50L) } returns Optional.empty()
        val saved = slot<Comment>()
        every { commentRepository.save(capture(saved)) } answers {
            saved.captured.also {
                ReflectionTestUtils.setField(it, "id", 1L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        service.createComment(2L, TargetType.POST, 50L, CreateCommentRequest("게시글 댓글", null))

        // EVENT 가드 경로가 한 번도 호출되지 않아야 한다.
        verify(exactly = 0) { eventRepository.findById(any()) }
        verify(exactly = 0) { eventParticipationRepository.findByEventAndParticipant(any(), any()) }
        assertThat(saved.captured.targetType).isEqualTo(TargetType.POST)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun createUser(id: Long, role: UserRole = UserRole.PARTICIPANT): User =
        User("u$id@test.com", "encoded", "user$id", "0101111${id.toString().padStart(4, '0')}")
            .apply {
                ReflectionTestUtils.setField(this, "id", id)
                updateRole(role)
            }

    private fun createChannel(id: Long, owner: User): Channel =
        Channel(owner, "ch$id", "desc", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun createEvent(id: Long, owner: User): Event = Event(
        channel = createChannel(id = 10L, owner = owner),
        title = "event$id",
        description = "desc",
        location = "서울",
        mainImageUrl = "https://example.com/$id.jpg",
        startAt = LocalDateTime.now().plusDays(1),
        endAt = LocalDateTime.now().plusDays(1).plusHours(2),
        maxParticipants = 30,
        participationFee = 0L,
        refundPolicy = "policy",
        detailContent = "detail",
        status = EventStatus.UPCOMING,
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
    }
}

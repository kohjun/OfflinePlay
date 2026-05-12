package com.contenido.domain.post.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.dto.CreatePostRequest
import com.contenido.domain.post.dto.UpdatePostRequest
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.entity.PostStatus
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.event.ContentSyncEvent
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class PostServiceTest {

    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var channelSubscriptionRepository: ChannelSubscriptionRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var notificationService: NotificationService
    @MockK lateinit var publisher: ApplicationEventPublisher

    private lateinit var service: PostService

    @BeforeEach
    fun setUp() {
        service = PostService(
            postRepository = postRepository,
            channelRepository = channelRepository,
            channelSubscriptionRepository = channelSubscriptionRepository,
            userRepository = userRepository,
            notificationService = notificationService,
            publisher = publisher,
        )
        every { notificationService.notify(any(), any(), any(), any(), any(), any()) } just Runs
        every { publisher.publishEvent(any<ContentSyncEvent>()) } just Runs
        every { channelSubscriptionRepository.findByChannel(any()) } returns emptyList()
    }

    // ── createPost ────────────────────────────────────────────────────────────

    @Test
    fun `createPost owner 가 작성하면 성공 + 구독자 NEW_POST 알림`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val request = CreatePostRequest(title = "공지", content = "안녕하세요", thumbnailUrl = null)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { postRepository.save(any()) } answers {
            val arg = firstArg<Post>()
            ReflectionTestUtils.setField(arg, "id", 100L)
            ReflectionTestUtils.setField(arg, "createdAt", LocalDateTime.now())
            arg
        }

        val result = service.createPost(userId = 1L, channelId = 10L, request = request)

        assertThat(result.id).isEqualTo(100L)
        assertThat(result.channelId).isEqualTo(10L)
        assertThat(result.title).isEqualTo("공지")
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = emptyList(),
                type = NotificationType.NEW_POST,
                title = any(),
                message = any(),
                // 알림 클릭 시 채널 공지 탭으로 이동해야 하므로 targetType="channels".
                targetType = "channels",
                targetId = 10L,
            )
        }
    }

    @Test
    fun `createPost owner 가 아니면 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val request = CreatePostRequest(title = "공지", content = "내용")

        every { userRepository.findById(2L) } returns Optional.of(intruder)
        every { channelRepository.findById(10L) } returns Optional.of(channel)

        assertThrows<UnauthorizedException> {
            service.createPost(userId = 2L, channelId = 10L, request = request)
        }
        verify(exactly = 0) { postRepository.save(any()) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createPost PARTICIPANT 도 owner 가 아니면 거부 (UnauthorizedException)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L, role = UserRole.PARTICIPANT)
        val channel = createChannel(id = 10L, owner = owner)
        val request = CreatePostRequest(title = "공지", content = "내용")

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { channelRepository.findById(10L) } returns Optional.of(channel)

        assertThrows<UnauthorizedException> {
            service.createPost(userId = 2L, channelId = 10L, request = request)
        }
    }

    @Test
    fun `createPost 사용자 없으면 UserNotFoundException`() {
        every { userRepository.findById(404L) } returns Optional.empty()

        assertThrows<UserNotFoundException> {
            service.createPost(userId = 404L, channelId = 10L, request = CreatePostRequest("t", "c"))
        }
    }

    @Test
    fun `createPost 탈퇴한 사용자면 DeletedUserException`() {
        val user = createUser(id = 1L, role = UserRole.CREATOR).also { it.softDelete() }
        every { userRepository.findById(1L) } returns Optional.of(user)

        assertThrows<DeletedUserException> {
            service.createPost(userId = 1L, channelId = 10L, request = CreatePostRequest("t", "c"))
        }
    }

    @Test
    fun `createPost 채널 없으면 ChannelNotFoundException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(99L) } returns Optional.empty()

        assertThrows<ChannelNotFoundException> {
            service.createPost(userId = 1L, channelId = 99L, request = CreatePostRequest("t", "c"))
        }
    }

    // ── updatePost / deletePost ───────────────────────────────────────────────

    @Test
    fun `updatePost 작성자 본인이면 성공`() {
        val author = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = author)
        val post = createPost(id = 100L, channel = channel, author = author, title = "원래 제목")

        every { userRepository.findById(1L) } returns Optional.of(author)
        every { postRepository.findByIdAndStatus(100L, PostStatus.PUBLISHED) } returns post

        val result = service.updatePost(userId = 1L, postId = 100L, request = UpdatePostRequest(title = "수정"))

        assertThat(result.title).isEqualTo("수정")
        assertThat(post.title).isEqualTo("수정")
    }

    @Test
    fun `updatePost ADMIN 도 수정 가능`() {
        val author = createUser(id = 1L, role = UserRole.CREATOR)
        val admin = createUser(id = 9L, role = UserRole.ADMIN)
        val channel = createChannel(id = 10L, owner = author)
        val post = createPost(id = 100L, channel = channel, author = author, title = "원래 제목")

        every { userRepository.findById(9L) } returns Optional.of(admin)
        every { postRepository.findByIdAndStatus(100L, PostStatus.PUBLISHED) } returns post

        val result = service.updatePost(userId = 9L, postId = 100L, request = UpdatePostRequest(content = "관리자 수정"))

        assertThat(result.content).isEqualTo("관리자 수정")
    }

    @Test
    fun `updatePost 작성자가 아닌 일반 사용자는 UnauthorizedException`() {
        val author = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = author)
        val post = createPost(id = 100L, channel = channel, author = author, title = "원래")

        every { userRepository.findById(2L) } returns Optional.of(intruder)
        every { postRepository.findByIdAndStatus(100L, PostStatus.PUBLISHED) } returns post

        assertThrows<UnauthorizedException> {
            service.updatePost(userId = 2L, postId = 100L, request = UpdatePostRequest(title = "x"))
        }
        assertThat(post.title).isEqualTo("원래")
    }

    @Test
    fun `deletePost 작성자 본인이면 상태가 DELETED 로 전환`() {
        val author = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = author)
        val post = createPost(id = 100L, channel = channel, author = author, title = "x")

        every { userRepository.findById(1L) } returns Optional.of(author)
        every { postRepository.findByIdAndStatus(100L, PostStatus.PUBLISHED) } returns post

        service.deletePost(userId = 1L, postId = 100L)

        assertThat(post.status).isEqualTo(PostStatus.DELETED)
    }

    @Test
    fun `deletePost 다른 사용자는 UnauthorizedException`() {
        val author = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = author)
        val post = createPost(id = 100L, channel = channel, author = author, title = "x")

        every { userRepository.findById(2L) } returns Optional.of(intruder)
        every { postRepository.findByIdAndStatus(100L, PostStatus.PUBLISHED) } returns post

        assertThrows<UnauthorizedException> { service.deletePost(userId = 2L, postId = 100L) }
        assertThat(post.status).isEqualTo(PostStatus.PUBLISHED)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(
            id: Long = 1L,
            role: UserRole = UserRole.CREATOR,
            nickname: String = "user$id",
        ): User {
            val u = User("u$id@test.com", "encoded", nickname, "01012345678").apply { updateRole(role) }
            ReflectionTestUtils.setField(u, "id", id)
            ReflectionTestUtils.setField(u, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(u, "updatedAt", LocalDateTime.now())
            return u
        }

        fun createChannel(id: Long, owner: User): Channel {
            val c = Channel(owner, "채널$id", "설명", ChannelCategory.MUSIC)
            ReflectionTestUtils.setField(c, "id", id)
            ReflectionTestUtils.setField(c, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(c, "updatedAt", LocalDateTime.now())
            return c
        }

        fun createPost(
            id: Long,
            channel: Channel,
            author: User,
            title: String = "공지",
            content: String = "내용",
        ): Post {
            val p = Post(channel = channel, author = author, title = title, content = content, thumbnailUrl = null)
            ReflectionTestUtils.setField(p, "id", id)
            ReflectionTestUtils.setField(p, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(p, "updatedAt", LocalDateTime.now())
            return p
        }
    }
}

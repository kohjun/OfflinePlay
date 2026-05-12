package com.contenido.domain.admin.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.ReportAlreadyProcessedException
import com.contenido.global.exception.ReportNotFoundException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class AdminServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var reportRepository: ReportRepository

    private lateinit var adminService: AdminService

    @BeforeEach
    fun setUp() {
        adminService = AdminService(
            userRepository = userRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
        )
    }

    // ── resolveReport ─────────────────────────────────────────────────────────

    @Test
    fun `resolveReport PENDING 신고를 RESOLVED 로 전환`() {
        val report = createReport(id = 1L, status = ReportStatus.PENDING)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        val result = adminService.resolveReport(1L)

        assertThat(result.status).isEqualTo(ReportStatus.RESOLVED)
        assertThat(report.status).isEqualTo(ReportStatus.RESOLVED)
    }

    @Test
    fun `resolveReport 존재하지 않는 신고 예외`() {
        every { reportRepository.findById(99L) } returns Optional.empty()

        assertThrows<ReportNotFoundException> { adminService.resolveReport(99L) }
    }

    @Test
    fun `resolveReport 이미 RESOLVED 인 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.RESOLVED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.resolveReport(1L) }
    }

    @Test
    fun `resolveReport 이미 DISMISSED 인 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.DISMISSED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.resolveReport(1L) }
    }

    // ── dismissReport ─────────────────────────────────────────────────────────

    @Test
    fun `dismissReport PENDING 신고를 DISMISSED 로 전환`() {
        val report = createReport(id = 1L, status = ReportStatus.PENDING)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        val result = adminService.dismissReport(1L)

        assertThat(result.status).isEqualTo(ReportStatus.DISMISSED)
        assertThat(report.status).isEqualTo(ReportStatus.DISMISSED)
    }

    @Test
    fun `dismissReport 존재하지 않는 신고 예외`() {
        every { reportRepository.findById(99L) } returns Optional.empty()

        assertThrows<ReportNotFoundException> { adminService.dismissReport(99L) }
    }

    @Test
    fun `dismissReport 이미 처리된 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.RESOLVED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.dismissReport(1L) }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun createUser(id: Long = 1L): User {
        val user = User("reporter@test.com", "pwd", "reporter", "01012345678")
            .apply { updateRole(UserRole.PARTICIPANT) }
        ReflectionTestUtils.setField(user, "id", id)
        ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.now())
        return user
    }

    private fun createReport(id: Long, status: ReportStatus): Report {
        val report = Report(
            reporter = createUser(),
            targetType = ReportTargetType.CHANNEL,
            targetId = 100L,
            reason = "부적절한 내용",
        )
        ReflectionTestUtils.setField(report, "id", id)
        ReflectionTestUtils.setField(report, "status", status)
        ReflectionTestUtils.setField(report, "createdAt", LocalDateTime.now())
        return report
    }
}

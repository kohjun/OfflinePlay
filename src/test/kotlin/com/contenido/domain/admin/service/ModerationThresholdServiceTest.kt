package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.UpdateModerationThresholdsRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationThresholdSetting
import com.contenido.domain.admin.repository.ModerationThresholdSettingRepository
import com.contenido.domain.report.entity.ReportTargetType
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
import java.util.Optional

/**
 * PR60 — DB 임계치 조회/갱신 service 동작 검증.
 *
 *  - DB 가 비어 있을 때 default fallback 반환
 *  - DB row 가 있으면 그 값 우선
 *  - partial update — null 필드 무시, 변경된 필드만 update
 *  - DB row 없는 targetType 갱신 시 save (V4 seed 누락 안전판)
 *  - 1..100 범위 가드 (controller 외 service 단 2차 가드)
 */
@ExtendWith(MockKExtension::class)
class ModerationThresholdServiceTest {

    @MockK lateinit var moderationThresholdSettingRepository: ModerationThresholdSettingRepository
    // PR61 — updateThresholds 가 audit log 를 기록. 본 테스트는 audit 호출 자체는 무시.
    @MockK(relaxed = true) lateinit var moderationAuditLogService: ModerationAuditLogService

    private lateinit var service: ModerationThresholdService

    @BeforeEach
    fun setUp() {
        service = ModerationThresholdService(
            moderationThresholdSettingRepository,
            moderationAuditLogService,
        )
    }

    /** PR61 — actor id placeholder. record() 가 relaxed mock 이라 어떤 값이든 무방. */
    private val ACTOR_ID: Long = 99L

    @Test
    fun `getThresholds DB 비어 있으면 DEFAULTS 로 5개 모두 채워 반환`() {
        every { moderationThresholdSettingRepository.findAll() } returns emptyList()

        val result = service.getThresholds()

        assertThat(result).hasSize(5)
        assertThat(result.first { it.targetType == ReportTargetType.REVIEW }.threshold).isEqualTo(3)
        assertThat(result.first { it.targetType == ReportTargetType.COMMENT }.threshold).isEqualTo(3)
        assertThat(result.first { it.targetType == ReportTargetType.POST }.threshold).isEqualTo(5)
        assertThat(result.first { it.targetType == ReportTargetType.EVENT }.threshold).isEqualTo(5)
        assertThat(result.first { it.targetType == ReportTargetType.CHANNEL }.threshold).isEqualTo(7)
    }

    @Test
    fun `getThresholds DB row 가 있는 type 은 그 값을 default 보다 우선해 반환`() {
        every { moderationThresholdSettingRepository.findAll() } returns listOf(
            ModerationThresholdSetting(ReportTargetType.REVIEW, 10),
            ModerationThresholdSetting(ReportTargetType.CHANNEL, 20),
        )

        val result = service.getThresholds()

        assertThat(result.first { it.targetType == ReportTargetType.REVIEW }.threshold).isEqualTo(10)
        assertThat(result.first { it.targetType == ReportTargetType.CHANNEL }.threshold).isEqualTo(20)
        // 누락된 type 은 default.
        assertThat(result.first { it.targetType == ReportTargetType.POST }.threshold).isEqualTo(5)
    }

    @Test
    fun `thresholdFor DB row 있으면 그 값`() {
        every { moderationThresholdSettingRepository.findById(ReportTargetType.REVIEW) } returns
            Optional.of(ModerationThresholdSetting(ReportTargetType.REVIEW, 11))

        assertThat(service.thresholdFor(ReportTargetType.REVIEW)).isEqualTo(11)
    }

    @Test
    fun `thresholdFor DB miss 면 DEFAULTS fallback`() {
        every { moderationThresholdSettingRepository.findById(ReportTargetType.POST) } returns Optional.empty()

        assertThat(service.thresholdFor(ReportTargetType.POST)).isEqualTo(5)
    }

    @Test
    fun `updateThresholds partial — null 필드는 변경하지 않음`() {
        val existingReview = ModerationThresholdSetting(ReportTargetType.REVIEW, 3)
        every { moderationThresholdSettingRepository.findById(ReportTargetType.REVIEW) } returns
            Optional.of(existingReview)
        // 다른 type 의 findById 는 호출되지 않아야 함 (request 가 review 만 채워 보냄).
        every { moderationThresholdSettingRepository.findAll() } returns listOf(existingReview)

        service.updateThresholds(ACTOR_ID, UpdateModerationThresholdsRequest(review = 7))

        assertThat(existingReview.thresholdValue).isEqualTo(7)
        verify(exactly = 0) { moderationThresholdSettingRepository.findById(ReportTargetType.POST) }
        verify(exactly = 0) { moderationThresholdSettingRepository.findById(ReportTargetType.COMMENT) }
        verify(exactly = 0) { moderationThresholdSettingRepository.findById(ReportTargetType.EVENT) }
        verify(exactly = 0) { moderationThresholdSettingRepository.findById(ReportTargetType.CHANNEL) }
    }

    @Test
    fun `updateThresholds DB row 없는 type 은 새로 save`() {
        every { moderationThresholdSettingRepository.findById(ReportTargetType.CHANNEL) } returns Optional.empty()
        val saved = slot<ModerationThresholdSetting>()
        every { moderationThresholdSettingRepository.save(capture(saved)) } answers { saved.captured }
        every { moderationThresholdSettingRepository.findAll() } returns listOf(
            ModerationThresholdSetting(ReportTargetType.CHANNEL, 15),
        )

        val result = service.updateThresholds(ACTOR_ID, UpdateModerationThresholdsRequest(channel = 15))

        assertThat(saved.captured.targetType).isEqualTo(ReportTargetType.CHANNEL)
        assertThat(saved.captured.thresholdValue).isEqualTo(15)
        assertThat(result.first { it.targetType == ReportTargetType.CHANNEL }.threshold).isEqualTo(15)
    }

    @Test
    fun `updateThresholds 1 미만 값은 IllegalArgumentException`() {
        // service 단 2차 가드. controller @Valid 가 1차로 잡지만 stub 우회를 막는다.
        assertThrows<IllegalArgumentException> {
            service.updateThresholds(ACTOR_ID, UpdateModerationThresholdsRequest(review = 0))
        }
    }

    @Test
    fun `updateThresholds 100 초과 값은 IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            service.updateThresholds(ACTOR_ID, UpdateModerationThresholdsRequest(post = 101))
        }
    }

    @Test
    fun `updateThresholds 모든 필드 null 이면 no-op (DB 호출 없이 현재 thresholds 반환)`() {
        every { moderationThresholdSettingRepository.findAll() } returns emptyList()

        val result = service.updateThresholds(ACTOR_ID, UpdateModerationThresholdsRequest())

        // findById/save 가 한 번도 호출되지 않아야 함.
        verify(exactly = 0) { moderationThresholdSettingRepository.findById(any()) }
        verify(exactly = 0) { moderationThresholdSettingRepository.save(any()) }
        // 그래도 응답은 5개 type 전부 default 로 채워 반환.
        assertThat(result).hasSize(5)
        // PR61 — no-op 이면 audit log 도 기록하지 않는다.
        verify(exactly = 0) {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ── PR61 audit ────────────────────────────────────────────────────────────

    @Test
    fun `updateThresholds 실제 변경된 필드만 THRESHOLD_UPDATED audit 기록`() {
        val existingReview = ModerationThresholdSetting(ReportTargetType.REVIEW, 3)
        every { moderationThresholdSettingRepository.findById(ReportTargetType.REVIEW) } returns
            Optional.of(existingReview)
        every { moderationThresholdSettingRepository.findAll() } returns listOf(
            ModerationThresholdSetting(ReportTargetType.REVIEW, 7),
        )

        service.updateThresholds(ACTOR_ID, UpdateModerationThresholdsRequest(review = 7))

        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ACTOR_ID,
                action = ModerationAuditAction.THRESHOLD_UPDATED,
                targetType = null,
                targetId = null,
                beforeValue = mapOf(ReportTargetType.REVIEW to 3),
                afterValue = mapOf(ReportTargetType.REVIEW to 7),
                reason = null,
            )
        }
    }

    @Test
    fun `updateThresholds 동일 값 set 은 audit 노이즈로 보고 기록하지 않는다`() {
        val existingPost = ModerationThresholdSetting(ReportTargetType.POST, 5)
        every { moderationThresholdSettingRepository.findById(ReportTargetType.POST) } returns
            Optional.of(existingPost)
        every { moderationThresholdSettingRepository.findAll() } returns listOf(existingPost)

        service.updateThresholds(ACTOR_ID, UpdateModerationThresholdsRequest(post = 5))

        verify(exactly = 0) {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any())
        }
    }
}

package com.contenido.domain.explore.service

import com.contenido.domain.channel.dto.ChannelResponse
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.dto.EventResponse
import com.contenido.domain.event.entity.ContentType
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.explore.dto.ExploreResponse
import com.contenido.domain.search.service.PopularSearchService
import com.contenido.global.response.PageResponse
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 홈/Explore 화면용 단일 진입점. 키워드/카테고리/콘텐츠 유형/장소/가격대/일정 범위/마감여부 등
 * 다중 속성을 조합해 채널·이벤트를 함께 조회한다.
 *
 * 검색 엔진(Elasticsearch) 의존 없이 JPA LIKE/범위 쿼리로 처리해 로컬/CI 환경에서도 항상 동작한다.
 * 검색어가 비어 있으면 카테고리·콘텐츠 유형·가격대·일정만으로 필터링되며, 모든 조건이 비어 있으면
 * (excludeClosed 기본 true 영향으로) 종료되지 않은 이벤트 + 구독자 많은 채널을 반환한다.
 *
 * PR45: 인기 검색어 ranking 을 위해 키워드가 들어오면 [PopularSearchService] 로 ZINCRBY 1.
 */
@Service
@Transactional(readOnly = true)
class ExploreService(
    private val channelRepository: ChannelRepository,
    private val eventRepository: EventRepository,
    private val popularSearchService: PopularSearchService,
) {

    fun explore(
        keyword: String?,
        category: String?,
        contentType: String?,
        location: String?,
        minFee: Long?,
        maxFee: Long?,
        startFrom: LocalDateTime?,
        startTo: LocalDateTime?,
        excludeClosed: Boolean,
        excludeFull: Boolean,
        page: Int,
        size: Int,
    ): ExploreResponse {
        val safeKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val safeLocation = location?.trim()?.takeIf { it.isNotEmpty() }
        val parsedCategory = parseCategory(category)
        val parsedContentType = parseContentType(contentType)
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 50))

        // PR45: 키워드가 있으면 인기 검색어 카운트 증가. 실패해도 검색 흐름 막지 않는다.
        if (safeKeyword != null) {
            runCatching { popularSearchService.recordKeyword(safeKeyword) }
        }

        val eventPage = eventRepository.searchForExplore(
            keyword = safeKeyword,
            category = parsedCategory,
            contentType = parsedContentType,
            location = safeLocation,
            minFee = minFee?.takeIf { it >= 0 },
            maxFee = maxFee?.takeIf { it >= 0 },
            startFrom = startFrom,
            startTo = startTo,
            excludeClosed = excludeClosed,
            excludeFull = excludeFull,
            pageable = pageable,
        )

        // 콘텐츠 유형/가격/일정은 이벤트에만 적용 — 채널 자체에는 해당 속성이 없음.
        val channelPage = channelRepository.searchForExplore(
            keyword = safeKeyword,
            category = parsedCategory,
            pageable = pageable,
        )

        return ExploreResponse(
            events = PageResponse.of(eventPage.map { it.toResponse() }),
            channels = PageResponse.of(channelPage.map { it.toResponse() }),
        )
    }

    // 잘못된 enum 값은 400 으로 떨어뜨리지 않고 무시 — UX 우선.
    private fun parseCategory(raw: String?): ChannelCategory? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { ChannelCategory.valueOf(it) }.getOrNull() }

    private fun parseContentType(raw: String?): ContentType? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { ContentType.valueOf(it) }.getOrNull() }

    private fun Event.toResponse() = EventResponse(
        id = id,
        channelId = channel.id,
        channelName = channel.name,
        channelOwnerId = channel.owner.id,
        title = title,
        description = description,
        location = location,
        mainImageUrl = mainImageUrl,
        startAt = startAt,
        endAt = endAt,
        maxParticipants = maxParticipants,
        currentParticipants = currentParticipants,
        participationFee = participationFee,
        refundPolicy = refundPolicy,
        detailContent = detailContent,
        status = status,
        contentType = contentType,
        createdAt = createdAt,
    )

    private fun Channel.toResponse() = ChannelResponse(
        id = id,
        ownerId = owner.id,
        ownerNickname = owner.nickname,
        name = name,
        description = description,
        category = category,
        categoryDisplayName = category.displayName,
        thumbnailUrl = thumbnailUrl,
        subscriberCount = subscriberCount,
        createdAt = createdAt,
    )
}

package com.contenido.domain.explore.dto

import com.contenido.domain.channel.dto.ChannelResponse
import com.contenido.domain.event.dto.EventResponse
import com.contenido.global.response.PageResponse

/**
 * Explore 화면 한 번의 호출로 받는 묶음 응답.
 *  - events  : 키워드/카테고리/콘텐츠유형 필터를 모두 적용한 이벤트 페이지
 *  - channels: 키워드/카테고리만 적용한 채널 페이지 (채널 자체에는 contentType 없음)
 */
data class ExploreResponse(
    val events: PageResponse<EventResponse>,
    val channels: PageResponse<ChannelResponse>,
)

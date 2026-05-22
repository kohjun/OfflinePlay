package com.contenido.domain.user.dto

/**
 * PR145 — 사용자 신뢰 요약. 기존 데이터를 집계해서 만든 lightweight snapshot.
 *
 *  - hostedEventCount         : 사용자가 owner 로 있는 채널이 만든 이벤트 수.
 *  - participatedEventCount   : 사용자가 EventParticipation 행을 가진 이벤트 수 (status 무관 — 신청 이력 전체).
 *  - checkedInCount           : 사용자의 티켓 중 status=USED 수.
 *  - reviewCount              : 사용자가 작성한 후기 수 (자동 숨김 포함).
 *  - averageEventRatingAsHost : 본인이 host 인 모든 이벤트의 후기 평균 별점. 후기 0건이면 null.
 *
 * 본 응답은 캐시 없이 매 호출마다 5개 query 로 계산한다 (참가/체크인 직후 즉시 갱신 보장).
 * 후속 PR 에서 동시 read 가 많아지면 redis 캐시 또는 materialized view 로 이관.
 */
data class TrustSummaryResponse(
    val userId: Long,
    val hostedEventCount: Long,
    val participatedEventCount: Long,
    val checkedInCount: Long,
    val reviewCount: Long,
    val averageEventRatingAsHost: Double?,
)

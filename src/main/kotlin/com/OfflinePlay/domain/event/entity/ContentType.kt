package com.contenido.domain.event.entity

/**
 * 이벤트(콘텐츠)의 유형. 홈 화면 콘텐츠 유형 섹션과 매핑된다.
 *
 *  - ORIGINAL  : Contenido만의 콘텐츠 — 자체 기획·제작 예능
 *  - CLASSIC   : 누구나 아는 콘텐츠 — 기성 IP를 활용한 이벤트
 *  - SPECIAL   : 새롭게 기획한 예능 — 시즌·콜라보 등 특별 기획
 *
 * 추후 MongoDB로 이벤트 도메인을 분리할 때 그대로 이전 가능한 단순 enum.
 */
enum class ContentType(val displayName: String) {
    ORIGINAL("Original"),
    CLASSIC("Classic"),
    SPECIAL("Special"),
}

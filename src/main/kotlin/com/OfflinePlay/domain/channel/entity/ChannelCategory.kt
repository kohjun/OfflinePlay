package com.contenido.domain.channel.entity

/**
 * CONTENIDO 콘텐츠 카테고리.
 *
 * 9종으로 통일 — 프론트 홈 화면의 "인기 카테고리 3x3" 그리드와 일대일 매핑된다.
 * (TRAVEL, LOVE, RACE, PSYCHOLOGICAL, SURVIVAL, MUSIC, SPORTS, COOKING, PARTY)
 *
 * 과거 CHASE("추격전") 카테고리는 SURVIVAL("서바이벌")로 합쳐졌다. 기존 데이터가
 * 남아 있을 경우 DB 마이그레이션에서 CHASE → SURVIVAL 로 일괄 갱신해야 한다.
 */
enum class ChannelCategory(val displayName: String) {
    TRAVEL("여행"),
    LOVE("연애"),
    RACE("레이스"),
    PSYCHOLOGICAL("심리추리"),
    SURVIVAL("서바이벌"),
    MUSIC("음악"),
    SPORTS("스포츠"),
    COOKING("요리"),
    PARTY("파티"),
}

package com.contenido.domain.payment.entity

/**
 * 결제 PG provider.
 *
 *  - NONE     : PR39 단계 — 실제 PG 호출 없이 도메인 뼈대만. 통합 테스트와 webhook 시뮬레이션에 사용.
 *  - TOSS     : Toss Payments
 *  - PORTONE  : 포트원 (구 아임포트)
 *
 * 새 PG 가 추가되면 여기에 값을 더한다. provider 별 호출 SDK 는 후속 PR 에서
 * 별도 어댑터(`PaymentGateway` 인터페이스)로 분리할 예정.
 */
enum class PaymentProvider {
    NONE, TOSS, PORTONE,
}

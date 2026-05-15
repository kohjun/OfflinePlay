import type { ReactNode } from 'react'

interface BottomCtaDockProps {
  /** dock 안에 들어갈 액션(주로 1개 또는 2개의 button). */
  children: ReactNode
}

/**
 * 화면 하단 thumb zone 에 고정되는 CTA dock — 핸드오프 README 공통 컴포넌트.
 *
 *  - sticky bottom, 12px 16px 패딩 + safe-area-inset-bottom 반영
 *  - 상단 1px --c-border 보더 + bg --c-surface
 *  - 내부 액션은 보통 full-block 56h primary 버튼
 *
 * 사용처: EventDetailPage("티켓 구매하기"), PaymentPage("결제하기"),
 * Confirm Bottom Sheet 등.
 */
export function BottomCtaDock({ children }: BottomCtaDockProps) {
  return <nav className="cta-dock">{children}</nav>
}

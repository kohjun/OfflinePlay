import type { TrustSummaryResponse } from '../api/users'
import { Badge } from './Badge'

interface TrustChipsProps {
  summary: TrustSummaryResponse
  /** 'compact' 는 1-2 chip 만, 'full' 은 모든 카운트. */
  variant?: 'compact' | 'full'
}

/**
 * PR145 — 사용자 신뢰 요약 chip 묶음.
 *
 *  - compact: 의미 있는 행동(기획 3+ / 체크인 5+ / 평균 4.0+) 중심으로 1-2개 chip.
 *  - full   : 카드 안에서 모든 카운트를 행으로 표시.
 *
 * 모든 chip 은 0/null 값이면 자동 hide — 신규 사용자에게는 빈 영역이 보이지 않는다.
 */
export function TrustChips({ summary, variant = 'compact' }: TrustChipsProps) {
  const {
    hostedEventCount,
    participatedEventCount,
    checkedInCount,
    reviewCount,
    averageEventRatingAsHost,
  } = summary

  if (variant === 'full') {
    return (
      <ul className="trust-chips trust-chips--full" aria-label="신뢰 요약">
        {hostedEventCount > 0 ? (
          <li><span className="muted">기획</span><strong>{hostedEventCount}회</strong></li>
        ) : null}
        {participatedEventCount > 0 ? (
          <li><span className="muted">참가</span><strong>{participatedEventCount}회</strong></li>
        ) : null}
        {checkedInCount > 0 ? (
          <li><span className="muted">체크인</span><strong>{checkedInCount}회</strong></li>
        ) : null}
        {reviewCount > 0 ? (
          <li><span className="muted">후기</span><strong>{reviewCount}건</strong></li>
        ) : null}
        {averageEventRatingAsHost != null ? (
          <li>
            <span className="muted">host 평균</span>
            <strong>{averageEventRatingAsHost.toFixed(1)}</strong>
          </li>
        ) : null}
      </ul>
    )
  }

  // compact: 의미 있는 chip 1-2개만.
  const chips: { key: string; label: string; tone: 'primary' | 'success' | 'neutral' }[] = []
  if (hostedEventCount >= 3) {
    chips.push({ key: 'hosted', label: `기획 ${hostedEventCount}회`, tone: 'primary' })
  }
  if (averageEventRatingAsHost != null && averageEventRatingAsHost >= 4.0 && hostedEventCount > 0) {
    chips.push({ key: 'rating', label: `★ ${averageEventRatingAsHost.toFixed(1)}`, tone: 'success' })
  }
  if (chips.length === 0 && checkedInCount >= 5) {
    chips.push({ key: 'checkin', label: `체크인 ${checkedInCount}회`, tone: 'neutral' })
  }
  if (chips.length === 0) return null

  return (
    <span className="trust-chips" aria-label="신뢰 요약">
      {chips.map((c) => (
        <Badge key={c.key} tone={c.tone}>{c.label}</Badge>
      ))}
    </span>
  )
}

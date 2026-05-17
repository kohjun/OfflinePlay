interface RemainingProgressProps {
  /** 남은 자리. 0이면 마감. */
  remaining: number
  /** 정원. */
  capacity: number
  /** 라벨 표시 여부 (기본 true). false 면 progress bar 만 노출. */
  showLabel?: boolean
  /**
   * PR91 — true 면 잔여 숫자/바에 짧은 highlight 효과 (pulse + fade) 가 켜진다.
   * 부모가 event refetch 후 값이 바뀌었을 때 1.2~1.8 초 정도 toggle 하는 용도.
   * 기본 false — 기존 호출처는 깨지지 않는다.
   */
  highlight?: boolean
  /**
   * PR91 — aria-live region 의 screen reader 라벨. 비어 있으면 기본 "잔여 N자리" 사용.
   */
  liveLabel?: string
}

/**
 * "잔여 X / N" + 3px progress bar — 핸드오프 EventCard / EventDetail 공통.
 *
 *  - 텍스트: 700 12px, 잔여 숫자는 700 13px coral
 *  - 바: 3px height, track --c-surface-2, fill --c-primary
 *  - 진척률 = (capacity - remaining) / capacity
 *  - 잔여가 25% 이하면 자동으로 "마감 임박" 라벨이 표시되도록 부모가 라벨 추가 가능
 *  - 잔여 숫자의 React key 를 remaining 값으로 둬서, 값이 바뀔 때마다 새 노드가
 *    마운트되며 fade-in 220ms 가 자동으로 트리거된다 (PR50, SSE refetch 후 변화 강조).
 *  - PR91 — `highlight` 가 true 면 잔여 숫자 + 바에 짧은 pulse 가 추가로 켜진다.
 *    실시간성을 약간 더 살리기 위한 보조 효과로, prefers-reduced-motion 환경에선
 *    CSS 단에서 자동 비활성화된다.
 */
export function RemainingProgress({
  remaining,
  capacity,
  showLabel = true,
  highlight = false,
  liveLabel,
}: RemainingProgressProps) {
  const safeCap = Math.max(1, capacity)
  const safeRemaining = Math.max(0, remaining)
  const filledPercent = Math.min(100, Math.max(0, ((safeCap - safeRemaining) / safeCap) * 100))
  const ariaLabel = liveLabel ?? `잔여 ${safeRemaining}자리`

  return (
    <div className={`rp${highlight ? ' is-live-update' : ''}`}>
      {showLabel ? (
        <span className="rp__label" aria-live="polite" aria-atomic="true" aria-label={ariaLabel}>
          잔여 <b key={safeRemaining} className="rp__num">{safeRemaining}</b> / {capacity}
        </span>
      ) : null}
      <span
        className="rp__bar"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={capacity}
        aria-valuenow={Math.max(0, capacity - remaining)}
        aria-label={`잔여 ${remaining}자리, 총 ${capacity}자리`}
      >
        <span className="rp__bar__fill" style={{ width: `${filledPercent}%` }} aria-hidden="true" />
      </span>
    </div>
  )
}

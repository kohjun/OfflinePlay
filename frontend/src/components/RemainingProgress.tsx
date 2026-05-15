interface RemainingProgressProps {
  /** 남은 자리. 0이면 마감. */
  remaining: number
  /** 정원. */
  capacity: number
  /** 라벨 표시 여부 (기본 true). false 면 progress bar 만 노출. */
  showLabel?: boolean
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
 */
export function RemainingProgress({ remaining, capacity, showLabel = true }: RemainingProgressProps) {
  const safeCap = Math.max(1, capacity)
  const safeRemaining = Math.max(0, remaining)
  const filledPercent = Math.min(100, Math.max(0, ((safeCap - safeRemaining) / safeCap) * 100))

  return (
    <div className="rp">
      {showLabel ? (
        <span className="rp__label">
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

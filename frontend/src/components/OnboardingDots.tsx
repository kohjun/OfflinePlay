interface OnboardingDotsProps {
  count: number
  active: number
}

/**
 * Onboarding 진행 dots — 핸드오프 화면 01.
 *
 *  - 활성: `--c-primary` × 18px 폭
 *  - 비활성: `--c-border-strong` × 6px 폭
 *  - 높이 6px / pill / 6px gap, 220ms ease transition (활성 셀이 늘어나는 모션)
 */
export function OnboardingDots({ count, active }: OnboardingDotsProps) {
  return (
    <div
      className="ob-dots"
      role="tablist"
      aria-label="온보딩 진행"
      aria-valuemin={1}
      aria-valuemax={count}
      aria-valuenow={active + 1}
    >
      {Array.from({ length: count }).map((_, idx) => (
        <span
          key={idx}
          className={`ob-dot${idx === active ? ' ob-dot--active' : ''}`}
          aria-current={idx === active ? 'step' : undefined}
        />
      ))}
    </div>
  )
}

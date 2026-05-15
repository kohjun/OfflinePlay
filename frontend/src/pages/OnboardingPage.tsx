import { useState } from 'react'
import { OnboardingDots } from '../components/OnboardingDots'

interface OnboardingPageProps {
  onStart: () => void
  onLogin: () => void
}

/**
 * 화면 01 — Onboarding / Splash.
 *
 * 비인증 사용자가 앱에 처음 진입할 때 노출. localStorage `contenido-onboarded` 플래그가
 * 없을 때만 표시되며, "시작하기" 또는 "로그인" 클릭 시 부모(App)가 플래그를 set 하고
 * 로그인 화면으로 이동시킨다.
 *
 * 핸드오프 README §01 명세 그대로:
 *  - 그라디언트 배경 `linear-gradient(180deg, #FFFFFF 0%, #FFF5F5 100%)`
 *  - 로고 영역: "CONTENIDO" 800 32px coral + 우하단 정렬 "by WOYA"
 *  - 히어로 영역: flex:1, 라운드 20px, 그라디언트 카드 + 떠있는 카테고리 칩 3개
 *  - 카피: dots indicator(3개) + h1 28px + body
 *  - 하단 CTA dock: "시작하기" full-block + "이미 계정이 있어요 · 로그인" 보조 링크
 *
 * 3 스텝 캐러셀(카피 회전)은 후속 PR — 지금은 첫 스텝만 정적으로 노출.
 */

interface StepCopy {
  title: string
  body: string
  chips: { emoji: string; label: string }[]
}

const STEPS: StepCopy[] = [
  {
    title: '화면 밖으로 나온 예능,\n오늘 밤 함께 해요',
    body: '9개 카테고리, 1,200여 명의 크리에이터.\n가까운 도시에서 열리는 오프라인 이벤트에 참가해보세요.',
    chips: [
      { emoji: '🏔️', label: '여행' },
      { emoji: '🎸', label: '음악' },
      { emoji: '⛺', label: '서바이벌' },
    ],
  },
  {
    title: '취향대로 채널을 구독하고\n새 이벤트를 가장 먼저',
    body: '관심 카테고리·크리에이터를 팔로우해두면\n새 이벤트가 열릴 때 푸시로 알려드려요.',
    chips: [
      { emoji: '💗', label: '연애' },
      { emoji: '🔍', label: '심리추리' },
      { emoji: '🎉', label: '파티' },
    ],
  },
  {
    title: 'QR 한 번이면\n현장 입장 완료',
    body: '결제부터 환불·체크인까지 앱 안에서.\n주최자도 운영 도구로 깔끔하게 관리해요.',
    chips: [
      { emoji: '🏁', label: '레이스' },
      { emoji: '⚽', label: '스포츠' },
      { emoji: '👨‍🍳', label: '요리' },
    ],
  },
]

export function OnboardingPage({ onStart, onLogin }: OnboardingPageProps) {
  const [step, setStep] = useState(0)
  const current = STEPS[step]

  return (
    <main className="ob-page">
      <header className="ob-brand">
        <strong className="ob-brand__name">CONTENIDO</strong>
        <span className="ob-brand__by">by WOYA</span>
      </header>

      <section className="ob-hero" aria-hidden="true">
        <div className="ob-hero__chips">
          {current.chips.map((chip) => (
            <span key={chip.label} className="ob-hero__chip">
              <span aria-hidden="true">{chip.emoji}</span>
              {chip.label}
            </span>
          ))}
        </div>
      </section>

      <section className="ob-copy">
        <OnboardingDots count={STEPS.length} active={step} />
        <h1 className="ob-copy__title">{current.title}</h1>
        <p className="ob-copy__body">{current.body}</p>
      </section>

      <nav className="ob-cta-dock" aria-label="시작하기">
        {step < STEPS.length - 1 ? (
          <button
            type="button"
            className="button button-primary is-block"
            onClick={() => setStep((s) => s + 1)}
          >
            다음
          </button>
        ) : (
          <button
            type="button"
            className="button button-primary is-block"
            onClick={onStart}
          >
            시작하기
          </button>
        )}
        <button type="button" className="ob-cta-dock__login" onClick={onLogin}>
          이미 계정이 있어요 · <em>로그인</em>
        </button>
      </nav>
    </main>
  )
}

import { FormEvent, useState, type ReactNode } from 'react'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { SignupRole } from '../types'

interface LoginPageProps {
  onDone: () => void
}

// Backend Pattern: ^01[016789]-?\d{3,4}-?\d{4}$ — accepts 01x with or without dashes.
const PHONE_PATTERN = '^01[016789]-?\\d{3,4}-?\\d{4}$'

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

interface RoleOption {
  value: SignupRole
  label: string
  description: string
  icon: ReactNode
}

const ROLE_OPTIONS: RoleOption[] = [
  {
    value: 'PARTICIPANT',
    label: '참가자로 시작하기',
    description: '이벤트를 둘러보고 참여합니다',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <circle cx="12" cy="9" r="3.4" />
        <path d="M5 19.4a7 7 0 0 1 14 0" />
      </svg>
    ),
  },
  {
    value: 'CREATOR',
    label: '기획자로 시작하기',
    description: '채널을 만들고 이벤트를 기획합니다',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M5 6.5h14" />
        <path d="M5 12h14" />
        <path d="M5 17.5h9" />
        <circle cx="17.5" cy="17.5" r="2.4" fill="currentColor" stroke="none" />
      </svg>
    ),
  },
]

/**
 * 화면 02 — 로그인 / 회원가입 (핸드오프 README §02).
 *
 * wireframe 은 "로그인" 만 다루지만 백엔드 가입 흐름이 필요하므로 segmented control 로
 * 로그인/회원가입 모드를 그대로 유지한다. 톤·간격·소셜 버튼은 wireframe 02 그대로.
 *
 * 소셜 로그인 3종(카카오/네이버/애플) 은 시각적으로 노출하되 클릭 시 "준비 중" 토스트 —
 * 실제 OAuth 연동은 별도 PR.
 */
export function LoginPage({ onDone }: LoginPageProps) {
  const { login, signup } = useAuth()
  const { showToast } = useToast()
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [nickname, setNickname] = useState('')
  const [phoneNumber, setPhoneNumber] = useState('')
  const [role, setRole] = useState<SignupRole>('PARTICIPANT')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)

    try {
      if (mode === 'login') {
        await login({ email, password })
      } else {
        await signup({ email, password, nickname, phoneNumber, role })
      }
      showToast({ title: 'CONTENIDO에 오신 것을 환영합니다', tone: 'success' })
      onDone()
    } catch (error) {
      showToast({
        title: mode === 'login' ? '로그인 실패' : '회원가입 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  function notReady(provider: string) {
    showToast({
      title: `${provider} 로그인은 준비 중이에요`,
      message: '곧 만나요. 이메일로 먼저 시작해주세요.',
      tone: 'info',
    })
  }

  const submitLabel = submitting ? '처리 중...' : mode === 'login' ? '로그인' : '계정 만들기'

  return (
    <main className="auth-page">
      <header className="auth-page__head">
        <h1 className="auth-page__title">{mode === 'login' ? '반가워요 👋' : '환영해요 👋'}</h1>
        <p className="auth-page__subtitle">
          {mode === 'login'
            ? '이메일로 로그인하거나, 소셜 계정으로 빠르게 시작해보세요.'
            : '닉네임과 이메일만 있으면 바로 시작할 수 있어요.'}
        </p>
      </header>

      <div className="segmented" role="tablist" aria-label="인증 모드">
        <button
          className={mode === 'login' ? 'is-active' : ''}
          onClick={() => setMode('login')}
          type="button"
          role="tab"
          aria-selected={mode === 'login'}
        >
          로그인
        </button>
        <button
          className={mode === 'signup' ? 'is-active' : ''}
          onClick={() => setMode('signup')}
          type="button"
          role="tab"
          aria-selected={mode === 'signup'}
        >
          회원가입
        </button>
      </div>

      <form className="auth-form" onSubmit={handleSubmit}>
        {mode === 'signup' ? (
          <>
            <fieldset className="role-picker">
              <legend className="role-picker-legend">어떤 모드로 시작할까요?</legend>
              <div className="role-picker-grid">
                {ROLE_OPTIONS.map((opt) => (
                  <label
                    key={opt.value}
                    className={`role-option ${role === opt.value ? 'is-active' : ''}`}
                  >
                    <input
                      type="radio"
                      name="signup-role"
                      value={opt.value}
                      checked={role === opt.value}
                      onChange={() => setRole(opt.value)}
                    />
                    <span className="role-option-icon" aria-hidden="true">
                      {opt.icon}
                    </span>
                    <strong>{opt.label}</strong>
                    <span>{opt.description}</span>
                  </label>
                ))}
              </div>
            </fieldset>
            <label className="auth-form__field">
              <span>닉네임</span>
              <input
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                minLength={2}
                maxLength={20}
                required
                autoComplete="nickname"
                placeholder="2~20자"
              />
            </label>
            <label className="auth-form__field">
              <span>전화번호</span>
              <input
                type="tel"
                value={phoneNumber}
                onChange={(event) => setPhoneNumber(event.target.value)}
                placeholder="010-1234-5678"
                pattern={PHONE_PATTERN}
                required
                autoComplete="tel"
                inputMode="tel"
              />
            </label>
          </>
        ) : null}

        <label className="auth-form__field">
          <span>이메일</span>
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
            autoComplete={mode === 'login' ? 'username' : 'email'}
            inputMode="email"
            placeholder="you@example.com"
          />
        </label>

        <label className="auth-form__field">
          <span>비밀번호</span>
          <div className="password-field">
            <input
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              minLength={8}
              maxLength={20}
              required
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              placeholder="8~20자"
            />
            <button
              type="button"
              className="password-toggle"
              onClick={() => setShowPassword((prev) => !prev)}
              aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}
            >
              {showPassword ? '숨김' : '표시'}
            </button>
          </div>
        </label>

        {mode === 'login' ? (
          <div className="auth-form__options">
            <label className="auth-form__check">
              <input type="checkbox" defaultChecked />
              <span>자동 로그인</span>
            </label>
            <button
              type="button"
              className="auth-form__link"
              onClick={() => notReady('비밀번호 찾기')}
            >
              비밀번호 찾기 ›
            </button>
          </div>
        ) : null}

        <button
          className="button button-primary is-block auth-form__submit"
          disabled={submitting}
          type="submit"
          aria-busy={submitting}
        >
          {submitting ? <span className="button-spinner" aria-hidden="true" /> : null}
          {submitLabel}
        </button>
      </form>

      <div className="auth-divider" aria-hidden="true">
        <span>또는 소셜 계정으로</span>
      </div>

      <div className="auth-social">
        <button
          type="button"
          className="auth-social__btn auth-social__btn--kakao"
          onClick={() => notReady('카카오')}
        >
          <span aria-hidden="true">💬</span>
          카카오로 계속하기
        </button>
        <button
          type="button"
          className="auth-social__btn auth-social__btn--naver"
          onClick={() => notReady('네이버')}
        >
          <span aria-hidden="true">N</span>
          네이버로 계속하기
        </button>
        <button
          type="button"
          className="auth-social__btn auth-social__btn--apple"
          onClick={() => notReady('Apple')}
        >
          <span aria-hidden="true"></span>
          Apple로 계속하기
        </button>
      </div>

      <p className="auth-page__terms">
        계속하면 <a href="#terms">이용약관</a> · <a href="#privacy">개인정보 처리방침</a>에 동의한
        것으로 간주합니다.
      </p>
    </main>
  )
}

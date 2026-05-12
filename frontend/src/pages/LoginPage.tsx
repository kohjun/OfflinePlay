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
        // signup auto-logs in via useAuth → authApi.signup → login
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

  const submitLabel = submitting ? '처리 중...' : mode === 'login' ? '로그인' : '계정 만들기'

  return (
    <main className="auth-shell">
      <section className="auth-panel">
        <div>
          <p className="eyebrow">CONTENIDO</p>
          <h1>{mode === 'login' ? '로그인' : '회원가입'}</h1>
          <p className="subtle">
            기획자가 만든 이벤트에 참여하고, 채널을 구독해 새 콘텐츠 알림을 받아보세요.
          </p>
        </div>
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
        <form className="form-stack auth-form" onSubmit={handleSubmit}>
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
                      <span className="role-option-icon" aria-hidden="true">{opt.icon}</span>
                      <strong>{opt.label}</strong>
                      <span>{opt.description}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <label>
                닉네임
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
              <label>
                전화번호
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
          <label>
            이메일
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
          <label>
            비밀번호
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
          <button
            className="button button-primary is-block"
            disabled={submitting}
            type="submit"
            aria-busy={submitting}
          >
            {submitting ? <span className="button-spinner" aria-hidden="true" /> : null}
            {submitLabel}
          </button>
        </form>
      </section>
    </main>
  )
}

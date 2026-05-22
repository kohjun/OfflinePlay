import { useEffect, useState } from 'react'
import { getPublicProfile, type PublicProfileResponse } from '../api/users'
import { Skeleton } from '../components/Skeleton'
import { useToast } from '../hooks/useToast'
import type { UserRole } from '../types'

const ROLE_LABEL: Record<UserRole, string> = {
  PARTICIPANT: '참가자',
  CREATOR: '기획자',
  ADMIN: '관리자',
}

const VISIBILITY_NOTICE: Record<PublicProfileResponse['visibility'], string | null> = {
  PUBLIC: null,
  MEMBERS: null,
  PRIVATE: '이 프로필은 비공개로 설정되어 있어요. 닉네임만 공개됩니다.',
}

interface ProfileViewPageProps {
  userId: number
  onNavigate: (path: string) => void
}

/**
 * PR144 — 다른 사용자의 공개 프로필. visibility=PRIVATE 면 nickname/role/joinedAt 만 노출.
 *
 * 추후 PR145 trust summary chip / PR146 manner summary / PR147 interest 표시를 본 페이지에 얹는다.
 */
export function ProfileViewPage({ userId, onNavigate }: ProfileViewPageProps) {
  const { showToast } = useToast()
  const [profile, setProfile] = useState<PublicProfileResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    getPublicProfile(userId)
      .then((res) => {
        if (alive) setProfile(res)
      })
      .catch((err) => {
        if (!alive) return
        const message = err instanceof Error ? err.message : '프로필을 불러오지 못했어요.'
        setError(message)
        showToast({ title: '프로필을 불러오지 못했어요', message, tone: 'warning' })
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [userId, showToast])

  if (loading) {
    return (
      <main className="page">
        <Skeleton lines={4} />
      </main>
    )
  }

  if (error || !profile) {
    return (
      <main className="page empty-state">
        <h1>프로필을 찾을 수 없어요</h1>
        <p className="muted">{error ?? '삭제되었거나 잘못된 링크일 수 있어요.'}</p>
        <button className="button button-primary is-block" onClick={() => onNavigate('/')} type="button">
          홈으로
        </button>
      </main>
    )
  }

  const roleLabel = ROLE_LABEL[profile.role] ?? '참가자'
  const visibilityNotice = VISIBILITY_NOTICE[profile.visibility]
  const region = [profile.regionSido, profile.regionSigungu].filter(Boolean).join(' ')

  return (
    <main className="page ct-profile-view">
      <section className="ct-my-hero">
        <div className="ct-my-avatar" aria-hidden="true">
          {profile.avatarUrl ? (
            <img src={profile.avatarUrl} alt="" />
          ) : (
            profile.nickname.slice(0, 1).toUpperCase()
          )}
        </div>
        <div className="ct-my-meta">
          <strong>{profile.nickname}</strong>
          <span className="muted">
            {roleLabel} · 가입 {new Date(profile.joinedAt).toLocaleDateString()}
          </span>
        </div>
      </section>

      {visibilityNotice ? (
        <p className="muted">{visibilityNotice}</p>
      ) : (
        <>
          {profile.bio ? (
            <section className="form-section" aria-label="자기소개">
              <h2>자기소개</h2>
              <p className="ct-event-section-text">{profile.bio}</p>
            </section>
          ) : null}
          {region ? (
            <section className="form-section" aria-label="지역">
              <h2>활동 지역</h2>
              <p>{region}</p>
            </section>
          ) : null}
        </>
      )}
    </main>
  )
}

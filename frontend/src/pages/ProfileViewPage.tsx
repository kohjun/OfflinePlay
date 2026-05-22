import { useEffect, useState } from 'react'
import {
  getMannerSummary,
  getPublicProfile,
  getTrustSummary,
  type MannerSummaryResponse,
  type PublicProfileResponse,
  type TrustSummaryResponse,
} from '../api/users'
import { Skeleton } from '../components/Skeleton'
import { TrustChips } from '../components/TrustChips'
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

/** PR146 — MannerFeedbackForm 의 태그 set 과 동일. 본 페이지는 표시 전용 라벨 매핑. */
const MANNER_TAG_LABEL: Record<string, string> = {
  FRIENDLY: '친절해요',
  PUNCTUAL: '시간 약속을 잘 지켜요',
  POLITE: '매너가 좋아요',
  COMMUNICATIVE: '소통이 원활해요',
  PREPARED: '준비가 철저해요',
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
  const [trust, setTrust] = useState<TrustSummaryResponse | null>(null)
  const [manner, setManner] = useState<MannerSummaryResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    // 프로필 + 신뢰 + 매너 요약을 병렬로 fetch. 신뢰/매너는 실패해도 프로필 노출 유지 (best-effort).
    Promise.all([
      getPublicProfile(userId),
      getTrustSummary(userId).catch(() => null),
      getMannerSummary(userId).catch(() => null),
    ])
      .then(([profileRes, trustRes, mannerRes]) => {
        if (!alive) return
        setProfile(profileRes)
        setTrust(trustRes)
        setManner(mannerRes)
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

      {trust ? (
        <section className="form-section" aria-label="신뢰 요약">
          <h2>활동 요약</h2>
          <TrustChips summary={trust} variant="full" />
        </section>
      ) : null}

      <section className="form-section" aria-label="매너 평가">
        <h2>매너 평가</h2>
        {manner ? (
          <div className="stack">
            <strong>
              ★ {manner.averageRating.toFixed(1)} <span className="muted">({manner.count}건)</span>
            </strong>
            {manner.topTags.length > 0 ? (
              <ul className="manner-tag-list">
                {manner.topTags.map((slug) => (
                  <li key={slug}>{MANNER_TAG_LABEL[slug] ?? slug}</li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : (
          <p className="muted">평가 데이터가 아직 부족해요. 함께한 이벤트가 끝나면 다른 참가자/호스트가 평가를 남길 수 있어요.</p>
        )}
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

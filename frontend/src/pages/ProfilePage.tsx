import { FormEvent, useEffect, useRef, useState } from 'react'
import {
  changeMyPassword,
  getMyExtendedProfile,
  updateMyExtendedProfile,
  updateMyProfile,
  type MyProfileResponse,
  type ProfileVisibility,
} from '../api/users'
import { uploadFile } from '../api/files'
import { getMyInterests, updateMyInterests } from '../api/taxonomy'
import { ApiError } from '../api/client'
import { InterestPicker } from '../components/InterestPicker'
import { RegionPicker } from '../components/RegionPicker'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import { authStore } from '../stores/authStore'
import type { UserRole } from '../types'

interface ProfilePageProps {
  onNavigate: (path: string) => void
}

const ROLE_LABEL: Record<UserRole, string> = {
  PARTICIPANT: '참가자',
  CREATOR: '기획자',
  ADMIN: '관리자',
}

const PHONE_PATTERN = /^\d{10,11}$/

/**
 * 프로필 탭. 닉네임/전화번호 편집(PATCH /users/me) + 로그아웃.
 */
export function ProfilePage({ onNavigate }: ProfilePageProps) {
  const { user, logout } = useAuth()
  const { showToast } = useToast()
  const [editing, setEditing] = useState(false)
  const [nickname, setNickname] = useState('')
  const [phoneNumber, setPhoneNumber] = useState('')
  const [saving, setSaving] = useState(false)
  const [nicknameError, setNicknameError] = useState<string | null>(null)
  const [phoneError, setPhoneError] = useState<string | null>(null)

  // 비밀번호 변경 폼
  const [changingPassword, setChangingPassword] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showCurrent, setShowCurrent] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [savingPassword, setSavingPassword] = useState(false)
  const [newPasswordError, setNewPasswordError] = useState<string | null>(null)
  const [confirmPasswordError, setConfirmPasswordError] = useState<string | null>(null)
  const [currentPasswordError, setCurrentPasswordError] = useState<string | null>(null)

  // PR144 + PR147 — 확장 프로필 (bio / avatar / region / interests / visibility)
  const [extProfile, setExtProfile] = useState<MyProfileResponse | null>(null)
  const [extLoading, setExtLoading] = useState(false)
  const [editingExt, setEditingExt] = useState(false)
  const [extBio, setExtBio] = useState('')
  const [extAvatarUrl, setExtAvatarUrl] = useState('')
  const [extRegionCode, setExtRegionCode] = useState<string | null>(null)
  const [extVisibility, setExtVisibility] = useState<ProfileVisibility>('PUBLIC')
  const [extInterestIds, setExtInterestIds] = useState<number[]>([])
  const [savingExt, setSavingExt] = useState(false)
  // PR159 — avatar 파일 업로드 (URL 직접 입력 대체)
  const [uploadingAvatar, setUploadingAvatar] = useState(false)
  const avatarInputRef = useRef<HTMLInputElement | null>(null)

  async function handleAvatarPick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setUploadingAvatar(true)
    try {
      const uploaded = await uploadFile(file, 'PROFILE')
      setExtAvatarUrl(uploaded.url)
    } catch (err) {
      showToast({
        title: '이미지 업로드에 실패했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setUploadingAvatar(false)
    }
  }

  useEffect(() => {
    if (!user) return
    setExtLoading(true)
    getMyExtendedProfile()
      .then((res) => setExtProfile(res))
      .catch(() => {
        // 조용히 실패 — section 자체가 안 보이게.
        setExtProfile(null)
      })
      .finally(() => setExtLoading(false))
  }, [user])

  function startEditExt() {
    setExtBio(extProfile?.bio ?? '')
    setExtAvatarUrl(extProfile?.avatarUrl ?? '')
    setExtRegionCode(extProfile?.regionCode ?? null)
    setExtVisibility(extProfile?.visibility ?? 'PUBLIC')
    setExtInterestIds(extProfile?.interests?.map((i) => i.id) ?? [])
    setEditingExt(true)
  }

  function cancelEditExt() {
    setEditingExt(false)
    setSavingExt(false)
  }

  async function handleSubmitExt(event: FormEvent) {
    event.preventDefault()
    if (savingExt) return
    setSavingExt(true)
    try {
      // 프로필 본체 갱신 + 관심사 갱신을 한 호출로 합치지 않고 두 endpoint 호출. interests 가 실패해도
      // bio/avatar/region 은 저장된 상태로 toast 안내.
      const updated = await updateMyExtendedProfile({
        bio: extBio,
        avatarUrl: extAvatarUrl,
        regionCode: extRegionCode ?? '',
        visibility: extVisibility,
      })
      const interestsUpdated = await updateMyInterests(extInterestIds)
      setExtProfile({ ...updated, interests: interestsUpdated })
      showToast({ title: '프로필이 저장되었어요', tone: 'success' })
      setEditingExt(false)
    } catch (error) {
      showToast({
        title: '프로필 저장에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSavingExt(false)
    }
  }

  if (!user) {
    return (
      <main className="page empty-state">
        <h1>로그인 정보를 불러올 수 없습니다</h1>
        <button
          type="button"
          className="button button-primary"
          onClick={() => onNavigate('/')}
        >
          홈으로
        </button>
      </main>
    )
  }

  const roleLabel = ROLE_LABEL[user.role] ?? '참가자'

  function startEdit() {
    if (!user) return
    setNickname(user.nickname)
    setPhoneNumber(user.phoneNumber)
    setNicknameError(null)
    setPhoneError(null)
    setEditing(true)
  }

  function cancelEdit() {
    setEditing(false)
    setSaving(false)
    setNicknameError(null)
    setPhoneError(null)
  }

  function validate(): boolean {
    let ok = true
    const trimmedNick = nickname.trim()
    if (trimmedNick.length < 2 || trimmedNick.length > 20) {
      setNicknameError('닉네임은 2~20자여야 합니다.')
      ok = false
    } else {
      setNicknameError(null)
    }
    if (!PHONE_PATTERN.test(phoneNumber)) {
      setPhoneError('전화번호는 숫자 10~11자리여야 합니다.')
      ok = false
    } else {
      setPhoneError(null)
    }
    return ok
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving || !user) return
    if (!validate()) return

    const trimmedNick = nickname.trim()
    // 변경 없으면 그냥 닫는다.
    if (trimmedNick === user.nickname && phoneNumber === user.phoneNumber) {
      setEditing(false)
      return
    }

    setSaving(true)
    try {
      const updated = await updateMyProfile({
        nickname: trimmedNick !== user.nickname ? trimmedNick : undefined,
        phoneNumber: phoneNumber !== user.phoneNumber ? phoneNumber : undefined,
      })
      authStore.setUser(updated)
      showToast({ title: '프로필이 수정되었어요', tone: 'success' })
      setEditing(false)
    } catch (error) {
      showToast({
        title: '프로필 저장 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSaving(false)
    }
  }

  function openPasswordForm() {
    setCurrentPassword('')
    setNewPassword('')
    setConfirmPassword('')
    setShowCurrent(false)
    setShowNew(false)
    setNewPasswordError(null)
    setConfirmPasswordError(null)
    setCurrentPasswordError(null)
    setChangingPassword(true)
  }

  function cancelPasswordForm() {
    setChangingPassword(false)
    setSavingPassword(false)
    setCurrentPassword('')
    setNewPassword('')
    setConfirmPassword('')
    setNewPasswordError(null)
    setConfirmPasswordError(null)
    setCurrentPasswordError(null)
  }

  function validatePassword(): boolean {
    let ok = true
    setCurrentPasswordError(null)
    if (!currentPassword) {
      setCurrentPasswordError('현재 비밀번호를 입력해주세요.')
      ok = false
    }
    if (newPassword.length < 8 || newPassword.length > 20) {
      setNewPasswordError('새 비밀번호는 8~20자여야 합니다.')
      ok = false
    } else {
      setNewPasswordError(null)
    }
    if (newPassword !== confirmPassword) {
      setConfirmPasswordError('새 비밀번호가 일치하지 않아요.')
      ok = false
    } else {
      setConfirmPasswordError(null)
    }
    return ok
  }

  async function handleSubmitPassword(event: FormEvent) {
    event.preventDefault()
    if (savingPassword) return
    if (!validatePassword()) return

    setSavingPassword(true)
    try {
      await changeMyPassword({ currentPassword, newPassword })
      // 서버가 refresh token 을 무효화했으므로 로컬도 로그아웃 처리하고 로그인 화면으로 이동.
      showToast({
        title: '비밀번호가 변경되었어요',
        message: '보안을 위해 다시 로그인해주세요.',
        tone: 'success',
      })
      cancelPasswordForm()
      try {
        await logout()
      } finally {
        onNavigate('/')
      }
      return
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        // 현재 비밀번호 불일치
        setCurrentPasswordError('현재 비밀번호가 일치하지 않아요.')
      } else {
        showToast({
          title: '비밀번호 변경 실패',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      }
    } finally {
      setSavingPassword(false)
    }
  }

  async function handleLogout() {
    await logout()
    showToast({ title: '로그아웃되었습니다', tone: 'success' })
    onNavigate('/')
  }

  return (
    <main className="page ct-profile-page">
      <section className="ct-my-hero">
        <div className="ct-my-avatar" aria-hidden="true">
          {user.nickname.slice(0, 1).toUpperCase()}
        </div>
        <div className="ct-my-meta">
          <strong>{user.nickname}</strong>
          <span className="muted">{roleLabel} · {user.email}</span>
        </div>
      </section>

      {editing ? (
        <section className="form-section" aria-label="프로필 편집">
          <h2>프로필 편집</h2>
          <form className="form-stack" onSubmit={handleSubmit} noValidate>
            <label>
              닉네임
              <input
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                autoComplete="nickname"
                maxLength={20}
                required
                aria-invalid={nicknameError ? true : undefined}
              />
              {nicknameError ? <span className="ct-form-error">{nicknameError}</span> : null}
            </label>
            <label>
              전화번호
              <input
                value={phoneNumber}
                onChange={(e) => setPhoneNumber(e.target.value.replace(/\D/g, ''))}
                inputMode="tel"
                autoComplete="tel"
                maxLength={11}
                placeholder="01012345678"
                required
                aria-invalid={phoneError ? true : undefined}
              />
              {phoneError ? <span className="ct-form-error">{phoneError}</span> : null}
            </label>
            <div className="ct-profile-edit-actions">
              <button
                type="button"
                className="button button-secondary"
                onClick={cancelEdit}
                disabled={saving}
              >
                취소
              </button>
              <button
                type="submit"
                className="button button-primary"
                disabled={saving}
                aria-busy={saving}
              >
                {saving ? <span className="button-spinner" aria-hidden="true" /> : null}
                {saving ? '저장 중...' : '저장'}
              </button>
            </div>
          </form>
        </section>
      ) : (
        <>
          <section className="form-section" aria-label="계정 정보">
            <div className="ct-profile-row">
              <span>이메일</span>
              <strong>{user.email}</strong>
            </div>
            <div className="ct-profile-row">
              <span>닉네임</span>
              <strong>{user.nickname}</strong>
            </div>
            <div className="ct-profile-row">
              <span>전화번호</span>
              <strong>{user.phoneNumber}</strong>
            </div>
            <div className="ct-profile-row">
              <span>역할</span>
              <strong>{roleLabel}</strong>
            </div>
            <div className="ct-profile-row">
              <span>가입일</span>
              <strong>{new Date(user.createdAt).toLocaleDateString()}</strong>
            </div>
          </section>

          <button
            type="button"
            className="card action-card"
            onClick={startEdit}
          >
            <strong>프로필 편집</strong>
            <span className="muted">닉네임과 전화번호를 수정할 수 있어요.</span>
          </button>

          {!changingPassword ? (
            <button
              type="button"
              className="card action-card"
              onClick={openPasswordForm}
            >
              <strong>비밀번호 변경</strong>
              <span className="muted">8~20자 새 비밀번호로 보안을 갱신해요.</span>
            </button>
          ) : null}
        </>
      )}

      {changingPassword ? (
        <section className="form-section" aria-label="비밀번호 변경">
          <h2>비밀번호 변경</h2>
          <form className="form-stack" onSubmit={handleSubmitPassword} noValidate>
            <label>
              현재 비밀번호
              <div className="ct-password-input">
                <input
                  type={showCurrent ? 'text' : 'password'}
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                  aria-invalid={currentPasswordError ? true : undefined}
                />
                <button
                  type="button"
                  className="ct-password-toggle"
                  onClick={() => setShowCurrent((v) => !v)}
                  aria-label={showCurrent ? '비밀번호 숨기기' : '비밀번호 표시'}
                  tabIndex={-1}
                >
                  {showCurrent ? '숨김' : '표시'}
                </button>
              </div>
              {currentPasswordError ? (
                <span className="ct-form-error">{currentPasswordError}</span>
              ) : null}
            </label>
            <label>
              새 비밀번호
              <div className="ct-password-input">
                <input
                  type={showNew ? 'text' : 'password'}
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  maxLength={20}
                  required
                  aria-invalid={newPasswordError ? true : undefined}
                />
                <button
                  type="button"
                  className="ct-password-toggle"
                  onClick={() => setShowNew((v) => !v)}
                  aria-label={showNew ? '비밀번호 숨기기' : '비밀번호 표시'}
                  tabIndex={-1}
                >
                  {showNew ? '숨김' : '표시'}
                </button>
              </div>
              {newPasswordError ? (
                <span className="ct-form-error">{newPasswordError}</span>
              ) : null}
            </label>
            <label>
              새 비밀번호 확인
              <input
                type={showNew ? 'text' : 'password'}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                autoComplete="new-password"
                maxLength={20}
                required
                aria-invalid={confirmPasswordError ? true : undefined}
              />
              {confirmPasswordError ? (
                <span className="ct-form-error">{confirmPasswordError}</span>
              ) : null}
            </label>
            <div className="ct-profile-edit-actions">
              <button
                type="button"
                className="button button-secondary"
                onClick={cancelPasswordForm}
                disabled={savingPassword}
              >
                취소
              </button>
              <button
                type="submit"
                className="button button-primary"
                disabled={savingPassword}
                aria-busy={savingPassword}
              >
                {savingPassword ? <span className="button-spinner" aria-hidden="true" /> : null}
                {savingPassword ? '변경 중...' : '비밀번호 변경'}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {extLoading ? null : editingExt ? (
        <section className="form-section" aria-label="공개 프로필 편집">
          <h2>공개 프로필 편집</h2>
          <form className="form-stack" onSubmit={handleSubmitExt} noValidate>
            <label>
              자기소개
              <textarea
                value={extBio}
                onChange={(e) => setExtBio(e.target.value)}
                maxLength={500}
                rows={4}
                placeholder="다른 참가자에게 나를 소개해보세요. 비워두면 표시되지 않아요."
                disabled={savingExt}
              />
            </label>
            <div className="profile-avatar-upload">
              <strong>프로필 이미지</strong>
              <div className="profile-avatar-upload__row">
                <div className="profile-avatar-upload__preview" aria-hidden="true">
                  {extAvatarUrl ? (
                    <img src={extAvatarUrl} alt="" />
                  ) : (
                    <span>{user?.nickname?.slice(0, 1).toUpperCase() ?? '?'}</span>
                  )}
                </div>
                <div className="profile-avatar-upload__actions">
                  <button
                    type="button"
                    className="button button-secondary"
                    onClick={() => avatarInputRef.current?.click()}
                    disabled={savingExt || uploadingAvatar}
                    aria-busy={uploadingAvatar}
                  >
                    {uploadingAvatar ? '업로드 중…' : extAvatarUrl ? '이미지 변경' : '이미지 선택'}
                  </button>
                  {extAvatarUrl ? (
                    <button
                      type="button"
                      className="button button-tertiary"
                      onClick={() => setExtAvatarUrl('')}
                      disabled={savingExt || uploadingAvatar}
                    >
                      삭제
                    </button>
                  ) : null}
                </div>
                <input
                  ref={avatarInputRef}
                  type="file"
                  accept="image/*"
                  hidden
                  onChange={handleAvatarPick}
                />
              </div>
              <span className="muted">JPEG / PNG / WebP / GIF, 최대 10MB. 비워두면 닉네임 첫 글자가 표시돼요.</span>
            </div>
            <div>
              <strong>활동 지역</strong>
              <RegionPicker
                value={extRegionCode}
                onChange={(code) => setExtRegionCode(code)}
                disabled={savingExt}
              />
            </div>
            <div>
              <strong>관심사</strong>
              <InterestPicker
                value={extInterestIds}
                onChange={(ids) => setExtInterestIds(ids)}
                disabled={savingExt}
              />
            </div>
            <label>
              공개 범위
              <select
                value={extVisibility}
                onChange={(e) => setExtVisibility(e.target.value as ProfileVisibility)}
                disabled={savingExt}
              >
                <option value="PUBLIC">전체 공개</option>
                <option value="MEMBERS">로그인 사용자에게만</option>
                <option value="PRIVATE">비공개 (닉네임만 노출)</option>
              </select>
            </label>
            <div className="ct-profile-edit-actions">
              <button
                type="button"
                className="button button-secondary"
                onClick={cancelEditExt}
                disabled={savingExt}
              >
                취소
              </button>
              <button
                type="submit"
                className="button button-primary"
                disabled={savingExt}
                aria-busy={savingExt}
              >
                {savingExt ? '저장 중…' : '저장'}
              </button>
            </div>
          </form>
        </section>
      ) : (
        <button
          type="button"
          className="card action-card"
          onClick={startEditExt}
        >
          <strong>공개 프로필 편집</strong>
          <span className="muted">
            {extProfile?.bio
              ? extProfile.bio
              : '자기소개 / 활동 지역 / 공개 범위를 설정해 다른 참가자가 나를 알게 해주세요.'}
          </span>
        </button>
      )}

      <button
        type="button"
        className="button button-secondary is-block"
        onClick={handleLogout}
      >
        로그아웃
      </button>
    </main>
  )
}

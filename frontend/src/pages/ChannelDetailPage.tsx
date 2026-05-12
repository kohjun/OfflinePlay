import { FormEvent, useEffect, useState } from 'react'
import {
  createChannelPost,
  deleteChannelPost,
  getChannel,
  getChannelPosts,
  subscribeChannel,
  unsubscribeChannel,
  updateChannelPost,
} from '../api/channels'
import {
  addChannelStaff,
  listChannelMembers,
  removeChannelMember,
  type ChannelMember,
} from '../api/channelMembers'
import { ApiError } from '../api/client'
import { getEvents } from '../api/events'
import { Badge } from '../components/Badge'
import { EventCard } from '../components/EventCard'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { Channel, ChannelPost, Event } from '../types'

type ChannelTab = 'events' | 'posts'

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

function formatSubscribers(n: number) {
  if (n >= 10000) return `${(n / 10000).toFixed(n % 10000 === 0 ? 0 : 1)}만`
  if (n >= 1000) return `${(Math.floor(n / 100) / 10).toFixed(1)}천`
  return n.toLocaleString()
}

interface ChannelDetailPageProps {
  channelId: number
  onNavigate: (path: string) => void
}

export function ChannelDetailPage({ channelId, onNavigate }: ChannelDetailPageProps) {
  const { showToast } = useToast()
  const { user } = useAuth()
  const [channel, setChannel] = useState<Channel | null>(null)
  const [events, setEvents] = useState<Event[]>([])
  const [posts, setPosts] = useState<ChannelPost[]>([])
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState<ChannelTab>('events')
  const [submittingSubscribe, setSubmittingSubscribe] = useState(false)
  const [composingPost, setComposingPost] = useState(false)
  const [postTitle, setPostTitle] = useState('')
  const [postContent, setPostContent] = useState('')
  const [submittingPost, setSubmittingPost] = useState(false)

  // 운영팀(STAFF) 관리 — owner/admin 만 접근.
  const [members, setMembers] = useState<ChannelMember[] | null>(null)
  const [loadingMembers, setLoadingMembers] = useState(false)
  const [staffEmail, setStaffEmail] = useState('')
  const [addingStaff, setAddingStaff] = useState(false)
  const [removingMemberId, setRemovingMemberId] = useState<number | null>(null)

  // 공지 편집 상태 — owner/admin 만 접근.
  const [editingPostId, setEditingPostId] = useState<number | null>(null)
  const [editTitle, setEditTitle] = useState('')
  const [editContent, setEditContent] = useState('')
  const [savingEdit, setSavingEdit] = useState(false)
  const [deletingPostId, setDeletingPostId] = useState<number | null>(null)

  // 알림에서 ?tab=posts 또는 #posts 로 진입한 경우 공지 탭을 활성화한다.
  useEffect(() => {
    if (typeof window === 'undefined') return
    const search = new URLSearchParams(window.location.search)
    const wantsPosts = search.get('tab') === 'posts' || window.location.hash === '#posts'
    if (wantsPosts) setTab('posts')
    // 마운트 시 1회 — 사용자가 직접 탭을 바꾼 뒤 URL 이 그대로면 다시 덮어쓰지 않도록 deps 비움.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 탭 변경 시 URL 쿼리 동기화. events 는 기본이라 query 제거.
  function selectTab(next: ChannelTab) {
    setTab(next)
    if (typeof window === 'undefined') return
    const url = next === 'posts' ? `/channels/${channelId}?tab=posts` : `/channels/${channelId}`
    if (window.location.pathname + window.location.search !== url) {
      window.history.replaceState({}, '', url)
    }
  }

  useEffect(() => {
    let alive = true
    Promise.all([
      getChannel(channelId),
      getEvents(channelId, { size: 5 }),
      getChannelPosts(channelId, { size: 10 }),
    ])
      .then(([channelResult, eventPage, postPage]) => {
        if (!alive) return
        setChannel(channelResult)
        setEvents(eventPage.content)
        setPosts(postPage.content)
      })
      .catch((error) => {
        showToast({
          title: '채널을 불러오지 못했습니다',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      })
      .finally(() => {
        if (alive) setLoading(false)
      })

    return () => {
      alive = false
    }
  }, [channelId, showToast])

  async function handleSubscribe() {
    if (!channel || submittingSubscribe) return
    const wasSubscribed = channel.isSubscribed === true
    setSubmittingSubscribe(true)
    setChannel({
      ...channel,
      isSubscribed: !wasSubscribed,
      subscriberCount: channel.subscriberCount + (wasSubscribed ? -1 : 1),
    })
    try {
      if (wasSubscribed) await unsubscribeChannel(channel.id)
      else await subscribeChannel(channel.id)
      showToast({
        title: wasSubscribed ? '구독을 취소했습니다' : '구독했습니다',
        tone: 'success',
      })
    } catch (error) {
      setChannel((prev) =>
        prev
          ? {
              ...prev,
              isSubscribed: wasSubscribed,
              subscriberCount: prev.subscriberCount + (wasSubscribed ? 1 : -1),
            }
          : prev,
      )
      showToast({
        title: '구독 상태가 반영되지 않았습니다',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmittingSubscribe(false)
    }
  }

  if (loading) {
    return (
      <main className="page ct-detail-page">
        <div className="ct-channel-hero-skeleton" aria-hidden="true" />
        <Skeleton lines={4} />
        <Skeleton lines={3} />
      </main>
    )
  }

  if (!channel) {
    return (
      <main className="page empty-state">
        <h1>채널을 찾을 수 없습니다</h1>
        <p className="muted">삭제되었거나 잘못된 링크일 수 있어요.</p>
        <button className="button button-primary is-block" onClick={() => onNavigate('/explore')} type="button">
          탐색으로 돌아가기
        </button>
      </main>
    )
  }

  const isCreatorOrAdmin = user?.role === 'CREATOR' || user?.role === 'ADMIN'
  const isOwner = Boolean(user && channel.ownerId === user.userId)
  const canPost = isOwner || user?.role === 'ADMIN'
  const canManageMembers = canPost
  const subscribed = channel.isSubscribed === true

  function openComposer() {
    setPostTitle('')
    setPostContent('')
    setComposingPost(true)
  }

  function cancelComposer() {
    setComposingPost(false)
    setPostTitle('')
    setPostContent('')
  }

  async function loadMembers() {
    if (!canManageMembers) return
    setLoadingMembers(true)
    try {
      const list = await listChannelMembers(channelId)
      setMembers(list)
    } catch (error) {
      showToast({
        title: '운영팀을 불러오지 못했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      })
    } finally {
      setLoadingMembers(false)
    }
  }

  // canManageMembers 가 true 가 되는 시점(채널 로드 + 인증 정보 도착) 에 1회 fetch.
  useEffect(() => {
    if (!canManageMembers || members !== null || loadingMembers) return
    loadMembers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canManageMembers])

  async function handleAddStaff(event: FormEvent) {
    event.preventDefault()
    if (addingStaff) return
    const trimmed = staffEmail.trim()
    if (!trimmed) {
      showToast({ title: '이메일을 입력해주세요', tone: 'warning' })
      return
    }
    setAddingStaff(true)
    try {
      const created = await addChannelStaff(channelId, trimmed)
      setMembers((prev) => (prev ? [...prev, created] : [created]))
      setStaffEmail('')
      showToast({ title: '스태프를 추가했어요', message: created.nickname, tone: 'success' })
    } catch (error) {
      const status = (error as ApiError | null)?.status
      const message = error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.'
      if (status === 409) {
        showToast({ title: '이미 채널 멤버입니다', tone: 'warning' })
      } else if (status === 404) {
        showToast({ title: '해당 이메일의 사용자를 찾을 수 없어요', tone: 'danger' })
      } else if (status === 400) {
        showToast({ title: '스태프로 추가할 수 없는 계정이에요', message, tone: 'danger' })
      } else {
        showToast({ title: '스태프 추가 실패', message, tone: 'danger' })
      }
    } finally {
      setAddingStaff(false)
    }
  }

  async function handleRemoveStaff(member: ChannelMember) {
    if (removingMemberId !== null) return
    if (!window.confirm(`${member.nickname}님을 스태프에서 제외할까요?`)) return
    setRemovingMemberId(member.id)
    try {
      await removeChannelMember(channelId, member.id)
      setMembers((prev) => prev?.filter((m) => m.id !== member.id) ?? null)
      showToast({ title: '스태프에서 제외했어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '스태프 제외 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setRemovingMemberId(null)
    }
  }

  function startEditPost(post: ChannelPost) {
    setEditingPostId(post.id)
    setEditTitle(post.title)
    setEditContent(post.content)
  }

  function cancelEditPost() {
    setEditingPostId(null)
    setEditTitle('')
    setEditContent('')
  }

  async function handleSaveEdit(postId: number, event: FormEvent) {
    event.preventDefault()
    if (savingEdit) return
    const trimmedTitle = editTitle.trim()
    const trimmedContent = editContent.trim()
    if (!trimmedTitle || !trimmedContent) {
      showToast({ title: '제목과 내용을 모두 입력해주세요', tone: 'warning' })
      return
    }
    setSavingEdit(true)
    try {
      const updated = await updateChannelPost(channelId, postId, {
        title: trimmedTitle,
        content: trimmedContent,
      })
      setPosts((items) => items.map((p) => (p.id === postId ? updated : p)))
      showToast({ title: '공지가 수정되었어요', tone: 'success' })
      cancelEditPost()
    } catch (error) {
      showToast({
        title: '공지 수정 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSavingEdit(false)
    }
  }

  async function handleDeletePost(post: ChannelPost) {
    if (deletingPostId !== null) return
    if (!window.confirm(`"${post.title}" 공지를 삭제할까요?`)) return
    setDeletingPostId(post.id)
    try {
      await deleteChannelPost(channelId, post.id)
      setPosts((items) => items.filter((p) => p.id !== post.id))
      showToast({ title: '공지를 삭제했어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '공지 삭제 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setDeletingPostId(null)
    }
  }

  async function handleSubmitPost(event: FormEvent) {
    event.preventDefault()
    if (submittingPost) return
    const trimmedTitle = postTitle.trim()
    const trimmedContent = postContent.trim()
    if (!trimmedTitle || !trimmedContent) {
      showToast({
        title: '제목과 내용을 모두 입력해주세요',
        tone: 'warning',
      })
      return
    }
    setSubmittingPost(true)
    try {
      const created = await createChannelPost(channelId, {
        title: trimmedTitle,
        content: trimmedContent,
      })
      setPosts((items) => [created, ...items])
      showToast({ title: '공지가 등록되었어요', tone: 'success' })
      cancelComposer()
    } catch (error) {
      showToast({
        title: '공지 등록 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmittingPost(false)
    }
  }

  return (
    <main className="page ct-detail-page ct-channel-detail">
      <button
        type="button"
        className="ct-back-btn"
        onClick={() => onNavigate('/explore')}
        aria-label="뒤로"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
          <path d="m15 5-7 7 7 7" />
        </svg>
      </button>

      <section className="ct-channel-hero">
        <div className="ct-channel-hero-cover" aria-hidden="true">
          {channel.thumbnailUrl ? (
            <img src={channel.thumbnailUrl} alt="" onError={(e) => (e.currentTarget.style.display = 'none')} />
          ) : (
            <span className="ct-channel-hero-initial">{channel.name.slice(0, 1).toUpperCase()}</span>
          )}
        </div>
        <div className="ct-channel-hero-body">
          <div className="badge-row">
            <Badge tone="primary">{channel.categoryDisplayName}</Badge>
          </div>
          <h1 className="ct-channel-title">{channel.name}</h1>
          <p className="ct-channel-summary">{channel.description}</p>
          <div className="ct-channel-stats">
            <div>
              <span>구독자</span>
              <strong>{formatSubscribers(channel.subscriberCount)}</strong>
            </div>
            <div>
              <span>운영자</span>
              <strong>{channel.ownerNickname}</strong>
            </div>
          </div>
          <button
            type="button"
            className={`button ${subscribed ? 'button-secondary' : 'button-primary'} is-block ct-channel-subscribe`}
            onClick={handleSubscribe}
            disabled={submittingSubscribe}
            aria-pressed={subscribed}
            aria-busy={submittingSubscribe}
          >
            {submittingSubscribe ? <span className="button-spinner" aria-hidden="true" /> : null}
            {subscribed ? '구독 중' : '구독하기'}
          </button>
        </div>
      </section>

      <section className="ct-channel-team" aria-label="운영자 정보">
        <div className="ct-channel-team-avatar" aria-hidden="true">
          {channel.ownerNickname.slice(0, 1).toUpperCase()}
        </div>
        <div className="ct-channel-team-body">
          <strong>{channel.ownerNickname}</strong>
          <span className="muted">기획자 · {channel.categoryDisplayName} 채널 운영</span>
        </div>
      </section>

      {canManageMembers ? (
        <section className="ct-channel-staff" aria-label="운영팀">
          <div className="section-heading">
            <h2 className="ct-channel-staff-title">운영팀</h2>
            <span className="muted">{members?.length ?? '…'}</span>
          </div>

          <form className="ct-channel-staff-add" onSubmit={handleAddStaff}>
            <input
              type="email"
              value={staffEmail}
              onChange={(e) => setStaffEmail(e.target.value)}
              placeholder="스태프 이메일"
              autoComplete="email"
              aria-label="스태프 이메일"
            />
            <button
              type="submit"
              className="button button-primary"
              disabled={addingStaff}
              aria-busy={addingStaff}
            >
              {addingStaff ? <span className="button-spinner" aria-hidden="true" /> : null}
              {addingStaff ? '추가 중...' : '스태프 추가'}
            </button>
          </form>

          {loadingMembers && !members ? (
            <Skeleton lines={2} />
          ) : !members || members.length === 0 ? (
            <div className="ct-channel-staff-empty muted">
              아직 운영팀이 없어요. 이메일로 스태프를 초대해보세요.
            </div>
          ) : (
            <ul className="ct-channel-staff-list">
              {members.map((m) => (
                <li key={m.id} className="ct-channel-staff-item">
                  <div className="ct-channel-staff-meta">
                    <strong>{m.nickname}</strong>
                    <span className="muted">{m.email}</span>
                  </div>
                  <div className="ct-channel-staff-trailing">
                    <Badge tone={m.role === 'OWNER' ? 'primary' : 'neutral'}>
                      {m.role === 'OWNER' ? '오너' : '스태프'}
                    </Badge>
                    {m.role === 'STAFF' ? (
                      <button
                        type="button"
                        className="text-button ct-channel-staff-remove"
                        onClick={() => handleRemoveStaff(m)}
                        disabled={removingMemberId === m.id}
                      >
                        {removingMemberId === m.id ? '제외 중...' : '제외'}
                      </button>
                    ) : null}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}

      <div className="segmented ct-channel-tabs" role="tablist" aria-label="채널 탭">
        <button
          className={tab === 'events' ? 'is-active' : ''}
          onClick={() => selectTab('events')}
          type="button"
          role="tab"
          aria-selected={tab === 'events'}
        >
          이벤트 {events.length > 0 ? `(${events.length})` : ''}
        </button>
        <button
          className={tab === 'posts' ? 'is-active' : ''}
          onClick={() => selectTab('posts')}
          type="button"
          role="tab"
          aria-selected={tab === 'posts'}
        >
          공지사항 {posts.length > 0 ? `(${posts.length})` : ''}
        </button>
      </div>

      {tab === 'events' ? (
        <section className="section ct-channel-tab-panel">
          {isCreatorOrAdmin ? (
            <button
              type="button"
              className="button button-secondary is-block ct-channel-new-event"
              onClick={() => onNavigate(`/channels/${channelId}/events/new`)}
            >
              + 새 이벤트 만들기
            </button>
          ) : null}
          <div className="stack">
            {events.length === 0 ? (
              <div className="ct-channel-tab-empty">
                <span aria-hidden="true">🎬</span>
                <strong>아직 등록된 이벤트가 없어요</strong>
                <span className="muted">새 이벤트가 열리면 알림으로 알려드릴게요.</span>
              </div>
            ) : (
              events.map((event) => (
                <EventCard
                  key={event.id}
                  event={event}
                  onOpen={(cid, eid) => onNavigate(`/channels/${cid}/events/${eid}`)}
                />
              ))
            )}
          </div>
        </section>
      ) : null}

      {tab === 'posts' ? (
        <section className="section ct-channel-tab-panel">
          {canPost && !composingPost ? (
            <button
              type="button"
              className="button button-secondary is-block ct-channel-new-post"
              onClick={openComposer}
            >
              + 공지 작성
            </button>
          ) : null}

          {composingPost ? (
            <form className="form-section ct-post-composer" onSubmit={handleSubmitPost}>
              <h2 className="ct-post-composer-title">새 공지 작성</h2>
              <div className="form-stack">
                <label>
                  제목
                  <input
                    value={postTitle}
                    onChange={(e) => setPostTitle(e.target.value)}
                    placeholder="공지 제목"
                    maxLength={100}
                    required
                  />
                </label>
                <label>
                  내용
                  <textarea
                    value={postContent}
                    onChange={(e) => setPostContent(e.target.value)}
                    placeholder="구독자에게 알릴 내용을 입력해주세요."
                    rows={6}
                    required
                  />
                </label>
              </div>
              <div className="ct-post-composer-actions">
                <button
                  type="button"
                  className="button button-secondary"
                  onClick={cancelComposer}
                  disabled={submittingPost}
                >
                  취소
                </button>
                <button
                  type="submit"
                  className="button button-primary"
                  disabled={submittingPost}
                  aria-busy={submittingPost}
                >
                  {submittingPost ? <span className="button-spinner" aria-hidden="true" /> : null}
                  {submittingPost ? '등록 중...' : '공지 등록'}
                </button>
              </div>
            </form>
          ) : null}

          <div className="stack">
            {posts.length === 0 ? (
              <div className="ct-channel-tab-empty">
                <span aria-hidden="true">📣</span>
                <strong>아직 공지사항이 없어요</strong>
                <span className="muted">
                  {canPost
                    ? '구독자에게 첫 공지를 남겨보세요.'
                    : '기획자가 새 소식을 올리면 여기에 표시됩니다.'}
                </span>
                {canPost && !composingPost ? (
                  <button
                    type="button"
                    className="button button-primary"
                    onClick={openComposer}
                  >
                    공지 작성하기
                  </button>
                ) : null}
              </div>
            ) : (
              posts.map((post) => {
                const isEditing = editingPostId === post.id
                if (isEditing && canPost) {
                  return (
                    <form
                      key={post.id}
                      className="form-section ct-post-composer"
                      onSubmit={(e) => handleSaveEdit(post.id, e)}
                    >
                      <h2 className="ct-post-composer-title">공지 수정</h2>
                      <div className="form-stack">
                        <label>
                          제목
                          <input
                            value={editTitle}
                            onChange={(e) => setEditTitle(e.target.value)}
                            maxLength={100}
                            required
                          />
                        </label>
                        <label>
                          내용
                          <textarea
                            value={editContent}
                            onChange={(e) => setEditContent(e.target.value)}
                            rows={6}
                            required
                          />
                        </label>
                      </div>
                      <div className="ct-post-composer-actions">
                        <button
                          type="button"
                          className="button button-secondary"
                          onClick={cancelEditPost}
                          disabled={savingEdit}
                        >
                          취소
                        </button>
                        <button
                          type="submit"
                          className="button button-primary"
                          disabled={savingEdit}
                          aria-busy={savingEdit}
                        >
                          {savingEdit ? <span className="button-spinner" aria-hidden="true" /> : null}
                          {savingEdit ? '저장 중...' : '저장'}
                        </button>
                      </div>
                    </form>
                  )
                }
                return (
                  <article key={post.id} className="card">
                    <div className="card-body">
                      <div className="card-heading-row">
                        <strong>{post.title}</strong>
                        <span className="muted">{new Date(post.createdAt).toLocaleDateString()}</span>
                      </div>
                      <p>{post.content}</p>
                      <div className="meta-row">
                        <span>작성자 {post.authorNickname}</span>
                        {canPost ? (
                          <div className="ct-post-actions">
                            <button
                              type="button"
                              className="text-button"
                              onClick={() => startEditPost(post)}
                            >
                              수정
                            </button>
                            <button
                              type="button"
                              className="text-button ct-post-action-danger"
                              onClick={() => handleDeletePost(post)}
                              disabled={deletingPostId === post.id}
                            >
                              {deletingPostId === post.id ? '삭제 중...' : '삭제'}
                            </button>
                          </div>
                        ) : null}
                      </div>
                    </div>
                  </article>
                )
              })
            )}
          </div>
        </section>
      ) : null}
    </main>
  )
}

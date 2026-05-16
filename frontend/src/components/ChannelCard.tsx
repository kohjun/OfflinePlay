import type { Channel } from '../types'
import { Badge } from './Badge'

interface ChannelCardProps {
  channel: Channel
  onOpen?: (id: number) => void
  onToggleSubscribe?: (channel: Channel) => void
}

function formatSubscribers(n: number) {
  if (n >= 10000) return `${(n / 10000).toFixed(n % 10000 === 0 ? 0 : 1)}만`
  if (n >= 1000) return `${Math.floor(n / 100) / 10}천`
  return n.toLocaleString()
}

export function ChannelCard({ channel, onOpen, onToggleSubscribe }: ChannelCardProps) {
  function handleOpen() {
    onOpen?.(channel.id)
  }

  function handleToggle(e: React.MouseEvent) {
    e.stopPropagation()
    onToggleSubscribe?.(channel)
  }

  return (
    <article className="card channel-card ct-channel-card" onClick={handleOpen}>
      <button
        type="button"
        className="media-button"
        onClick={(e) => {
          e.stopPropagation()
          handleOpen()
        }}
        aria-label={`${channel.name} 채널 열기`}
      >
        {channel.thumbnailUrl ? (
          <img src={channel.thumbnailUrl} alt="" />
        ) : (
          <span className="media-placeholder">{channel.name.slice(0, 1).toUpperCase()}</span>
        )}
      </button>
      <div className="card-body">
        <div className="badge-row">
          <Badge tone="primary">{channel.categoryDisplayName}</Badge>
          {/* PR47: 후기 1건 이상일 때만 칩 노출. */}
          {channel.averageRating != null && (channel.reviewCount ?? 0) > 0 ? (
            <span className="ct-rating-chip" aria-label={`평균 별점 ${channel.averageRating.toFixed(1)}, 후기 ${channel.reviewCount}건`}>
              <span aria-hidden="true">★</span>
              <strong>{channel.averageRating.toFixed(1)}</strong>
              <span className="muted">({channel.reviewCount})</span>
            </span>
          ) : null}
        </div>
        <h3 className="ct-channel-card-title">{channel.name}</h3>
        <p className="ct-channel-card-desc">{channel.description}</p>
        <div className="meta-row">
          <span>구독자 {formatSubscribers(channel.subscriberCount)}</span>
          <span>기획자 {channel.ownerNickname}</span>
        </div>
        {onToggleSubscribe ? (
          <button
            className={`button ${channel.isSubscribed ? 'button-secondary' : 'button-primary'} ct-channel-card-cta`}
            onClick={handleToggle}
            type="button"
            aria-pressed={channel.isSubscribed === true}
          >
            {channel.isSubscribed ? '구독 중' : '구독하기'}
          </button>
        ) : null}
      </div>
    </article>
  )
}

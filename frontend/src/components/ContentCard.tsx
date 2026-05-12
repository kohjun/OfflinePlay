import type { Content } from '../types'

interface ContentCardProps {
  content: Content
}

export function ContentCard({ content }: ContentCardProps) {
  return (
    <article className="card content-card">
      {content.thumbnailUrl ? (
        <img className="content-thumb" src={content.thumbnailUrl} alt="" />
      ) : (
        <div className="content-thumb content-thumb-empty">{content.title.slice(0, 1).toUpperCase()}</div>
      )}
      <div className="card-body">
        <h3>{content.title}</h3>
        <p>{content.description}</p>
        <div className="meta-row">
          <span>{content.creatorNickname}</span>
          <span>{content.viewCount.toLocaleString()} views</span>
        </div>
      </div>
    </article>
  )
}

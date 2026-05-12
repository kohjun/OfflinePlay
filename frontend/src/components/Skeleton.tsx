interface SkeletonProps {
  lines?: number
}

export function Skeleton({ lines = 3 }: SkeletonProps) {
  return (
    <div className="skeleton-card" aria-label="Loading">
      {Array.from({ length: lines }).map((_, index) => (
        <span key={index} className="skeleton-line" />
      ))}
    </div>
  )
}

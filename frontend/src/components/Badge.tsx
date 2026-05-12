import type { ReactNode } from 'react'

interface BadgeProps {
  children: ReactNode
  tone?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral'
}

export function Badge({ children, tone = 'neutral' }: BadgeProps) {
  return <span className={`badge badge-${tone}`}>{children}</span>
}

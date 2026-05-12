import type { ReactNode } from 'react'
import { useNotificationStore } from '../stores/notificationStore'

interface BottomTabBarProps {
  currentPath: string
  onNavigate: (path: string) => void
}

interface TabDef {
  path: string
  label: string
  icon: ReactNode
}

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

const sideTabs: TabDef[] = [
  {
    path: '/',
    label: '홈',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M3.2 11.6 12 4l8.8 7.6" />
        <path d="M5.5 10v9.2a.8.8 0 0 0 .8.8h3.5v-5.5h4.4V20h3.5a.8.8 0 0 0 .8-.8V10" />
      </svg>
    ),
  },
  {
    path: '/community',
    label: '커뮤니티',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M4.5 8.5A2.5 2.5 0 0 1 7 6h7a2.5 2.5 0 0 1 2.5 2.5v4A2.5 2.5 0 0 1 14 15h-4l-3 3v-3H7A2.5 2.5 0 0 1 4.5 12.5z" />
        <path d="M11 5h6a2.5 2.5 0 0 1 2.5 2.5v4a2.5 2.5 0 0 1-1 2" />
      </svg>
    ),
  },
  {
    path: '/my',
    label: 'MY',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M4 19V7l8-3 8 3v12" />
        <path d="M9 19v-6h6v6" />
      </svg>
    ),
  },
  {
    path: '/profile',
    label: '프로필',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <circle cx="12" cy="9" r="3.6" />
        <path d="M5 19.4a7 7 0 0 1 14 0" />
      </svg>
    ),
  },
]

export function BottomTabBar({ currentPath, onNavigate }: BottomTabBarProps) {
  const { unreadCount } = useNotificationStore()

  function isActive(path: string) {
    if (path === '/') return currentPath === '/'
    return currentPath.startsWith(path)
  }

  const left = sideTabs.slice(0, 2)
  const right = sideTabs.slice(2)
  const playActive = currentPath.startsWith('/play')

  return (
    <nav className="ct-tab-bar" aria-label="기본 네비게이션">
      {left.map((tab) => (
        <button
          key={tab.path}
          type="button"
          className={`ct-tab ${isActive(tab.path) ? 'is-active' : ''}`}
          onClick={() => onNavigate(tab.path)}
          aria-current={isActive(tab.path) ? 'page' : undefined}
        >
          <span className="ct-tab-icon" aria-hidden="true">
            {tab.icon}
            {tab.path === '/community' && unreadCount > 0 ? (
              <span className="ct-tab-dot">{unreadCount > 99 ? '99+' : unreadCount}</span>
            ) : null}
          </span>
          <span className="ct-tab-label">{tab.label}</span>
        </button>
      ))}

      <button
        type="button"
        className={`ct-tab-play ${playActive ? 'is-active' : ''}`}
        onClick={() => onNavigate('/play')}
        aria-label="Play"
        aria-current={playActive ? 'page' : undefined}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" fill="currentColor">
          <path d="M8 5.5v13l11-6.5z" />
        </svg>
      </button>

      {right.map((tab) => (
        <button
          key={tab.path}
          type="button"
          className={`ct-tab ${isActive(tab.path) ? 'is-active' : ''}`}
          onClick={() => onNavigate(tab.path)}
          aria-current={isActive(tab.path) ? 'page' : undefined}
        >
          <span className="ct-tab-icon" aria-hidden="true">
            {tab.icon}
          </span>
          <span className="ct-tab-label">{tab.label}</span>
        </button>
      ))}
    </nav>
  )
}

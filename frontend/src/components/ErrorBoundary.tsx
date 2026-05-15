import { Component, type ErrorInfo, type ReactNode } from 'react'

interface ErrorBoundaryProps {
  children: ReactNode
}

interface ErrorBoundaryState {
  hasError: boolean
}

/**
 * Catches render-time errors in its subtree and shows a recovery card.
 *
 * Intentionally scoped to the page area only — the parent shell still renders the
 * top bar and bottom tab bar, so the user can navigate away even if the current
 * page crashes.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Surface to the browser console so devs can debug. No remote reporting yet.
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  private handleReload = () => {
    window.location.reload()
  }

  render() {
    if (!this.state.hasError) return this.props.children

    return (
      <main className="page empty-state error-boundary">
        <span className="error-boundary__icon" aria-hidden="true">⚡️</span>
        <h1>잠깐만요, 화면이 멈췄어요</h1>
        <p className="muted">
          예상치 못한 오류로 이 화면을 그릴 수 없었어요. 다시 시도해도 같은 화면이 나오면
          홈으로 돌아가서 다른 메뉴를 열어보세요.
        </p>
        <button className="button button-primary is-block" onClick={this.handleReload} type="button">
          다시 시도
        </button>
      </main>
    )
  }
}

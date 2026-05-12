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
      <main className="page error-boundary">
        <h1>화면을 불러오지 못했습니다</h1>
        <p className="muted">
          예상치 못한 오류가 발생했습니다. 잠시 후 다시 시도해주세요.
        </p>
        <button className="button button-primary" onClick={this.handleReload} type="button">
          다시 시도
        </button>
      </main>
    )
  }
}

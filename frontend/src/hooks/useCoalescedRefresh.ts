import { useCallback, useEffect, useRef } from 'react'

interface UseCoalescedRefreshOptions {
  /** 디바운스 지연. 기본 300ms — SSE 묶음 도착(예: 승인 → 티켓 발급) 을 한 번에 흡수. */
  delayMs?: number
}

interface UseCoalescedRefreshResult {
  /**
   * refresh 를 스케줄한다. 같은 윈도우(delayMs) 안에 여러 번 호출돼도 실제 refresh 는 한 번만 실행.
   * `reason` 은 옵셔널 디버그 힌트 — 실제 동작에는 영향 없음.
   */
  scheduleRefresh: (reason?: string) => void
  /** 펜딩 타이머가 있으면 취소하고 즉시 refresh 를 실행한다. */
  flushRefresh: () => void
}

/**
 * PR92 — SSE / 알림 수신처럼 같은 시간 창에 여러 트리거가 몰릴 때, 실제 refresh 호출을
 * 한 번으로 묶기 위한 hook.
 *
 *  - 페이지·hook 마다 `useRef<number | null>` + `setTimeout` 보일러플레이트가 반복되던 패턴을
 *    한 곳으로 모은다.
 *  - `refresh` 콜백은 ref 로 latest 를 따라가므로, 호출처에서 매 렌더마다 새 함수를 넘겨도
 *    동일 윈도우 내 묶음 효과가 그대로 보장된다.
 *  - unmount 시 펜딩 타이머가 정리된다.
 *
 * EventDetail 의 flag 병합 스케줄러처럼 "여러 종류 refetch 를 하나의 타이머로 묶는" 케이스도
 * 본 hook 의 단일 콜백 안에서 flag ref 를 읽어 처리하면 된다 (useEventDetailData 참고).
 */
export function useCoalescedRefresh(
  refresh: () => void | Promise<void>,
  options: UseCoalescedRefreshOptions = {},
): UseCoalescedRefreshResult {
  const { delayMs = 300 } = options

  // 최신 콜백을 ref 로 보관 — scheduleRefresh 가 stable callback 이면서도 최신 클로저를 호출.
  const refreshRef = useRef(refresh)
  useEffect(() => {
    refreshRef.current = refresh
  }, [refresh])

  const timerRef = useRef<number | null>(null)

  const cancel = useCallback(() => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  const scheduleRefresh = useCallback(
    (_reason?: string) => {
      // 이미 펜딩이면 추가 트리거를 흡수한다 (debounce-leading 가 아닌 coalesce-trailing).
      if (timerRef.current != null) return
      timerRef.current = window.setTimeout(() => {
        timerRef.current = null
        const result = refreshRef.current()
        if (result && typeof (result as Promise<void>).catch === 'function') {
          ;(result as Promise<void>).catch(() => {
            /* non-fatal — refresh 자체 실패는 호출처에서 별도 처리하는 것이 원칙. */
          })
        }
      }, delayMs)
    },
    [delayMs],
  )

  const flushRefresh = useCallback(() => {
    cancel()
    const result = refreshRef.current()
    if (result && typeof (result as Promise<void>).catch === 'function') {
      ;(result as Promise<void>).catch(() => {
        /* non-fatal */
      })
    }
  }, [cancel])

  // unmount cleanup.
  useEffect(() => cancel, [cancel])

  return { scheduleRefresh, flushRefresh }
}

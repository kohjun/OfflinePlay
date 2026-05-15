/**
 * Toss Payments JS SDK 동적 로더.
 *
 * `VITE_TOSS_CLIENT_KEY` 가 빌드 시점에 들어왔을 때만 실제 SDK 를 로드한다. 키가 없으면
 * `loadTossPayments` 는 reject 하고, 호출처 (`EventDetailPage`) 가 mock confirm fallback
 * 으로 빠진다 — sandbox 키가 아직 없는 환경에서도 결제 CTA 가 동작하게 하기 위함.
 *
 * SDK script (`https://js.tosspayments.com/v1/payment`) 는 한 번만 로드되고, 두 번째
 * 이후 호출은 캐시된 Promise 를 반환한다.
 */

/** Toss SDK 의 결제 진입점. 정확한 타입 정의는 @tosspayments/payment-sdk 참고. */
export interface TossPaymentsInstance {
  /**
   * 카드/계좌 등 결제 수단 별 결제창을 띄운다. 호출 시 페이지가 PG 결제창으로 전환되며,
   * 결제 결과는 `successUrl` / `failUrl` 로 redirect 되어 돌아온다.
   *
   * 결제수단 키 문자열은 Toss 공식 문서를 따른다 — 예: '카드', '계좌이체', '간편결제'.
   */
  requestPayment(
    method: string,
    options: {
      amount: number
      orderId: string
      orderName: string
      successUrl: string
      failUrl: string
      customerName?: string
      customerEmail?: string
    },
  ): Promise<void>
}

declare global {
  interface Window {
    TossPayments?: (clientKey: string) => TossPaymentsInstance
  }
}

const SDK_URL = 'https://js.tosspayments.com/v1/payment'

let cached: Promise<TossPaymentsInstance> | null = null

/**
 * Toss SDK 를 로드하고 [TossPaymentsInstance] 를 반환한다.
 *
 * - clientKey 가 비어 있거나 SDK 로드가 실패하면 reject. 호출처는 catch 해서 사용자에게
 *   "결제 모듈 설정이 필요합니다" 토스트를 띄우거나 mock fallback 으로 전환한다.
 * - 첫 호출 후 결과를 캐시하므로 같은 키로 여러 번 호출해도 script 가 중복 로드되지 않는다.
 */
export function loadTossPayments(clientKey: string): Promise<TossPaymentsInstance> {
  if (!clientKey) {
    return Promise.reject(new Error('Toss client key is not configured'))
  }
  if (cached) return cached

  cached = new Promise((resolve, reject) => {
    if (typeof window === 'undefined') {
      reject(new Error('Toss SDK can only load in a browser context'))
      return
    }

    const construct = () => {
      const factory = window.TossPayments
      if (!factory) {
        reject(new Error('Toss SDK loaded but TossPayments factory is missing'))
        return
      }
      try {
        resolve(factory(clientKey))
      } catch (err) {
        reject(err instanceof Error ? err : new Error(String(err)))
      }
    }

    if (window.TossPayments) {
      construct()
      return
    }

    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SDK_URL}"]`)
    if (existing) {
      existing.addEventListener('load', construct, { once: true })
      existing.addEventListener('error', () => reject(new Error('Toss SDK script failed to load')), { once: true })
      return
    }

    const script = document.createElement('script')
    script.src = SDK_URL
    script.async = true
    script.onload = construct
    script.onerror = () => reject(new Error('Toss SDK script failed to load'))
    document.head.appendChild(script)
  })

  return cached
}

/**
 * 현재 빌드의 Toss client key. 빌드 시 `VITE_TOSS_CLIENT_KEY` env var 가 주입된다.
 * 비어 있으면 결제 CTA 는 mock confirm fallback 으로 동작한다.
 */
export function tossClientKey(): string {
  return (import.meta.env.VITE_TOSS_CLIENT_KEY as string | undefined) ?? ''
}

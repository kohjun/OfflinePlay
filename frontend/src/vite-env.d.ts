/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  /**
   * PR139 — Web Push VAPID public key (base64url). Empty/undefined 면 frontend 가
   * "지원하지 않는 환경" 으로 폴백한다. 운영은 backend 의 PUSH_VAPID_PUBLIC_KEY 와 동일 값.
   */
  readonly VITE_PUSH_VAPID_PUBLIC_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

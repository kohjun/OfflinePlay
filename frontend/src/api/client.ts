import type { ApiResponse, PageResponse } from '../types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const ACCESS_TOKEN_KEY = 'woya.accessToken'
const REFRESH_TOKEN_KEY = 'woya.refreshToken'

type QueryValue = string | number | boolean | undefined | null

export class ApiError extends Error {
  status: number
  payload: unknown

  constructor(message: string, status: number, payload: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
  }
}

export const tokenStorage = {
  get() {
    return localStorage.getItem(ACCESS_TOKEN_KEY)
  },
  set(token: string) {
    localStorage.setItem(ACCESS_TOKEN_KEY, token)
  },
  getRefresh() {
    return localStorage.getItem(REFRESH_TOKEN_KEY)
  },
  setRefresh(token: string) {
    localStorage.setItem(REFRESH_TOKEN_KEY, token)
  },
  setPair(accessToken: string, refreshToken: string) {
    localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
  },
  clear() {
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  },
}

let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

function buildUrl(path: string, query?: Record<string, QueryValue>) {
  const trimmedBase = API_BASE_URL.replace(/\/$/, '')
  const trimmedPath = path.startsWith('/') ? path : `/${path}`
  const url = new URL(`${trimmedBase}${trimmedPath}`, window.location.origin)

  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value))
      }
    })
  }

  return url.toString()
}

async function parseResponse<T>(response: Response): Promise<T> {
  const text = await response.text()
  const payload = text ? JSON.parse(text) : null

  if (!response.ok) {
    const message =
      payload && typeof payload === 'object' && 'message' in payload
        ? String((payload as { message: unknown }).message)
        : 'Request failed'
    throw new ApiError(message, response.status, payload)
  }

  if (payload && typeof payload === 'object' && 'data' in payload) {
    return (payload as ApiResponse<T>).data
  }

  return payload as T
}

function buildHeaders(options: RequestInit) {
  const headers = new Headers(options.headers)
  const token = tokenStorage.get()
  if (!headers.has('Content-Type') && options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return headers
}

let reissuePromise: Promise<string | null> | null = null

interface ReissuedTokens {
  accessToken?: string
  refreshToken?: string
}

async function reissueAccessToken(): Promise<string | null> {
  if (reissuePromise) return reissuePromise

  const refreshToken = tokenStorage.getRefresh()
  if (!refreshToken) return null

  reissuePromise = (async () => {
    try {
      const response = await fetch(buildUrl('/auth/reissue'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
      if (!response.ok) return null
      const text = await response.text()
      const payload = text ? JSON.parse(text) : null
      const data: ReissuedTokens | null =
        payload && typeof payload === 'object' && 'data' in payload
          ? (payload as { data: ReissuedTokens }).data
          : (payload as ReissuedTokens | null)
      const newAccess = data?.accessToken ?? null
      const newRefresh = data?.refreshToken ?? null
      if (newAccess && newRefresh) {
        tokenStorage.setPair(newAccess, newRefresh)
      } else if (newAccess) {
        tokenStorage.set(newAccess)
      }
      return newAccess
    } catch {
      return null
    } finally {
      reissuePromise = null
    }
  })()

  return reissuePromise
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  query?: Record<string, QueryValue>,
): Promise<T> {
  const url = buildUrl(path, query)
  const skipReissue =
    path === '/auth/reissue' ||
    path === '/auth/login' ||
    path === '/auth/signup' ||
    path === '/auth/logout'

  const response = await fetch(url, {
    ...options,
    headers: buildHeaders(options),
  })

  if (response.status !== 401 || skipReissue) {
    return parseResponse<T>(response)
  }

  const newToken = await reissueAccessToken()
  if (!newToken) {
    tokenStorage.clear()
    onUnauthorized?.()
    return parseResponse<T>(response)
  }

  const retryResponse = await fetch(url, {
    ...options,
    headers: buildHeaders(options),
  })

  if (retryResponse.status === 401) {
    tokenStorage.clear()
    onUnauthorized?.()
  }

  return parseResponse<T>(retryResponse)
}

/**
 * PR63 — non-JSON 응답 (CSV 등) 을 Blob 으로 받는 변형. 인증 헤더 + 401 reissue 로직은 동일.
 * JSON request() 와 코드 중복이 있지만, 분기 처리하면 generic 타입이 복잡해져 분리.
 */
async function requestBlob(
  path: string,
  query?: Record<string, QueryValue>,
): Promise<Blob> {
  const url = buildUrl(path, query)
  const initialResponse = await fetch(url, { headers: buildHeaders({}) })
  if (initialResponse.status !== 401) {
    return parseBlobResponse(initialResponse)
  }
  const newToken = await reissueAccessToken()
  if (!newToken) {
    tokenStorage.clear()
    onUnauthorized?.()
    throw new ApiError('Unauthorized', 401, null)
  }
  const retryResponse = await fetch(url, { headers: buildHeaders({}) })
  if (retryResponse.status === 401) {
    tokenStorage.clear()
    onUnauthorized?.()
  }
  return parseBlobResponse(retryResponse)
}

async function parseBlobResponse(response: Response): Promise<Blob> {
  if (!response.ok) {
    // 실패 시 JSON 메시지를 추출해 ApiError 로 던진다 (정상 응답이 CSV 라도 에러는 JSON).
    const text = await response.text()
    let payload: unknown = null
    try {
      payload = text ? JSON.parse(text) : null
    } catch {
      payload = text
    }
    const message =
      payload && typeof payload === 'object' && 'message' in payload
        ? String((payload as { message: unknown }).message)
        : 'Request failed'
    throw new ApiError(message, response.status, payload)
  }
  return await response.blob()
}

export const apiClient = {
  get: <T>(path: string, query?: Record<string, QueryValue>) => request<T>(path, {}, query),
  getBlob: (path: string, query?: Record<string, QueryValue>) => requestBlob(path, query),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: 'POST',
      body: body instanceof FormData ? body : JSON.stringify(body ?? {}),
    }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: 'PUT',
      body: JSON.stringify(body ?? {}),
    }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: 'PATCH',
      body: JSON.stringify(body ?? {}),
    }),
  delete: <T>(path: string) =>
    request<T>(path, {
      method: 'DELETE',
    }),
}

export const emptyPage = <T>(): PageResponse<T> => ({
  content: [],
  totalElements: 0,
  totalPages: 0,
  currentPage: 0,
  size: 0,
  isFirst: true,
  isLast: true,
})

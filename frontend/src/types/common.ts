/** Mirrors backend ApiResponse envelope (success/message/data). */
export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

/** Mirrors backend PageResponse. */
export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  currentPage: number
  size: number
  isFirst: boolean
  isLast: boolean
}

export interface Content {
  id: number
  title: string
  description: string
  thumbnailUrl?: string
  creatorNickname: string
  viewCount: number
  createdAt: string
}

export interface ContentPayload {
  title: string
  description: string
  thumbnailUrl?: string
}

export interface ToastMessage {
  id: number
  title: string
  message?: string
  tone?: 'success' | 'warning' | 'danger' | 'info'
}

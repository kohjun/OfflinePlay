import { apiClient } from './client'
import type { PageResponse } from '../types'

export interface Review {
  id: number
  eventId: number
  authorId: number
  authorNickname: string
  rating: number
  content: string
  createdAt: string
  updatedAt: string
}

export interface EventReviewSummary {
  averageRating: number | null
  reviewCount: number
}

export interface CreateReviewPayload {
  rating: number
  content: string
}

export interface UpdateReviewPayload {
  rating: number
  content: string
}

/** POST /api/v1/events/{eventId}/reviews — USED 티켓 보유자만 가능. 401/403/409 등은 backend 에러 메시지 노출. */
export function createReview(eventId: number, payload: CreateReviewPayload) {
  return apiClient.post<Review>(`/events/${eventId}/reviews`, payload)
}

/** GET /api/v1/events/{eventId}/reviews — 비로그인 OK. 최신순. */
export function getEventReviews(eventId: number, params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<Review>>(`/events/${eventId}/reviews`, params)
}

/** GET /api/v1/events/{eventId}/reviews/me — 본인 후기. 없으면 응답 data = null. */
export function getMyReview(eventId: number) {
  return apiClient.get<Review | null>(`/events/${eventId}/reviews/me`)
}

/** GET /api/v1/events/{eventId}/reviews/summary — 평균 별점 + 총 후기 수 (hero 표시용). */
export function getEventReviewSummary(eventId: number) {
  return apiClient.get<EventReviewSummary>(`/events/${eventId}/reviews/summary`)
}

/** PATCH /api/v1/reviews/{reviewId} — 본인만. */
export function updateReview(reviewId: number, payload: UpdateReviewPayload) {
  return apiClient.patch<Review>(`/reviews/${reviewId}`, payload)
}

/** DELETE /api/v1/reviews/{reviewId} — 본인 + ADMIN. */
export function deleteReview(reviewId: number) {
  return apiClient.delete<void>(`/reviews/${reviewId}`)
}

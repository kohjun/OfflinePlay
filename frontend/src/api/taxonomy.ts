import { apiClient } from './client'

/**
 * PR147 — interests + regions catalog API.
 *
 *  - 두 catalog 는 비로그인 허용 — 비로그인 EventCreate 화면에서도 region/interest 옵션 노출 가능.
 *  - 캐싱은 frontend caller (페이지/store) 가 담당. 본 helper 는 매 호출 fetch.
 */

export interface Interest {
  id: number
  slug: string
  label: string
  category: string
  displayOrder: number
}

export interface Sigungu {
  code: string
  name: string
  parentCode: string
}

export interface Sido {
  code: string
  name: string
  sigunguList: Sigungu[]
}

export function listInterests() {
  return apiClient.get<Interest[]>('/interests')
}

export function getRegionTree() {
  return apiClient.get<Sido[]>('/regions')
}

export function getMyInterests() {
  return apiClient.get<Interest[]>('/users/me/interests')
}

export function updateMyInterests(interestIds: number[]) {
  return apiClient.patch<Interest[]>('/users/me/interests', { interestIds })
}

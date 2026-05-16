import { apiClient } from './client'

/**
 * 백엔드 `/files/upload` 응답 DTO 와 1:1.
 * - `url`: 공개 가능한 S3/CDN URL (DB 에 그대로 저장)
 * - `key`: S3 internal key — 삭제 요청 시 사용 (사용자 본인 경로 검증)
 */
export interface UploadedFile {
  url: string
  key: string
  originalFilename: string
  contentType: string
  size: number
}

/**
 * 백엔드 FileDirectory enum 과 동일한 라벨.
 * 디렉토리에 따라 S3 prefix 가 결정됨 (예: CONTENT_THUMBNAIL → contents/...).
 */
export type FileDirectory = 'PROFILE' | 'CHANNEL_THUMBNAIL' | 'CONTENT_THUMBNAIL' | 'POST'

/**
 * 서버 직접 업로드 — `/api/v1/files/upload?directory=...` POST multipart.
 * 작은 파일/단순 흐름에 권장. 큰 파일은 presigned URL 흐름 사용 (별도 helper).
 *
 * 로컬 개발 환경에서는 application-local.yml 의 fake AWS credentials 때문에
 * 실제 PUT 단계에서 실패할 수 있다 — 호출처는 ApiError 를 잡아 사용자에게
 * "URL 직접 입력" 대안을 안내해야 한다.
 */
export function uploadFile(file: File, directory: FileDirectory = 'CONTENT_THUMBNAIL') {
  const form = new FormData()
  form.append('file', file)
  return apiClient.post<UploadedFile>(`/files/upload?directory=${directory}`, form)
}

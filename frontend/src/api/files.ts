import { apiClient } from './client'
import { compressImage } from '../utils/imageCompression'

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
 *
 * PR163 부터 이미지 파일은 업로드 전에 Canvas 로 자동 리사이즈/압축 (최대 변 1280px,
 * JPEG quality 0.85). PNG/JPG/WebP/GIF 모두 입력 가능, GIF 는 첫 프레임만 추출.
 * 원본보다 결과가 더 크면 원본 그대로 업로드.
 *
 * 로컬 개발 환경에서는 application-local.yml 의 `storage.local-fallback.enabled=true` 가 활성화돼
 * 서버가 디스크에 저장하고 `/uploads/...` URL 을 반환한다. 운영(prod) 은 S3 직접 호출.
 */
export async function uploadFile(
  file: File,
  directory: FileDirectory = 'CONTENT_THUMBNAIL',
) {
  const finalFile = file.type.startsWith('image/') ? await compressImage(file) : file
  const form = new FormData()
  form.append('file', finalFile)
  return apiClient.post<UploadedFile>(`/files/upload?directory=${directory}`, form)
}

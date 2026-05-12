import { apiClient } from './client'

export interface UploadedFile {
  url: string
  fileName: string
  contentType: string
  size: number
}

export function uploadFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  return apiClient.post<UploadedFile>('/files', form)
}

/**
 * PR163 — 업로드 전 Canvas 기반 이미지 리사이즈/압축.
 *
 *  - 최대 변 1280px 로 비율 유지 축소.
 *  - PNG/JPG/WebP/GIF 입력 모두 받아 항상 JPEG 출력으로 정규화 (file size 안정 + alpha 무관).
 *  - quality 0.85 — Instagram 카드 수준의 가시 품질.
 *  - 원본보다 결과가 더 크면 원본을 그대로 반환 (작은 파일은 굳이 변환할 이유 없음).
 *  - GIF 는 첫 프레임만 추출됨 — 사용자에게 안내 메시지를 호출처에서 제공 권장.
 *  - 잘못된 이미지 / canvas 실패 시 원본 파일을 그대로 반환 (silent fallback).
 *
 * 본 helper 는 외부 dep 없이 표준 Canvas API 만 사용한다.
 */

const DEFAULT_MAX_SIDE = 1280
const DEFAULT_QUALITY = 0.85
const OUTPUT_TYPE = 'image/jpeg'

export interface CompressOptions {
  maxSide?: number
  quality?: number
}

export async function compressImage(
  file: File,
  options: CompressOptions = {},
): Promise<File> {
  if (!file.type.startsWith('image/')) return file
  try {
    const maxSide = options.maxSide ?? DEFAULT_MAX_SIDE
    const quality = options.quality ?? DEFAULT_QUALITY

    const bitmap = await loadBitmap(file)
    const { width, height } = scaleDown(bitmap.width, bitmap.height, maxSide)

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')
    if (!ctx) return file
    // 흰 배경으로 칠해 PNG alpha → JPEG 전환 시 검은 배경 회피.
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(0, 0, width, height)
    ctx.drawImage(bitmap, 0, 0, width, height)
    if (typeof (bitmap as ImageBitmap).close === 'function') {
      ;(bitmap as ImageBitmap).close()
    }

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, OUTPUT_TYPE, quality),
    )
    if (!blob) return file
    // 원본이 더 작으면 그대로. (작은 이미지를 굳이 변환할 필요 없음.)
    if (blob.size >= file.size) return file

    // 확장자를 .jpg 로 정규화.
    const baseName = file.name.replace(/\.[^.]+$/, '')
    const compressed = new File([blob], `${baseName}.jpg`, {
      type: OUTPUT_TYPE,
      lastModified: Date.now(),
    })
    return compressed
  } catch {
    return file
  }
}

async function loadBitmap(file: File): Promise<ImageBitmap | HTMLImageElement> {
  // createImageBitmap 가 가능한 환경 (Chromium / Firefox / iOS Safari 14+) 에서 더 빠르고 alpha 채널도 정확.
  if (typeof createImageBitmap === 'function') {
    try {
      return await createImageBitmap(file)
    } catch {
      /* fall back to <img> */
    }
  }
  return new Promise((resolve, reject) => {
    const img = new Image()
    const url = URL.createObjectURL(file)
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('이미지 로딩 실패'))
    }
    img.src = url
  })
}

function scaleDown(width: number, height: number, maxSide: number) {
  const longest = Math.max(width, height)
  if (longest <= maxSide) return { width, height }
  const ratio = maxSide / longest
  return {
    width: Math.round(width * ratio),
    height: Math.round(height * ratio),
  }
}

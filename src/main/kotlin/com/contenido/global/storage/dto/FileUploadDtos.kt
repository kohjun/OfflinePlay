package com.contenido.global.storage.dto

/**
 * 서버 직접 업로드 응답 DTO
 */
data class FileUploadResponse(
    /** 업로드된 파일의 공개 URL (CDN 또는 S3 URL) */
    val url: String,

    /** S3 내부 키 (삭제 요청 시 사용) */
    val key: String,

    /** 원본 파일명 */
    val originalFilename: String,

    /** 파일 크기 (bytes) */
    val size: Long,

    /** Content-Type */
    val contentType: String,
)

/**
 * Presigned URL 응답 DTO
 * 프론트엔드가 이 URL로 직접 PUT 요청하여 S3에 업로드한다.
 */
data class PresignedUrlResponse(
    /** Presigned PUT URL — 이 URL에 파일을 PUT 하면 S3에 저장된다. */
    val presignedUrl: String,

    /** 업로드 완료 후 접근 가능한 최종 파일 URL */
    val fileUrl: String,

    /** S3 키 (업로드 완료 확인 및 삭제 시 사용) */
    val key: String,

    /** 만료 시간 (ISO-8601) */
    val expiresAt: String,
)

/**
 * Presigned URL 요청 DTO
 */
data class PresignedUrlRequest(
    /** 업로드할 파일 타입 (예: "image/jpeg") */
    val contentType: String,

    /** 업로드 디렉토리 (예: "profile", "channel-thumbnail", "content-thumbnail", "post") */
    val directory: FileDirectory,

    /** 연관 리소스 ID (예: channelId, contentId 등) */
    val resourceId: Long? = null,
)

enum class FileDirectory(val path: String) {
    PROFILE("users"),
    CHANNEL_THUMBNAIL("channels"),
    CONTENT_THUMBNAIL("contents"),
    POST("posts"),
    ;
}
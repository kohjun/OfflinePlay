package com.contenido.global.storage

import com.contenido.global.config.S3Properties
import com.contenido.global.exception.FileUploadException
import com.contenido.global.exception.FileSizeExceededException
import com.contenido.global.exception.InvalidFileTypeException
import com.contenido.global.storage.dto.FileDirectory
import com.contenido.global.storage.dto.FileUploadResponse
import com.contenido.global.storage.dto.PresignedUrlResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectCannedACL
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class S3Service(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val s3Properties: S3Properties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 허용 Content-Type */
        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
        )

        /** 최대 파일 크기: 10MB */
        private const val MAX_FILE_SIZE = 10L * 1024 * 1024

        /** Presigned URL 유효 시간: 5분 */
        private val PRESIGNED_EXPIRY = Duration.ofMinutes(5)
    }

    // ── 서버 직접 업로드 ────────────────────────────────────────────────────

    /**
     * MultipartFile을 받아 S3에 업로드하고 공개 URL을 반환한다.
     *
     * @param file      업로드할 파일
     * @param directory 저장 디렉토리 (FileDirectory enum)
     * @param userId    업로드 요청 사용자 ID (경로에 포함)
     * @param resourceId 연관 리소스 ID (선택 — 채널/콘텐츠 ID 등)
     */
    fun upload(
        file: MultipartFile,
        directory: FileDirectory,
        userId: Long,
        resourceId: Long? = null,
    ): FileUploadResponse {
        validate(file)

        val key = buildKey(directory, userId, resourceId, extractExtension(file))
        val contentType = file.contentType ?: "application/octet-stream"

        try {
            val request = PutObjectRequest.builder()
                .bucket(s3Properties.bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(file.size)
                // public-read ACL — 버킷 정책으로 대체 가능
                // .acl(ObjectCannedACL.PUBLIC_READ)
                .build()

            s3Client.putObject(request, RequestBody.fromInputStream(file.inputStream, file.size))

            val url = "${s3Properties.baseUrl}/$key"
            log.info("S3 업로드 완료: key=$key, size=${file.size}")

            return FileUploadResponse(
                url = url,
                key = key,
                originalFilename = file.originalFilename ?: "unknown",
                size = file.size,
                contentType = contentType,
            )
        } catch (e: Exception) {
            log.error("S3 업로드 실패: key=$key", e)
            throw FileUploadException("파일 업로드 중 오류가 발생했습니다: ${e.message}")
        }
    }

    // ── Presigned URL 발급 ──────────────────────────────────────────────────

    /**
     * 프론트엔드가 S3에 직접 업로드할 수 있는 Presigned PUT URL을 발급한다.
     *
     * 흐름:
     * 1. 클라이언트 → 서버: `POST /api/v1/files/presigned-url` (contentType, directory 전달)
     * 2. 서버 → 클라이언트: presignedUrl + fileUrl 반환
     * 3. 클라이언트 → S3: presignedUrl에 PUT (Content-Type 헤더 필수)
     * 4. 클라이언트 → 서버: fileUrl을 사용하여 채널/콘텐츠 업데이트 API 호출
     */
    fun generatePresignedUrl(
        contentType: String,
        directory: FileDirectory,
        userId: Long,
        resourceId: Long? = null,
    ): PresignedUrlResponse {
        validateContentType(contentType)

        val extension = contentTypeToExtension(contentType)
        val key = buildKey(directory, userId, resourceId, extension)
        val expiresAt = Instant.now().plus(PRESIGNED_EXPIRY)

        val putObjectRequest = PutObjectRequest.builder()
            .bucket(s3Properties.bucket)
            .key(key)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .putObjectRequest(putObjectRequest)
            .signatureDuration(PRESIGNED_EXPIRY)
            .build()

        val presigned: PresignedPutObjectRequest = s3Presigner.presignPutObject(presignRequest)

        return PresignedUrlResponse(
            presignedUrl = presigned.url().toString(),
            fileUrl = "${s3Properties.baseUrl}/$key",
            key = key,
            expiresAt = expiresAt.toString(),
        )
    }

    // ── 파일 삭제 ───────────────────────────────────────────────────────────

    /**
     * S3에서 파일을 삭제한다.
     * 실패해도 비즈니스 로직에 영향을 주지 않도록 예외를 흡수하고 로그만 남긴다.
     */
    fun delete(key: String) {
        try {
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(s3Properties.bucket)
                    .key(key)
                    .build()
            )
            log.info("S3 파일 삭제 완료: key=$key")
        } catch (e: Exception) {
            // 삭제 실패는 비즈니스 처리를 막지 않음
            log.warn("S3 파일 삭제 실패 (무시): key=$key, error=${e.message}")
        }
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────

    /**
     * S3 키 생성 규칙:
     * - profile   : users/{userId}/profile/{uuid}.{ext}
     * - channel   : channels/{resourceId}/thumbnail/{uuid}.{ext}
     * - content   : contents/{resourceId}/thumbnail/{uuid}.{ext}
     * - post      : posts/{resourceId}/{uuid}.{ext}
     */
    private fun buildKey(
        directory: FileDirectory,
        userId: Long,
        resourceId: Long?,
        extension: String,
    ): String {
        val uuid = UUID.randomUUID()
        return when (directory) {
            FileDirectory.PROFILE -> "users/$userId/profile/$uuid.$extension"
            FileDirectory.CHANNEL_THUMBNAIL -> "channels/${resourceId ?: userId}/thumbnail/$uuid.$extension"
            FileDirectory.CONTENT_THUMBNAIL -> "contents/${resourceId ?: userId}/thumbnail/$uuid.$extension"
            FileDirectory.POST -> "posts/${resourceId ?: userId}/$uuid.$extension"
        }
    }

    private fun validate(file: MultipartFile) {
        if (file.isEmpty) throw FileUploadException("빈 파일은 업로드할 수 없습니다.")
        if (file.size > MAX_FILE_SIZE) throw FileSizeExceededException()
        validateContentType(file.contentType)
    }

    private fun validateContentType(contentType: String?) {
        if (contentType == null || contentType !in ALLOWED_CONTENT_TYPES) {
            throw InvalidFileTypeException()
        }
    }

    private fun extractExtension(file: MultipartFile): String {
        val original = file.originalFilename ?: return "bin"
        return original.substringAfterLast('.', "bin")
    }

    private fun contentTypeToExtension(contentType: String): String =
        when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "bin"
        }
}
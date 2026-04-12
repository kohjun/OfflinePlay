package com.contenido.global.storage.controller

import com.contenido.global.response.ApiResponse
import com.contenido.global.storage.S3Service
import com.contenido.global.storage.dto.FileDirectory
import com.contenido.global.storage.dto.FileUploadResponse
import com.contenido.global.storage.dto.PresignedUrlRequest
import com.contenido.global.storage.dto.PresignedUrlResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "File Upload", description = "S3 파일 업로드 API")
class FileUploadController(
    private val s3Service: S3Service,
) {

    // ── 방식 1: 서버 직접 업로드 ────────────────────────────────────────────

    /**
     * 서버가 파일을 받아 S3에 업로드한다. (단순하지만 서버 메모리 사용)
     *
     * 사용 예시 (curl):
     *   curl -X POST /api/v1/files/upload \
     *     -H "Authorization: Bearer {token}" \
     *     -F "file=@image.jpg" \
     *     -F "directory=PROFILE"
     */
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "파일 업로드 (서버 직접)")
    fun upload(
        @AuthenticationPrincipal userId: Long,
        @Parameter(description = "업로드할 파일 (최대 10MB, JPEG/PNG/WebP/GIF)")
        @RequestPart("file") file: MultipartFile,
        @RequestParam directory: FileDirectory,
        @RequestParam(required = false) resourceId: Long?,
    ): ApiResponse<FileUploadResponse> {
        val result = s3Service.upload(file, directory, userId, resourceId)
        return ApiResponse.created(result, "파일이 업로드되었습니다.")
    }

    // ── 방식 2: Presigned URL 발급 (권장) ──────────────────────────────────

    /**
     * 프론트엔드가 S3에 직접 PUT 업로드할 수 있는 Presigned URL을 발급한다.
     *
     * 흐름:
     * 1. 이 API 호출 → presignedUrl + fileUrl 수신
     * 2. presignedUrl에 PUT 요청으로 파일 업로드 (Content-Type 헤더 필수)
     * 3. 업로드 성공 후 fileUrl을 채널/콘텐츠 수정 API에 전달
     *
     * 사용 예시 (curl):
     *   # Step 1: URL 발급
     *   curl -X POST /api/v1/files/presigned-url \
     *     -H "Authorization: Bearer {token}" \
     *     -H "Content-Type: application/json" \
     *     -d '{"contentType":"image/jpeg","directory":"CHANNEL_THUMBNAIL","resourceId":1}'
     *
     *   # Step 2: S3에 직접 업로드
     *   curl -X PUT "{presignedUrl}" \
     *     -H "Content-Type: image/jpeg" \
     *     --data-binary @image.jpg
     */
    @PostMapping("/presigned-url")
    @Operation(summary = "Presigned URL 발급 (클라이언트 직접 업로드 권장)")
    fun getPresignedUrl(
        @AuthenticationPrincipal userId: Long,
        @RequestBody request: PresignedUrlRequest,
    ): ApiResponse<PresignedUrlResponse> {
        val result = s3Service.generatePresignedUrl(
            contentType = request.contentType,
            directory = request.directory,
            userId = userId,
            resourceId = request.resourceId,
        )
        return ApiResponse.ok(result)
    }

    // ── 파일 삭제 ───────────────────────────────────────────────────────────

    /**
     * S3에서 파일을 삭제한다.
     * 본인의 파일만 삭제할 수 있도록 key에 userId가 포함된 경우에만 허용한다.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "S3 파일 삭제")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @RequestParam key: String,
    ) {
        // 보안: key에 본인 userId 경로가 포함되어야만 삭제 허용
        // 관리자는 이 제한에서 제외 (필요 시 @PreAuthorize 로 분기)
        require(key.contains("/$userId/") || key.startsWith("users/$userId/")) {
            "본인의 파일만 삭제할 수 있습니다."
        }
        s3Service.delete(key)
    }
}
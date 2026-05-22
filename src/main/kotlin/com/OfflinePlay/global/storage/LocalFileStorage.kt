package com.contenido.global.storage

import com.contenido.global.exception.FileUploadException
import com.contenido.global.storage.dto.FileDirectory
import com.contenido.global.storage.dto.FileUploadResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * PR163 — S3 자격 증명이 fake 인 local/dev 환경에서 동작하는 디스크 파일 저장소.
 *
 *  - `storage.local-fallback.enabled=true` 일 때만 bean 으로 등록.
 *  - 운영(prod) yml 에는 명시적으로 false. 본 클래스의 응답은 `http://localhost:8080/uploads/...` URL
 *    이라 그대로 외부 노출되면 안 된다.
 *  - S3Service.upload 가 본 bean 의 존재 여부로 분기한다.
 */
@Component
@ConditionalOnProperty(name = ["storage.local-fallback.enabled"], havingValue = "true")
class LocalFileStorage(
    private val props: LocalFileStorageProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // bean 등록 시 기본 디렉터리 생성. 운영 환경에 잘못 켜져 있으면 즉시 noticeable 한 log.
        val base = Path.of(props.basePath)
        if (!Files.exists(base)) {
            Files.createDirectories(base)
        }
        log.info("[LocalFileStorage] enabled. basePath={}, publicBaseUrl={}", props.basePath, props.publicBaseUrl)
    }

    /**
     * S3Service.upload 와 동일한 응답 shape 으로 디스크에 저장. 키 형식도 S3 와 동일하게 둬서
     * 후속에 S3 로 옮길 때 URL 마이그레이션이 단순해진다.
     */
    fun upload(
        file: MultipartFile,
        directory: FileDirectory,
        userId: Long,
        resourceId: Long?,
    ): FileUploadResponse {
        val extension = file.originalFilename?.substringAfterLast('.', "bin")?.lowercase() ?: "bin"
        val uuid = UUID.randomUUID()
        val key = when (directory) {
            FileDirectory.PROFILE -> "users/$userId/profile/$uuid.$extension"
            FileDirectory.CHANNEL_THUMBNAIL -> "channels/${resourceId ?: userId}/thumbnail/$uuid.$extension"
            FileDirectory.CONTENT_THUMBNAIL -> "contents/${resourceId ?: userId}/thumbnail/$uuid.$extension"
            FileDirectory.POST -> "posts/${resourceId ?: userId}/$uuid.$extension"
        }

        val target = Path.of(props.basePath, key)
        try {
            Files.createDirectories(target.parent)
            file.inputStream.use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log.error("[LocalFileStorage] 디스크 저장 실패 key={}", key, e)
            throw FileUploadException("파일 저장 중 오류가 발생했습니다: ${e.message}")
        }

        val url = "${props.publicBaseUrl.trimEnd('/')}/$key"
        log.info("[LocalFileStorage] saved key={} size={}", key, file.size)
        return FileUploadResponse(
            url = url,
            key = key,
            originalFilename = file.originalFilename ?: "unknown",
            size = file.size,
            contentType = file.contentType ?: "application/octet-stream",
        )
    }

    fun delete(key: String) {
        val target = Path.of(props.basePath, key)
        try {
            Files.deleteIfExists(target)
        } catch (e: Exception) {
            log.warn("[LocalFileStorage] delete 실패 (무시): key={} err={}", key, e.message)
        }
    }
}

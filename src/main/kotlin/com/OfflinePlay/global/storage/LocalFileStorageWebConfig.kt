package com.contenido.global.storage

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Path

// PR163 — local fallback storage 의 디스크 파일을 /uploads 정적 자원으로 서빙.
//
// - 활성화 조건은 storage.local-fallback.enabled=true (LocalFileStorage 와 동일).
// - 운영(prod) 에서는 bean 자체가 등록 안 됨 → /uploads 경로는 SecurityConfig 의 anyRequest 에 막힘.
// - local/dev 에서는 인증 없이 이미지에 접근할 수 있도록 SecurityConfig 가 permitAll 처리 필요.
@Configuration
@ConditionalOnProperty(name = ["storage.local-fallback.enabled"], havingValue = "true")
class LocalFileStorageWebConfig(
    private val props: LocalFileStorageProperties,
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val location = Path.of(props.basePath).toAbsolutePath().toUri().toString()
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations(location)
            .setCachePeriod(0)  // dev — 캐시 안 함
    }
}

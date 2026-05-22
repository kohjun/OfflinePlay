package com.contenido.global.storage

import org.springframework.boot.context.properties.ConfigurationProperties

// PR163 — 로컬 디스크 파일 저장 설정.
//
// - enabled=true 면 S3 대신 디스크에 저장 + 정적 핸들러로 서빙. local/dev 편의 기능.
// - 운영(prod) yml 에서는 절대 true 로 두지 말 것. S3 자격 증명이 진짜로 들어 있어야 한다.
// - basePath 는 OS file system 경로. 기본은 사용자 홈 + .woya/uploads (git ignore 안전).
// - publicBaseUrl 은 응답 URL prefix. 기본 http://localhost:8080/uploads.
@ConfigurationProperties(prefix = "storage.local-fallback")
data class LocalFileStorageProperties(
    var enabled: Boolean = false,
    var basePath: String = System.getProperty("user.home") + "/.woya/uploads",
    var publicBaseUrl: String = "http://localhost:8080/uploads",
)

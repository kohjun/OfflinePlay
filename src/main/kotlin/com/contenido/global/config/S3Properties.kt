package com.contenido.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cloud.aws.s3")
data class S3Properties(
    val bucket: String,
    val region: String,
    val baseUrl: String,
)

@ConfigurationProperties(prefix = "cloud.aws.credentials")
data class AwsCredentialProperties(
    val accessKey: String,
    val secretKey: String,
)
package com.contenido.global.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@Configuration
@EnableConfigurationProperties(S3Properties::class, AwsCredentialProperties::class)
class S3Config(
    private val s3Properties: S3Properties,
    private val credentials: AwsCredentialProperties,
) {

    private fun credentialsProvider(): StaticCredentialsProvider =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(credentials.accessKey, credentials.secretKey)
        )

    @Bean
    fun s3Client(): S3Client =
        S3Client.builder()
            .region(Region.of(s3Properties.region))
            .credentialsProvider(credentialsProvider())
            .build()

    /**
     * Presigned URL 발급용 클라이언트.
     * 프론트엔드가 서버를 거치지 않고 S3에 직접 업로드할 때 사용한다.
     */
    @Bean
    fun s3Presigner(): S3Presigner =
        S3Presigner.builder()
            .region(Region.of(s3Properties.region))
            .credentialsProvider(credentialsProvider())
            .build()
}
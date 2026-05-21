package com.contenido

import com.contenido.global.config.PushNotificationProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@EnableAsync
@EnableConfigurationProperties(PushNotificationProperties::class)
@EnableJpaAuditing
@EnableRetry
@EnableScheduling
@SpringBootApplication
class ContENIDOApplication

fun main(args: Array<String>) {
    runApplication<ContENIDOApplication>(*args)
}

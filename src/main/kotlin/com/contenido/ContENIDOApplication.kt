package com.contenido

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@EnableAsync
@EnableRetry
@EnableScheduling
@SpringBootApplication
class ContENIDOApplication

fun main(args: Array<String>) {
    runApplication<ContENIDOApplication>(*args)
}

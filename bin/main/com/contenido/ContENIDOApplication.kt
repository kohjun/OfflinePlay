package com.contenido

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class ContENIDOApplication

fun main(args: Array<String>) {
    runApplication<ContENIDOApplication>(*args)
}
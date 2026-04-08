package com.contenido

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@EnableJpaAuditing
class ContENIDOApplication

fun main(args: Array<String>) {
    runApplication<ContENIDOApplication>(*args)
}

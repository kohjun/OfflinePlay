package com.contenido.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openAPI(): OpenAPI {
        val bearerAuth = SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .name("bearerAuth")

        val securityRequirement = SecurityRequirement()
            .addList("bearerAuth")

        return OpenAPI()
            .info(
                Info()
                    .title("ContENIDO API")
                    .version("v1")
                    .description("콘텐츠 플랫폼 API")
            )
            .components(
                Components()
                    .addSecuritySchemes("bearerAuth", bearerAuth)
            )
            .addSecurityItem(securityRequirement)
    }
}

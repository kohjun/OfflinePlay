package com.contenido.global.config

import com.contenido.global.jwt.JwtAuthenticationFilter
import com.contenido.global.jwt.JwtTokenProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val jwtTokenProvider: JwtTokenProvider,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()

        // TODO: 운영 환경에서는 환경변수(ALLOWED_ORIGINS)로 분리 필요
        // e.g. environment.getProperty("cors.allowed-origins").split(",")
        config.allowedOriginPatterns = listOf(
            "http://localhost:3000",  // React CRA / Next.js
            "http://localhost:5173",  // Vite
        )

        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("*")
        config.allowCredentials = true
        config.maxAge = 3600

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // Swagger UI
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // 인증 불필요 엔드포인트
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/reissue").permitAll()
                    // 콘텐츠 조회 (비로그인 허용)
                    .requestMatchers(HttpMethod.GET, "/api/v1/contents", "/api/v1/contents/**").permitAll()
                    // 채널 조회 (비로그인 허용)
                    .requestMatchers(HttpMethod.GET, "/api/v1/channels/**").permitAll()
                    // 댓글/좋아요 조회 (비로그인 허용)
                    .requestMatchers(HttpMethod.GET, "/api/v1/events/*/comments", "/api/v1/posts/*/comments").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/events/*/likes", "/api/v1/posts/*/likes").permitAll()
                    // 검색 (비로그인 허용)
                    .requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
                    // 헬스체크 / actuator
                    .requestMatchers("/actuator/health").permitAll()
                    // 그 외 모든 요청 인증 필요
                    .anyRequest().authenticated()
            }
            .addFilterBefore(
                JwtAuthenticationFilter(jwtTokenProvider, objectMapper),
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }
}

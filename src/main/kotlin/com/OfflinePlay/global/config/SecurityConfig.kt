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

        config.allowedOriginPatterns = listOf(
            "http://localhost:3000",  // React CRA / Next.js
            "http://127.0.0.1:3000",
            "http://localhost:5173",  // Vite
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174",
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
            .headers { headers ->
                headers.frameOptions { it.deny() }
                headers.contentTypeOptions { }
                headers.xssProtection { }
            }
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
                    // 이벤트 단건 flat 조회 (비로그인 허용, 알림/Studio 진입용). 단일 세그먼트만 허용해
                    // /events/{id}/participations 같은 인증 필요 경로는 영향받지 않는다.
                    .requestMatchers(HttpMethod.GET, "/api/v1/events/*").permitAll()
                    // Explore 통합 조회 (비로그인 허용)
                    .requestMatchers(HttpMethod.GET, "/api/v1/explore").permitAll()
                    // 댓글/좋아요 조회 (비로그인 허용)
                    .requestMatchers(HttpMethod.GET, "/api/v1/events/*/comments", "/api/v1/posts/*/comments").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/events/*/likes", "/api/v1/posts/*/likes").permitAll()
                    // 검색 (비로그인 허용)
                    .requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
                    // PR147 — interests / regions 카탈로그 (비로그인 허용). 개인 관심사 `/users/me/interests` 는 인증 필요.
                    .requestMatchers(HttpMethod.GET, "/api/v1/interests").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/regions").permitAll()
                    // PR148 — 추천 (비로그인 허용 — fallback segment 응답).
                    .requestMatchers(HttpMethod.GET, "/api/v1/recommendations/**").permitAll()
                    // 헬스체크 / actuator — health 트리(liveness/readiness 포함) + info 만 외부 공개.
                    // metrics/prometheus 같은 그 외 엔드포인트는 노출 자체를 management.endpoints
                    // 에서 제외하므로 별도 deny 룰 불필요. 추후 metrics 노출 시 인증 게이트 필요.
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    // 결제 webhook — PG 가 외부에서 호출. signature/HMAC 검증은 후속 PR 에서.
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()
                    // 어드민 (ADMIN 권한 필요 - 메서드 시큐리티로 추가 검증)
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
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

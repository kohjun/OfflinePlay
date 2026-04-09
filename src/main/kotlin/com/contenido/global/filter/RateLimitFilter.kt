package com.contenido.global.filter

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
@Order(1)
class RateLimitFilter : OncePerRequestFilter() {

    // 로그인: IP당 분당 10회
    private val loginBuckets = ConcurrentHashMap<String, Bucket>()

    // 회원가입: IP당 시간당 5회
    private val signupBuckets = ConcurrentHashMap<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        val method = request.method
        val ip = request.remoteAddr

        val bucket = when {
            method == "POST" && path == "/api/v1/auth/login" ->
                loginBuckets.computeIfAbsent(ip) { buildBucket(10, Duration.ofMinutes(1)) }
            method == "POST" && path == "/api/v1/auth/signup" ->
                signupBuckets.computeIfAbsent(ip) { buildBucket(5, Duration.ofHours(1)) }
            else -> null
        }

        if (bucket != null && !bucket.tryConsume(1)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"success":false,"message":"요청이 너무 많습니다. 잠시 후 다시 시도해주세요."}""")
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun buildBucket(capacity: Long, period: Duration): Bucket =
        Bucket.builder()
            .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, period)))
            .build()
}

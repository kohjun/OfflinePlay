package com.contenido.global.jwt

import com.contenido.global.exception.ExpiredTokenException
import com.contenido.global.exception.InvalidTokenException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date

class JwtTokenProviderTest {

    // 46바이트 = 368비트 (256비트 이상 조건 충족)
    private val testSecret = "dGVzdC1zZWNyZXQta2V5LW11c3QtYmUtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZw=="
    private val provider = JwtTokenProvider(
        secretBase64 = testSecret,
        accessTokenExpiration = 1800000L,
        refreshTokenExpiration = 1209600000L,
    )

    @Test
    fun `generateAccessToken 파싱 검증`() {
        val token = provider.generateAccessToken(userId = 42L, role = "CREATOR")

        assertThat(provider.getUserIdFromToken(token)).isEqualTo(42L)
        assertThat(provider.getRoleFromToken(token)).isEqualTo("CREATOR")
        assertThat(provider.validateToken(token)).isTrue()
    }

    @Test
    fun `만료된 토큰 ExpiredTokenException 발생`() {
        val expiredToken = buildExpiredToken(userId = 1L, role = "PARTICIPANT")

        assertThrows<ExpiredTokenException> {
            provider.validateToken(expiredToken)
        }
    }

    @Test
    fun `잘못된 토큰 InvalidTokenException 발생`() {
        assertThrows<InvalidTokenException> {
            provider.validateToken("invalid.jwt.token")
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private fun buildExpiredToken(userId: Long, role: String): String {
        val key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(testSecret))
        val past = Date(System.currentTimeMillis() - 10_000)  // 10초 전
        return Jwts.builder()
            .subject(userId.toString())
            .claim("userId", userId)
            .claim("role", role)
            .claim("type", "access")
            .issuedAt(Date(past.time - 1000))
            .expiration(past)
            .signWith(key)
            .compact()
    }
}

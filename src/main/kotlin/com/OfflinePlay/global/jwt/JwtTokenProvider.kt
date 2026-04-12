package com.contenido.global.jwt

import com.contenido.global.exception.ExpiredTokenException
import com.contenido.global.exception.InvalidTokenException
import io.jsonwebtoken.*
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") secretBase64: String,
    @Value("\${jwt.access-token-expiration}") private val accessTokenExpiration: Long,
    @Value("\${jwt.refresh-token-expiration}") private val refreshTokenExpiration: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val signingKey: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretBase64))

    companion object {
        private const val CLAIM_USER_ID = "userId"
        private const val CLAIM_ROLE = "role"
        private const val TOKEN_TYPE_ACCESS = "access"
        private const val TOKEN_TYPE_REFRESH = "refresh"
    }

    fun generateAccessToken(userId: Long, role: String): String =
        buildToken(userId, role, TOKEN_TYPE_ACCESS, accessTokenExpiration)

    fun generateRefreshToken(userId: Long, role: String): String =
        buildToken(userId, role, TOKEN_TYPE_REFRESH, refreshTokenExpiration)

    private fun buildToken(userId: Long, role: String, type: String, expiration: Long): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .claim(CLAIM_USER_ID, userId)
            .claim(CLAIM_ROLE, role)
            .claim("type", type)
            .issuedAt(now)
            .expiration(Date(now.time + expiration))
            .signWith(signingKey)
            .compact()
    }

    fun getUserIdFromToken(token: String): Long =
        parseClaims(token).get(CLAIM_USER_ID, Integer::class.java).toLong()

    fun getRoleFromToken(token: String): String =
        parseClaims(token).get(CLAIM_ROLE, String::class.java)

    fun validateToken(token: String): Boolean {
        parseClaims(token) // throws on invalid/expired
        return true
    }

    /**
     * 파싱 실패 시 커스텀 예외로 변환.
     * Security Filter 에서 호출되므로 절대 unchecked 예외를 밖으로 흘려보내지 않는다.
     */
    fun parseClaims(token: String): Claims =
        try {
            Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: ExpiredJwtException) {
            throw ExpiredTokenException()
        } catch (e: JwtException) {
            log.debug("JWT parse error: ${e.message}")
            throw InvalidTokenException()
        }
}

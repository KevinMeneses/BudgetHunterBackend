package com.budgethunter.util

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtUtil {
    @Value("\${jwt.secret:budgethunter-secret-key-for-jwt-token-generation-minimum-256-bits}")
    private lateinit var secret: String

    @Value("\${jwt.expiration:86400000}") // 24 hours in milliseconds
    private var expiration: Long = 86400000

    @Value("\${jwt.refresh.expiration:604800000}") // 7 days in milliseconds
    private var refreshExpiration: Long = 604800000

    private fun getSigningKey(): SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generateToken(email: String): String {
        val now = Date()
        val expiryDate = Date(now.time + expiration)

        return Jwts
            .builder()
            .subject(email)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(getSigningKey())
            .compact()
    }

    fun generateRefreshToken(): String = UUID.randomUUID().toString()

    fun getRefreshTokenExpiry(): Instant = Instant.now().plusMillis(refreshExpiration)

    fun extractEmail(token: String): String = extractClaims(token).subject

    fun isTokenValid(
        token: String,
        email: String,
    ): Boolean {
        val extractedEmail = extractEmail(token)
        return extractedEmail == email && !isTokenExpired(token)
    }

    private fun extractClaims(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .payload

    private fun isTokenExpired(token: String): Boolean = extractClaims(token).expiration.before(Date())
}

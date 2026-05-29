package io.dockistry.platform.service

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.dockistry.platform.config.JwtConfig
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class JwtTokenProvider(
    private val jwtConfig: JwtConfig,
    private val clock: Clock
) {
    fun verifier(): JWTVerifier {
        val verification = JWT.require(Algorithm.HMAC256(jwtConfig.secret))
        return (verification as JWTVerifier.BaseVerification).build(clock)
    }

    fun generateRefreshToken(
        userId: UUID,
        refreshTokenId: UUID,
        expiresAt: Instant
    ): String {

        return JWT.create()
            .withSubject(userId.toString())
            .withJWTId(refreshTokenId.toString())
            .withExpiresAt(expiresAt)
            .withClaim("typ", "Refresh")
            .sign(Algorithm.HMAC256(jwtConfig.secret))
    }

    fun generateAccessToken(
        userId: UUID,
        expiresAt: Instant
    ): String {

        return JWT.create()
            .withSubject(userId.toString())
            .withExpiresAt(expiresAt)
            .withClaim("typ", "Access")
            .sign(Algorithm.HMAC256(jwtConfig.secret))
    }

    fun validateToken(token: String): Boolean {
        try {
            verifier().verify(token)
            return true
        } catch (_: JWTVerificationException) {
            return false
        }
    }

    fun isAccessToken(token: String): Boolean {
        return try {
            JWT.decode(token).getClaim("typ").asString() == "Access"
        } catch (_: JWTVerificationException) {
            false
        }
    }

    fun isRefreshToken(token: String): Boolean {
        return try {
            JWT.decode(token).getClaim("typ").asString() == "Refresh"
        } catch (_: JWTVerificationException) {
            false
        }
    }

    fun getUserIdFromToken(token: String): String? {
        return JWT.decode(token).subject
    }

    fun getTokenIdFromToken(token: String): String? {
        return JWT.decode(token).getClaim("jti").asString()
    }
}

package io.dockistry.platform.auth.application

import io.dockistry.platform.auth.domain.InvalidTokenException
import io.dockistry.platform.auth.domain.RefreshToken
import io.dockistry.platform.auth.domain.TokenNotFoundException
import io.dockistry.platform.auth.domain.TokenRevokedException
import io.dockistry.platform.auth.domain.UserForAuthenticationNotFoundException
import io.dockistry.platform.auth.domain.dto.TokenPair
import io.dockistry.platform.auth.infrastructure.JwtConfig
import io.dockistry.platform.auth.infrastructure.RefreshTokenRepository
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.UserRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class TokenManagementService(
    private val userRepository: UserRepository,
    private val tokenProvider: JwtTokenProvider,
    private val jwtConfig: JwtConfig,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock
) {

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(TokenManagementService::class.java)
    }

    @Transactional
    fun issueTokenPair(username: String): TokenPair {
        val user = userRepository.findUserByUsername(username) ?: throw UserForAuthenticationNotFoundException("User with username $username not found")
        return issueTokenPair(user)
    }

    @Transactional
    fun issueTokenPair(userId: UUID): TokenPair {
        val user = userRepository.findUserById(userId) ?: throw UserForAuthenticationNotFoundException("User with ID $userId not found")
        return issueTokenPair(user)
    }

    private fun issueTokenPair(user: User): TokenPair {
        val refreshTokenId = UUID.randomUUID()
        val now = clock.instant()
        val refreshExpiresAt = now.plusMillis(jwtConfig.refreshExpiration)
        val accessExpiresAt = now.plusMillis(jwtConfig.accessExpiration)

        val refreshToken = tokenProvider.generateRefreshToken(
            userId = user.id,
            refreshTokenId = refreshTokenId,
            expiresAt = refreshExpiresAt
        )
        val accessToken = tokenProvider.generateAccessToken(
            userId = user.id,
            expiresAt = accessExpiresAt
        )

        refreshTokenRepository.save(
            RefreshToken(
                id = refreshTokenId,
                issuedAt = now,
                expiresAt = refreshExpiresAt
            ).apply {
                this.user = user
            }
        )

        return TokenPair(
            refreshToken = refreshToken,
            refreshExpiresAt = refreshExpiresAt.toEpochMilli(),
            accessToken = accessToken,
            accessExpiresAt = accessExpiresAt.toEpochMilli()
        )
    }

    // Prevents a situation where old token is revoked but new wasn't issued due to some error
    @Transactional
    fun refreshToken(token: String): TokenPair {
        val parsedToken = parseRefreshToken(token)
        val refreshToken = findRefreshToken(parsedToken.tokenId)

        throwIfRefreshTokenUserMismatches(
            refreshToken = refreshToken,
            userIdFromToken = parsedToken.userId
        )

        revokeRefreshToken(refreshToken)
        flushRefreshTokenRevocation()

        return issueTokenPair(refreshToken.user)
    }

    @Transactional
    fun invalidateRefreshToken(token: String) {
        val parsed = parseRefreshToken(token)

        try {
            val refreshToken = findRefreshToken(parsed.tokenId)
            revokeRefreshToken(refreshToken)
            flushRefreshTokenRevocation()
        } catch (_: TokenRevokedException) {
            logger.warn(
                "Logout requested for already-revoked refresh token: tokenId={}",
                parsed.tokenId
            )
        } catch (_: TokenNotFoundException) {
            logger.warn(
                "Logout requested for refresh token not found in DB: tokenId={}",
                parsed.tokenId
            )
        }
    }

    // Not needed for logout anymore since we have invalidateRefreshToken, but may be useful in future
    @Transactional
    fun revokeRefreshToken(token: String) {
        val parsedToken = parseRefreshToken(token)
        val refreshToken = findRefreshToken(parsedToken.tokenId)

        revokeRefreshToken(refreshToken)
        flushRefreshTokenRevocation()
    }

    @Transactional
    fun revokeAllRefreshTokens(user: User) {
        refreshTokenRepository.findByUserAndRevokedAtNull(user).forEach {
            try {
                revokeRefreshToken(it)
                flushRefreshTokenRevocation()
            } catch (_: TokenRevokedException) {
                logger.warn("${it.id} refresh token was revoked in parallel with logout-all operation on user ${user.id}")
            }
        }
    }

    private fun parseRefreshToken(token: String): ParsedRefreshToken {
        if (!tokenProvider.validateToken(token) || !tokenProvider.isRefreshToken(token)) {
            throw InvalidTokenException("Token is invalid or of wrong type")
        }

        return try {
            ParsedRefreshToken(
                tokenId = UUID.fromString(tokenProvider.getTokenIdFromToken(token)),
                userId = UUID.fromString(tokenProvider.getUserIdFromToken(token))
            )
        } catch (_: RuntimeException) {
            // NullPointerException or IllegalArgumentException
            throw InvalidTokenException("Couldn't parse user/token UUID")
        }
    }

    private fun findRefreshToken(tokenId: UUID): RefreshToken {
        return refreshTokenRepository.findRefreshTokenById(tokenId)
            ?: throw TokenNotFoundException()
    }

    private fun revokeRefreshToken(refreshToken: RefreshToken) {
        if (refreshToken.revokedAt != null) {
            throw TokenRevokedException()
        }

        refreshToken.revokedAt = clock.instant()
    }

    private fun flushRefreshTokenRevocation() {
        try {
            refreshTokenRepository.flush()
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw TokenRevokedException()
        }
    }

    private fun throwIfRefreshTokenUserMismatches(
        refreshToken: RefreshToken,
        userIdFromToken: UUID
    ) {
        if (refreshToken.user.id != userIdFromToken) {
            throw InternalDataIntegrityException(
                "Refresh token subject $userIdFromToken does not match DB owner ${refreshToken.user.id}"
            )
        }
    }

    private data class ParsedRefreshToken(
        val tokenId: UUID,
        val userId: UUID
    )
}

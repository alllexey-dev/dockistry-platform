package io.dockistry.platform.service

import io.dockistry.platform.config.JwtConfig
import io.dockistry.platform.exception.InvalidTokenException
import io.dockistry.platform.exception.TokenNotFoundException
import io.dockistry.platform.exception.TokenRevokedException
import io.dockistry.platform.exception.UserNotFoundException
import io.dockistry.platform.model.RefreshToken
import io.dockistry.platform.model.TokenPair
import io.dockistry.platform.model.User
import io.dockistry.platform.repository.RefreshTokenRepository
import io.dockistry.platform.repository.UserRepository
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
    @Transactional
    fun issueTokenPair(username: String): TokenPair {
        val user = userRepository.findUserByUsername(username) ?: throw UserNotFoundException("user with username $username not found")
        return issueTokenPair(user)
    }

    @Transactional
    fun issueTokenPair(userId: UUID): TokenPair {
        val user = userRepository.findUserById(userId) ?: throw UserNotFoundException("user with ID $userId not found")
        return issueTokenPair(user)
    }

    private fun issueTokenPair(user: User): TokenPair {
        val refreshTokenId = UUID.randomUUID()
        val now = clock.instant()
        val refreshExpiresAt = now.plusMillis(jwtConfig.refreshExpiration)

        val refreshToken = tokenProvider.generateRefreshToken(
            userId = user.id,
            refreshTokenId = refreshTokenId,
            expiresAt = refreshExpiresAt
        )
        val accessToken = tokenProvider.generateAccessToken(
            userId = user.id,
            expiresAt = now.plusMillis(jwtConfig.accessExpiration)
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
            accessToken = accessToken
        )
    }

    // Prevents a situation where old token is revoked but new wasn't issued due to some error
    @Transactional
    fun refreshToken(token: String): TokenPair {
        if (!tokenProvider.validateToken(token) || !tokenProvider.isRefreshToken(token)) {
            throw InvalidTokenException("token is invalid or of wrong type")
        }

        var userId: UUID
        var tokenId: UUID

        try {
            tokenId = UUID.fromString(tokenProvider.getTokenIdFromToken(token))
            userId = UUID.fromString(tokenProvider.getUserIdFromToken(token))
        } catch (_: RuntimeException) {
            // NullPointerException or IllegalArgumentException
            throw InvalidTokenException("couldn't parse user/token UUID")
        }

        revokeRefreshToken(tokenId, userId)

        try {
            refreshTokenRepository.flush()
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw TokenRevokedException()
        }

        return issueTokenPair(userId)
    }

    private fun revokeRefreshToken(tokenId: UUID, userId: UUID) {
        val refreshToken = refreshTokenRepository.findRefreshTokenById(tokenId)
            ?: throw TokenNotFoundException()

        if (refreshToken.user?.id != userId) {
            throw InvalidTokenException("token user mismatch")
        }

        if (refreshToken.revokedAt != null) {
            throw TokenRevokedException()
        }

        refreshToken.revokedAt = clock.instant()
    }
}

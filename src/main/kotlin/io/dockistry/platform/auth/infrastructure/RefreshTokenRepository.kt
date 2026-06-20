package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.auth.domain.RefreshToken
import io.dockistry.platform.iam.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findRefreshTokenById(id: UUID): RefreshToken?
    fun findByUserAndRevokedAtNull(user: User): MutableList<RefreshToken>
}
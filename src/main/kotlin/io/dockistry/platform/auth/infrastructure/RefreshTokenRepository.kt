package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.auth.domain.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findRefreshTokenById(id: UUID): RefreshToken?
}
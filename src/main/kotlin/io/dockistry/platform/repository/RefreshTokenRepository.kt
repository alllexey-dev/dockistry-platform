package io.dockistry.platform.repository

import io.dockistry.platform.model.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findRefreshTokenById(id: UUID): RefreshToken?
}

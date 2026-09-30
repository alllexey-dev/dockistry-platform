package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.SecretVersion
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SecretVersionRepository : JpaRepository<SecretVersion, String> {
    fun findByIdIn(versionIds: Collection<String>): List<SecretVersion>

    // Ordering by version as a deterministic tie-braker.
    @Query(
        """
            SELECT DISTINCT ON (version.secret_id) *
            FROM secret_versions version
            WHERE version.secret_id = :secretId
            ORDER BY version.secret_id, version.created_at DESC, version.id
    """,
        nativeQuery = true
    )
    fun findLatestBySecretId(secretId: String): SecretVersion

    // Ordering by version as a deterministic tie-braker.
    @Query(
        """
            SELECT DISTINCT ON (version.secret_id) *
            FROM secret_versions version
            WHERE version.secret_id IN (:secretIds)
            ORDER BY version.secret_id, version.created_at DESC, version.id
    """, nativeQuery = true
    )
    fun findLatestBySecretIdIn(secretIds: Collection<String>): List<SecretVersion>
}

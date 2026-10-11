package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.domain.Secret
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface SecretRepository : JpaRepository<Secret, String> {
    @Query("""
        SELECT DISTINCT secret
        FROM Secret secret
        LEFT JOIN FETCH secret.versions
        WHERE secret.ownerPrincipal = :ownerPrincipal
          AND secret.name = :name
    """)
    fun findByOwnerAndName(
        ownerPrincipal: Principal,
        name: String
    ): Secret?

    @Query("""
        SELECT DISTINCT secret
        FROM Secret secret
        LEFT JOIN FETCH secret.versions
        WHERE secret.ownerPrincipal = :ownerPrincipal
        ORDER BY secret.name
    """)
    fun listByOwnerPrincipal(ownerPrincipal: Principal): List<Secret>

    @Query(
        """
        SELECT *
        FROM secrets
        WHERE id = :secretId
        FOR NO KEY UPDATE
    """, nativeQuery = true
    )
    fun findByIdForNoKeyUpdate(secretId: String): Secret?

    /* Database cascades remove versions and canonical references of the Secret. */
    @Modifying
    @Query("""
        DELETE FROM Secret secret
        WHERE secret.id = :secretId
    """)
    fun deleteLogicalSecret(secretId: String): Int
}

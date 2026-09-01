package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.domain.Secret
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
interface SecretRepository : JpaRepository<Secret, String> {
    @Query("""
        SELECT DISTINCT secret
        FROM Secret secret
        LEFT JOIN FETCH secret.versions
        WHERE secret.ownerPrincipal = :ownerPrincipal
          AND secret.name = :name
    """)
    fun getByOwnerAndName(
        @Param("ownerPrincipal") ownerPrincipal: Principal,
        @Param("name") name: String
    ): Secret?

    @Query("""
        SELECT secret.ownerPrincipal.id
        FROM Secret secret
        WHERE secret.id = :secretId
    """)
    fun getOwnerIdById(@Param("secretId") secretId: String): UUID?

    @Query("""
        SELECT DISTINCT secret
        FROM Secret secret
        LEFT JOIN FETCH secret.versions
        WHERE secret.ownerPrincipal = :ownerPrincipal
        ORDER BY secret.name
    """)
    fun listByOwnerPrincipal(@Param("ownerPrincipal") ownerPrincipal: Principal): List<Secret>
}

package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.domain.Secret
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
interface SecretRepository : JpaRepository<Secret, String> {
    @Query("""
        SELECT secret.ownerPrincipal.id
        FROM Secret secret
        WHERE secret.id = :secretId
    """)
    fun getOwnerIdById(@Param("secretId") secretId: String): UUID?

    @Query("""
        SELECT secret
        FROM Secret secret
        WHERE secret.ownerPrincipal = :ownerPrincipal
        ORDER BY secret.name
    """)
    fun listByOwnerPrincipal(@Param("ownerPrincipal") ownerPrincipal: Principal): List<Secret>
}

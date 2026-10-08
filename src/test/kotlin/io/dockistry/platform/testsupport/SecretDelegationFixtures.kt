package io.dockistry.platform.testsupport

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID

class SecretDelegationFixtures(private val entityManager: EntityManager) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    fun secret(owner: Principal): Secret {
        val secret = Secret(
            name = "secret-${UUID.randomUUID().toString().take(8)}",
            ownerPrincipal = owner,
            createdAt = now,
            updatedAt = now
        )
        secret.addVersion(SecretVersion(secret, mapOf("password" to "ciphertext"), now))
        entityManager.persist(secret)
        return secret
    }

    fun delegate(owner: Principal, project: Project): ProjectSecretDelegation =
        delegate(secret(owner), project)

    fun delegate(secret: Secret, project: Project): ProjectSecretDelegation {
        val delegation = ProjectSecretDelegation(secret, project, now, now)
        entityManager.persist(delegation)
        return delegation
    }
}

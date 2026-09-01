package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import org.springframework.data.jpa.repository.JpaRepository

interface SecretVersionRepository : JpaRepository<SecretVersion, String> {
    fun findBySecretOrderByCreatedAtDescIdDesc(secret: Secret): List<SecretVersion>

    fun findFirstBySecretOrderByCreatedAtDescIdDesc(secret: Secret): SecretVersion?
}

package io.dockistry.platform.secrets.application

import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.dto.SecretUsageDto

internal fun ManagedServiceSecretReferenceEntity.toUsageDto(): SecretUsageDto = SecretUsageDto(
    projectId = service.project.id,
    projectName = service.project.name,
    serviceId = service.id,
    serviceName = service.name,
    secretId = secret.id,
    key = secretKey,
    envName = envName,
    secretVersion = secretVersion
)

package io.dockistry.platform.secrets.application

import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.dto.SecretUsageDto
import io.dockistry.platform.secrets.domain.dto.SecretVersionDto

// Same order as latest resolution: created_at DESC with the version ID as tie-breaker.
private val NEWEST_VERSION_FIRST: Comparator<SecretVersion> =
    compareByDescending<SecretVersion> { it.createdAt }.thenBy { it.id }

internal fun SecretVersion.toVersionDto(): SecretVersionDto = SecretVersionDto(
    version = id,
    createdAt = createdAt,
    keys = encryptedValues.keys.toSortedSet()
)

internal fun Collection<SecretVersion>.toVersionDtos(): List<SecretVersionDto> =
    sortedWith(NEWEST_VERSION_FIRST).map { it.toVersionDto() }

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

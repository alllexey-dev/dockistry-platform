package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProjectSecretDelegationRepository : JpaRepository<ProjectSecretDelegation, UUID>

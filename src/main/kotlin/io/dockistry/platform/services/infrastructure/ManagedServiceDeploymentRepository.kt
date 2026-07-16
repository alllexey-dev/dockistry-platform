package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.services.domain.ManagedServiceDeployment
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ManagedServiceDeploymentRepository :
    JpaRepository<ManagedServiceDeployment, UUID>

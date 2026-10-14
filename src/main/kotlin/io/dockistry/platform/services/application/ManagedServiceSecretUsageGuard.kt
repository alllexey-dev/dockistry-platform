package io.dockistry.platform.services.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessService
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.secrets.domain.SecretPermissionRequiredException
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ManagedServiceSecretUsageGuard(
    private val projectAccessService: ProjectAccessService
) {
    fun requireSecretsUsable(
        project: Project,
        principal: Principal,
        resultingReferences: List<ManagedServiceSecretReference>,
        currentReferences: List<ManagedServiceSecretReference> = emptyList()
    ) {
        if (resultingReferences.isEmpty() && currentReferences.isEmpty()) {
            return
        }

        if (!projectAccessService.hasSecretsUsePermission(
                scope = project.scope,
                principal = principal
            )
        ) {
            throw SecretPermissionRequiredException(
                "Permission project.secrets.use is required because the current or " +
                    "resulting service configuration references Secrets"
            )
        }
    }
}
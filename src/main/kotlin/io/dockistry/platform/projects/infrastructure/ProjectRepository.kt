package io.dockistry.platform.projects.infrastructure

import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.projects.domain.Project
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProjectRepository : JpaRepository<Project, UUID> {
    fun findProjectById(id: UUID): Project?
    fun findByScope(scope: Scope): Project?
    fun existsByScope(scope: Scope): Boolean
}

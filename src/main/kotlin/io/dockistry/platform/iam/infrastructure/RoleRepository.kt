package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.projects.domain.Project
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RoleRepository : JpaRepository<Role, UUID> {
    fun findByNameAndIsSystemTrue(name: String): Role?
    fun existsByNameAndIsSystemTrue(name: String): Boolean
    fun existsByNameAndProject(name: String, project: Project): Boolean
}

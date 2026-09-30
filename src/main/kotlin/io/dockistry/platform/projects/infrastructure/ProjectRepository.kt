package io.dockistry.platform.projects.infrastructure

import io.dockistry.platform.projects.domain.Project
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ProjectRepository : JpaRepository<Project, UUID> {
    fun findProjectById(id: UUID): Project?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Project project
        SET project.name = :name,
            project.description = :description,
            project.version = project.version + 1
        WHERE project.id = :projectId
          AND project.version = :expectedVersion
    """)
    fun updateWithExpectedVersion(
        projectId: UUID,
        name: String,
        description: String?,
        expectedVersion: Long
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM Project project
        WHERE project.id = :projectId
    """)
    fun deleteIgnoringVersion(
        projectId: UUID
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Project project
        SET project.status = ProjectStatus.ARCHIVED,
            project.version = project.version + 1
        WHERE project.id = :projectId
          AND project.status <> ProjectStatus.ARCHIVED
    """)
    fun archive(
        projectId: UUID
    ): Int

    @Query("""
        SELECT CASE
            WHEN project.status = ProjectStatus.ARCHIVED THEN TRUE
            ELSE FALSE
        END
        FROM Project project
        WHERE project.id = :projectId
    """)
    fun isArchived(
        projectId: UUID
    ): Boolean?

    @Query("""
        SELECT p
            FROM Project p
            WHERE p.scope.id in :scopeIds
    """)
    fun findAllByScopeIdIn(scopeIds: Set<UUID>): List<Project>
}

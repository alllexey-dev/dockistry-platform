package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.projects.application.ports.ProjectManagedServicesExistencePort
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.services.domain.ManagedService
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ManagedServiceRepository :
    JpaRepository<ManagedService, UUID>,
    ProjectManagedServicesExistencePort {
    fun existsByProjectIdAndName(projectId: UUID, name: String): Boolean

    @Query("""
        SELECT service
            FROM ManagedService service
            WHERE service.project.id = :projectId
    """)
    fun findAllByProjectId(@Param("projectId") projectId: UUID): List<ManagedService>

    @Query("""
        SELECT EXISTS (
            SELECT service
            FROM ManagedService service
            WHERE service.project.id = :projectId
        )
    """)
    override fun existsByProjectId(@Param("projectId") projectId: UUID): Boolean

    // Checking not only by PK, but also by Project ID, so that at the same time we're
    // also checking that this service belongs to specified project
    @Query("""
        SELECT service
            FROM ManagedService service
            WHERE service.project.id = :projectId
              AND service.id = :serviceId
    """)
    fun findByProjectIdAndId(
        @Param("projectId") projectId: UUID,
        @Param("serviceId") serviceId: UUID
    ): ManagedService?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM ManagedService service
            WHERE service.project.id = :projectId
              AND service.id = :serviceId
    """)
    fun deleteByProjectIdAndId(
        @Param("projectId") projectId: UUID,
        @Param("serviceId") serviceId: UUID
    ): Int

    fun project(project: Project): MutableList<ManagedService>
}

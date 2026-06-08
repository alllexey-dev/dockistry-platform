package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class ProjectAccessServiceIntegrationTests {
    @Autowired
    private lateinit var projectAccessService: ProjectAccessService

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `can create requires global project create permission`() {
        val fixtures = IamProjectFixtures(entityManager)
        val globalCreator = fixtures.principal()
        val projectScopedCreator = fixtures.principal()
        fixtures.grant(globalCreator, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE)
        fixtures.grant(projectScopedCreator, fixtures.scope(ScopeType.PROJECT), SystemPermissionNames.PROJECT_CREATE)
        fixtures.flush()

        assertTrue(projectAccessService.canCreate(globalCreator))
        assertFalse(projectAccessService.canCreate(projectScopedCreator))
    }

    @Test
    fun `can read accepts project scoped or global read permission`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val projectReader = fixtures.principal()
        val globalReader = fixtures.principal()
        val noAccess = fixtures.principal()
        fixtures.grant(projectReader, project.scope, SystemPermissionNames.PROJECT_READ)
        fixtures.grant(globalReader, fixtures.globalScope(), SystemPermissionNames.PROJECT_READ)
        fixtures.flush()

        assertTrue(projectAccessService.canRead(project.scope, projectReader))
        assertTrue(projectAccessService.canRead(project.scope, globalReader))
        assertFalse(projectAccessService.canRead(project.scope, noAccess))
    }

    @Test
    fun `can update requires read and update permissions`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val updater = fixtures.principal()
        val updateWithoutRead = fixtures.principal()
        fixtures.grant(
            updater,
            project.scope,
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_UPDATE
        )
        fixtures.grant(updateWithoutRead, project.scope, SystemPermissionNames.PROJECT_UPDATE)
        fixtures.flush()

        assertTrue(projectAccessService.canUpdate(project.scope, updater))
        assertFalse(projectAccessService.canUpdate(project.scope, updateWithoutRead))
    }

    @Test
    fun `can archive requires read and archive permissions`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val archiver = fixtures.principal()
        val archiveWithoutRead = fixtures.principal()
        fixtures.grant(
            archiver,
            project.scope,
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_ARCHIVE
        )
        fixtures.grant(archiveWithoutRead, project.scope, SystemPermissionNames.PROJECT_ARCHIVE)
        fixtures.flush()

        assertTrue(projectAccessService.canArchive(project.scope, archiver))
        assertFalse(projectAccessService.canArchive(project.scope, archiveWithoutRead))
    }

    @Test
    fun `can delete requires read and delete permissions`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val deleter = fixtures.principal()
        val deleteWithoutRead = fixtures.principal()
        fixtures.grant(
            deleter,
            project.scope,
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_DELETE
        )
        fixtures.grant(deleteWithoutRead, project.scope, SystemPermissionNames.PROJECT_DELETE)
        fixtures.flush()

        assertTrue(projectAccessService.canDelete(project.scope, deleter))
        assertFalse(projectAccessService.canDelete(project.scope, deleteWithoutRead))
    }
}

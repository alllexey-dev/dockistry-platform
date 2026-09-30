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
    fun `has create permission requires global project create permission`() {
        val fixtures = IamProjectFixtures(entityManager)
        val globalCreator = fixtures.principal()
        val projectScopedCreator = fixtures.principal()
        fixtures.grantSystemPermission(globalCreator, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE.key)
        fixtures.grantSystemPermission(
            projectScopedCreator,
            fixtures.scope(ScopeType.PROJECT),
            SystemPermissionNames.PROJECT_CREATE.key
        )
        fixtures.flush()

        assertTrue(projectAccessService.hasCreatePermission(globalCreator))
        assertFalse(projectAccessService.hasCreatePermission(projectScopedCreator))
    }

    @Test
    fun `has read permission accepts project scoped or global read permission`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val projectReader = fixtures.principal()
        val globalReader = fixtures.principal()
        val noAccess = fixtures.principal()
        fixtures.grantSystemPermission(projectReader, project.scope, SystemPermissionNames.PROJECT_READ.key)
        fixtures.grantSystemPermission(globalReader, fixtures.globalScope(), SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertTrue(projectAccessService.hasReadPermission(project.scope, projectReader))
        assertTrue(projectAccessService.hasReadPermission(project.scope, globalReader))
        assertFalse(projectAccessService.hasReadPermission(project.scope, noAccess))
    }

    @Test
    fun `has read permission accepts project scoped permission granted through user group`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val group = fixtures.group()
        val user = fixtures.user()
        user.groups += group
        fixtures.grantSystemPermission(group.principal, project.scope, SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertTrue(projectAccessService.hasReadPermission(project.scope, user.principal))
    }

    @Test
    fun `has update permission checks update permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val updater = fixtures.principal()
        val noUpdate = fixtures.principal()
        fixtures.grantSystemPermission(
            updater,
            project.scope,
            SystemPermissionNames.PROJECT_UPDATE.key
        )
        fixtures.grantSystemPermission(noUpdate, project.scope, SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertTrue(projectAccessService.hasUpdatePermission(project.scope, updater))
        assertFalse(projectAccessService.hasUpdatePermission(project.scope, noUpdate))
    }

    @Test
    fun `has archive permission checks archive permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val archiver = fixtures.principal()
        val noArchive = fixtures.principal()
        fixtures.grantSystemPermission(
            archiver,
            project.scope,
            SystemPermissionNames.PROJECT_ARCHIVE.key
        )
        fixtures.grantSystemPermission(noArchive, project.scope, SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertTrue(projectAccessService.hasArchivePermission(project.scope, archiver))
        assertFalse(projectAccessService.hasArchivePermission(project.scope, noArchive))
    }

    @Test
    fun `has delete permission checks delete permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val deleter = fixtures.principal()
        val noDelete = fixtures.principal()
        fixtures.grantSystemPermission(
            deleter,
            project.scope,
            SystemPermissionNames.PROJECT_DELETE.key
        )
        fixtures.grantSystemPermission(noDelete, project.scope, SystemPermissionNames.PROJECT_READ.key)
        fixtures.flush()

        assertTrue(projectAccessService.hasDeletePermission(project.scope, deleter))
        assertFalse(projectAccessService.hasDeletePermission(project.scope, noDelete))
    }

}

package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.Scope
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

    @Autowired
    private lateinit var permissionCheckService: PermissionCheckService

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

    @Test
    fun `has member management permission checks members manage permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val manager = fixtures.principal()
        val noManagement = fixtures.principal()
        fixtures.grantSystemPermission(
            manager,
            project.scope,
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE.key
        )
        grantAllOtherPermissions(
            fixtures,
            noManagement,
            project.scope,
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE.key
        )
        fixtures.flush()

        assertTrue(
            projectAccessService.hasMemberManagementPermission(
                project.scope,
                manager
            )
        )
        assertFalse(
            projectAccessService.hasMemberManagementPermission(
                project.scope,
                noManagement
            )
        )
    }

    @Test
    fun `has project services manage permission checks services manage permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val manager = fixtures.principal()
        val noManagement = fixtures.principal()
        fixtures.grantSystemPermission(
            manager,
            project.scope,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )
        grantAllOtherPermissions(
            fixtures,
            noManagement,
            project.scope,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )
        fixtures.flush()

        assertTrue(
            projectAccessService.hasProjectServicesManagePermission(
                project.scope,
                manager
            )
        )
        assertFalse(
            projectAccessService.hasProjectServicesManagePermission(
                project.scope,
                noManagement
            )
        )
    }

    @Test
    fun `has secrets delegate permission checks secrets delegate permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val delegator = fixtures.principal()
        val noDelegation = fixtures.principal()
        fixtures.grantSystemPermission(
            delegator,
            project.scope,
            SystemPermissionNames.SECRETS_DELEGATE.key
        )
        grantAllOtherPermissions(
            fixtures,
            noDelegation,
            project.scope,
            SystemPermissionNames.SECRETS_DELEGATE.key
        )
        fixtures.flush()

        assertTrue(
            projectAccessService.hasSecretsDelegatePermission(
                project.scope,
                delegator
            )
        )
        assertFalse(
            projectAccessService.hasSecretsDelegatePermission(
                project.scope,
                noDelegation
            )
        )
    }

    @Test
    fun `has secrets use permission checks secrets use permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val user = fixtures.principal()
        val noUse = fixtures.principal()
        fixtures.grantSystemPermission(
            user,
            project.scope,
            SystemPermissionNames.SECRETS_USE.key
        )
        grantAllOtherPermissions(
            fixtures,
            noUse,
            project.scope,
            SystemPermissionNames.SECRETS_USE.key
        )
        fixtures.flush()

        assertTrue(projectAccessService.hasSecretsUsePermission(project.scope, user))
        assertFalse(projectAccessService.hasSecretsUsePermission(project.scope, noUse))
    }

    @Test
    fun `has secrets force revoke permission checks force revoke permission only`() {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val revoker = fixtures.principal()
        val noRevocation = fixtures.principal()
        fixtures.grantSystemPermission(
            revoker,
            project.scope,
            SystemPermissionNames.SECRETS_FORCE_REVOKE.key
        )
        grantAllOtherPermissions(
            fixtures,
            noRevocation,
            project.scope,
            SystemPermissionNames.SECRETS_FORCE_REVOKE.key
        )
        fixtures.flush()

        assertTrue(
            permissionCheckService.hasPermission(
                SystemPermissionNames.SECRETS_FORCE_REVOKE.key,
                revoker.id,
                project.scope.id
            )
        )
        assertFalse(
            permissionCheckService.hasPermission(
                SystemPermissionNames.SECRETS_FORCE_REVOKE.key,
                noRevocation.id,
                project.scope.id
            )
        )
    }

    @Test
    fun `has generic containers manage permission checks manage permission only`() {
        assertOnlyGlobalPermission(SystemPermissionNames.DOCKER_GENERIC_CONTAINERS_MANAGE.key)
    }

    @Test
    fun `has docker images read permission checks read permission only`() {
        assertOnlyGlobalPermission(SystemPermissionNames.DOCKER_IMAGES_READ.key)
    }

    @Test
    fun `has docker images manage permission checks manage permission only`() {
        assertOnlyGlobalPermission(SystemPermissionNames.DOCKER_IMAGES_MANAGE.key)
    }

    private fun assertOnlyGlobalPermission(requiredPermission: String) {
        val fixtures = IamProjectFixtures(entityManager)
        val globalScope = fixtures.globalScope()
        val allowed = fixtures.principal()
        val missingRequired = fixtures.principal()
        fixtures.grantSystemPermission(allowed, globalScope, requiredPermission)
        grantAllOtherPermissions(fixtures, missingRequired, globalScope, requiredPermission)
        fixtures.flush()

        assertTrue(permissionCheckService.hasGlobalPermission(requiredPermission, allowed.id))
        assertFalse(
            permissionCheckService.hasGlobalPermission(requiredPermission, missingRequired.id)
        )
    }

    private fun grantAllOtherPermissions(
        fixtures: IamProjectFixtures,
        principal: Principal,
        scope: Scope,
        requiredPermission: String
    ) {
        fixtures.grantSystemPermission(
            principal,
            scope,
            *(SystemPermissionNames.entries.map { it.key }.toSet() - requiredPermission).toTypedArray()
        )
    }
}

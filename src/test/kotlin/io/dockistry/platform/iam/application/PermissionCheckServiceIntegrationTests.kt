package io.dockistry.platform.iam.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class PermissionCheckServiceIntegrationTests {
    @Autowired
    private lateinit var permissionCheckService: PermissionCheckService

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `has global permission returns true only for matching permission on global scope`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        fixtures.grantSystemPermission(principal, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE)
        fixtures.flush()

        assertTrue(permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_CREATE, principal.id))
        assertFalse(permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_READ, principal.id))
    }

    @Test
    fun `has global permission ignores project scoped binding`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        fixtures.grantSystemPermission(principal, fixtures.scope(ScopeType.PROJECT), SystemPermissionNames.PROJECT_CREATE)
        fixtures.flush()

        assertFalse(permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_CREATE, principal.id))
    }

    @Test
    fun `has permission returns true for matching permission in exact scope`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val projectScope = fixtures.scope(ScopeType.PROJECT)
        fixtures.grantSystemPermission(principal, projectScope, SystemPermissionNames.PROJECT_READ)
        fixtures.flush()

        assertTrue(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ,
            principal.id,
            projectScope.id
        ))
    }

    @Test
    fun `has permission returns true for global permission against concrete scope`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val projectScope = fixtures.scope(ScopeType.PROJECT)
        fixtures.grantSystemPermission(principal, fixtures.globalScope(), SystemPermissionNames.PROJECT_READ)
        fixtures.flush()

        assertTrue(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ,
            principal.id,
            projectScope.id
        ))
    }

    @Test
    fun `has permission returns false for different scope or missing permission`() {
        val fixtures = IamProjectFixtures(entityManager)
        val principal = fixtures.principal()
        val grantedScope = fixtures.scope(ScopeType.PROJECT)
        val otherScope = fixtures.scope(ScopeType.PROJECT)
        fixtures.grantSystemPermission(principal, grantedScope, SystemPermissionNames.PROJECT_READ)
        fixtures.flush()

        assertFalse(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_READ,
            principal.id,
            otherScope.id
        ))
        assertFalse(permissionCheckService.hasPermission(
            SystemPermissionNames.PROJECT_UPDATE,
            principal.id,
            grantedScope.id
        ))
    }

    @Test
    fun `permission checks throw internal data integrity exception when principal is missing`() {
        assertFailsWith<InternalDataIntegrityException> {
            permissionCheckService.hasGlobalPermission(SystemPermissionNames.PROJECT_READ, UUID.randomUUID())
        }
    }
}

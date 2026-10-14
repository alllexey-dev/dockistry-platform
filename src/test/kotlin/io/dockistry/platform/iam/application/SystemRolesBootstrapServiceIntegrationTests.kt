package io.dockistry.platform.iam.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.infrastructure.PermissionRepository
import io.dockistry.platform.iam.infrastructure.RoleRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class SystemRolesBootstrapServiceIntegrationTests {
    @Autowired
    private lateinit var bootstrapService: SystemRolesBootstrapService

    @Autowired
    private lateinit var permissionRepository: PermissionRepository

    @Autowired
    private lateinit var roleRepository: RoleRepository

    @Test
    fun `bootstrap creates project permissions and system roles`() {
        val admin = role(SystemRoleNames.PROJECT_ADMIN.key)
        val operator = role(SystemRoleNames.PROJECT_OPERATOR.key)
        val viewer = role(SystemRoleNames.PROJECT_VIEWER.key)

        assertEquals(
            SystemRoleNames.PROJECT_ADMIN.permissionNames.map { it.key }.toSet(),
            admin.permissions.map { it.key }.toSet()
        )
        assertEquals(
            SystemRoleNames.PROJECT_OPERATOR.permissionNames.map { it.key }.toSet(),
            operator.permissions.map { it.key }.toSet()
        )
        assertEquals(
            SystemRoleNames.PROJECT_VIEWER.permissionNames.map { it.key }.toSet(),
            viewer.permissions.map { it.key }.toSet()
        )

        listOf(
            SystemPermissionNames.PROJECT_CREATE,
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_UPDATE,
            SystemPermissionNames.PROJECT_DELETE,
            SystemPermissionNames.PROJECT_ARCHIVE,
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE,
            SystemPermissionNames.DOCKER_GENERIC_CONTAINERS_MANAGE,
            SystemPermissionNames.DOCKER_IMAGES_READ,
            SystemPermissionNames.DOCKER_IMAGES_MANAGE,
            SystemPermissionNames.SECRETS_DELEGATE,
            SystemPermissionNames.SECRETS_USE,
            SystemPermissionNames.SECRETS_FORCE_REVOKE
        ).forEach { permission ->
            assertNotNull(permissionRepository.findByKey(permission.key))
        }
    }

    @Test
    fun `bootstrap can run repeatedly without duplicating roles or permissions`() {
        val roleCount = roleRepository.count()
        val permissionCount = permissionRepository.count()

        bootstrapService.bootstrap()
        bootstrapService.bootstrap()

        assertEquals(roleCount, roleRepository.count())
        assertEquals(permissionCount, permissionRepository.count())
    }

    @Test
    fun `bootstrap restores expected permissions when system role drifts`() {
        val admin = role(SystemRoleNames.PROJECT_ADMIN.key)
        admin.permissions.removeIf { permission ->
            permission.key == SystemPermissionNames.PROJECT_ARCHIVE.key
        }

        bootstrapService.bootstrap()

        assertEquals(
            SystemRoleNames.PROJECT_ADMIN.permissionNames.map { it.key }.toSet(),
            role(SystemRoleNames.PROJECT_ADMIN.key).permissions.map { it.key }.toSet()
        )
    }

    private fun role(name: String) =
        assertNotNull(roleRepository.findByNameAndIsSystemTrue(name))
}

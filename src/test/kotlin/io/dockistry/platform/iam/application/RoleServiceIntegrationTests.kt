package io.dockistry.platform.iam.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.RoleNameCollidesProjectRoleException
import io.dockistry.platform.iam.domain.RoleNameCollidesSystemRoleException
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class RoleServiceIntegrationTests {
    @Autowired
    private lateinit var roleService: RoleService

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `insert role persists new system role`() {
        val role = Role(
            name = "system-role-${fixtures().unique()}",
            description = "System role",
            isSystem = true
        )

        roleService.insertRole(role)
        entityManager.flush()
        entityManager.clear()

        val savedRole = entityManager.find(Role::class.java, role.id)
        assertNotNull(savedRole)
        assertEquals(role.name, savedRole.name)
    }

    @Test
    fun `insert role rejects role name that collides with system role`() {
        val fixtures = fixtures()
        val systemRoleName = "system-role-${fixtures.unique()}"
        fixtures.role(name = systemRoleName, isSystem = true)
        val customProject = fixtures.project()
        fixtures.flush()

        assertFailsWith<RoleNameCollidesSystemRoleException> {
            roleService.insertRole(
                Role(
                    name = systemRoleName,
                    description = "Custom role",
                    isSystem = false,
                    project = customProject
                )
            )
        }
    }

    @Test
    fun `insert role rejects duplicate custom role name inside same project`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val roleName = "custom-role-${fixtures.unique()}"
        fixtures.role(name = roleName, isSystem = false, project = project)
        fixtures.flush()

        assertFailsWith<RoleNameCollidesProjectRoleException> {
            roleService.insertRole(
                Role(
                    name = roleName,
                    description = "Duplicate custom role",
                    isSystem = false,
                    project = project
                )
            )
        }
    }

    @Test
    fun `insert role allows same custom role name in different projects`() {
        val fixtures = fixtures()
        val firstProject = fixtures.project()
        val secondProject = fixtures.project()
        val roleName = "custom-role-${fixtures.unique()}"
        fixtures.role(name = roleName, isSystem = false, project = firstProject)
        fixtures.flush()

        roleService.insertRole(
            Role(
                name = roleName,
                description = "Same name in another project",
                isSystem = false,
                project = secondProject
            )
        )
        entityManager.flush()

        val count = entityManager
            .createQuery(
                "select count(role) from Role role where role.name = :name",
                Long::class.javaObjectType
            )
            .setParameter("name", roleName)
            .singleResult

        assertEquals(2L, count.toLong())
    }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)
}

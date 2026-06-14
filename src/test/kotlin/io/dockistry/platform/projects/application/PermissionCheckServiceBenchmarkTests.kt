package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("benchmark")
@SpringBootTest(
    properties = [
        "spring.jpa.properties.hibernate.generate_statistics=true"
    ]
)
@Import(TestcontainersConfig::class)
@Testcontainers
class PermissionCheckServiceBenchmarkTests {

    companion object {
        const val GROUP_COUNT = 10
        const val PROJECT_USER_BINDINGS = 100
        const val PROJECT_EVERY_GROUP_BINDINGS = 10
        const val PROJECT_COUNT = PROJECT_USER_BINDINGS + GROUP_COUNT * PROJECT_EVERY_GROUP_BINDINGS
        const val PERMISSIONS_PER_ROLE = 20

        const val TEST_RUNS = 5
    }

    @Autowired
    private lateinit var permissionCheckService: PermissionCheckService

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    // Required to get Hibernate queries statistics
    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    // Assigned in test TODO: probably do it differently
    private lateinit var fixtures: IamProjectFixtures

    // Targets for benchmarking permission check
    lateinit var targetBinding: RoleBinding
    lateinit var targetPermissionKey: String

    @Test
    @Transactional
    fun `count permission check service queries`() {
        fixtures = IamProjectFixtures(entityManager)
        val user = seedData()
        val stats = getStats()

        val statementsCounts = mutableListOf<Long>()

        repeat(TEST_RUNS) {
            entityManager.clear()
            stats.clear()

            assertTrue(
                permissionCheckService.hasPermission(
                    targetPermissionKey,
                    user.principal.id,
                    targetBinding.scope.id
                )
            )

            statementsCounts.add(stats.prepareStatementCount)
        }

        println("Statements counts: $statementsCounts")
        println("Average statements count: ${statementsCounts.average()}")
    }

    private fun getStats(): Statistics {
        val sessionFactory = entityManagerFactory.unwrap(org.hibernate.SessionFactory::class.java)
        return sessionFactory.statistics
    }

    private fun seedData(): User {
        val user = fixtures.user()

        val projectList = seedProjects()
        val projectListIterator = projectList.listIterator()

        seedUserProjectBindings(user, projectListIterator)
        seedGroupsAndTheirBindings(user, projectListIterator)

        entityManager.flush()

        return user
    }

    private fun seedProjects(): List<Project> {
        val projectList = mutableListOf<Project>()

        repeat(PROJECT_COUNT) {
            val project = fixtures.project()
            projectList.add(project)
        }

        return projectList
    }

    private fun seedUserProjectBindings(user: User, projectListIterator: ListIterator<Project>) {
        repeat(PROJECT_USER_BINDINGS) {
            seedRoleBinding(
                user.principal,
                projectListIterator.next().scope,
                *generatePermissionKeys()
            )
        }
    }

    private fun seedGroupsAndTheirBindings(user: User, projectListIterator: ListIterator<Project>) {
        repeat(GROUP_COUNT) {
            val group = fixtures.group()

            repeat(PROJECT_EVERY_GROUP_BINDINGS) {
                val permissionKeys = generatePermissionKeys()
                targetBinding = seedRoleBinding(
                    group.principal,
                    projectListIterator.next().scope,
                    *permissionKeys
                )
                targetPermissionKey = permissionKeys.last()
            }

            user.groups += group
        }
    }

    private fun seedRoleBinding(
        principal: Principal,
        scope: Scope,
        vararg permissionKeys: String = arrayOf(
            SystemPermissionNames.PROJECT_READ
        )
    ): RoleBinding {
        return fixtures.grant(principal, scope, *permissionKeys)
    }

    private fun generatePermissionKeys(): Array<String> {
        val permissionList = mutableListOf<String>()

        repeat(PERMISSIONS_PER_ROLE) {
            permissionList.add("test-permission-${unique()}")
        }

        return permissionList.toTypedArray()
    }

    fun unique(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}

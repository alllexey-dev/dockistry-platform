package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.application.PermissionCheckService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.infrastructure.RoleBindingRepository
import io.dockistry.platform.projects.application.ProjectMemberSecretDelegationIntegrationTests.Alternative
import io.dockistry.platform.secrets.application.SecretService
import io.dockistry.platform.secrets.domain.SecretDelegationActiveException
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.shared.exception.ServiceException
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.SecretDelegationFixtures
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_BINDING_SNAPSHOT
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_BINDING_DELETE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_DELEGATION_SAVE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_PRINCIPAL_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import io.dockistry.platform.testsupport.concurrency.RepositoryConcurrencyTestConfiguration
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
@Import(
    TestcontainersConfig::class,
    RepositoryConcurrencyTestConfiguration::class
)
class ProjectMemberSecretDelegationConcurrencyTests {
    @Autowired private lateinit var memberService: ProjectMemberService
    @Autowired private lateinit var secretService: SecretService
    @Autowired private lateinit var permissionService: PermissionCheckService
    @Autowired private lateinit var bindings: RoleBindingRepository
    @Autowired private lateinit var delegations: ProjectSecretDelegationRepository
    @Autowired private lateinit var concurrency: ConcurrencyTestSupport
    @Autowired private lateinit var entityManager: EntityManager

    companion object {
        @JvmStatic
        fun principalTypeRemovals(): List<Arguments> {
            return Removal.entries.flatMap { removal ->
                PrincipalType.entries.map { type -> Arguments.of(type, removal) }
            }
        }
    }

    enum class SourcePair(val types: List<PrincipalType>) {
        USER_USER(listOf(PrincipalType.USER, PrincipalType.USER)),
        GROUP_USER(listOf(PrincipalType.GROUP, PrincipalType.USER)),
        GROUP_GROUP(listOf(PrincipalType.GROUP, PrincipalType.GROUP))
    }

    enum class Removal { BINDING, MEMBER }

    @ParameterizedTest
    @EnumSource(SourcePair::class)
    fun `concurrent removal of two delegate sources preserves the last one`(
        pair: SourcePair
    ) {
        val data = prepare(pair.types, withDelegation = true)
        val first = data.sources.first()
        val second = data.sources.last()
        concurrency.run {
            val pause = pauseAt(BEFORE_BINDING_DELETE, matching = first.bindingId)
            val firstRevoke = start("first revoke") {
                remove(data, first, Removal.BINDING)
            }
            pause.awaitReached()
            val attempt = observeNext(BEFORE_PRINCIPAL_LOCK)
            val secondRevoke = start("second revoke") {
                remove(data, second, Removal.BINDING)
            }
            assertBlocked(attempt, by = pause)

            pause.release()
            firstRevoke.await()
            val rejected = assertIs<SecretDelegationActiveException>(secondRevoke.failure())
            assertEquals("secret_delegation_active", rejected.code)
        }

        assertFalse(bindings.existsById(first.bindingId))
        assertTrue(bindings.existsById(second.bindingId))
        assertTrue(delegations.existsById(assertNotNull(data.delegationId)))
        assertTrue(
            permissionService.hasPermission(
                SystemPermissionNames.SECRETS_DELEGATE.key, data.owner.id, data.scopeId
            )
        )
    }

    @ParameterizedTest
    @CsvSource("USER,BINDING", "USER,MEMBER", "GROUP,BINDING", "GROUP,MEMBER")
    fun `first delegation commits before concurrent last source removal is rejected`(
        type: PrincipalType,
        removal: Removal
    ) {
        val data = prepare(listOf(type), withDelegation = false)
        concurrency.run {
            val pause = pauseAt(BEFORE_DELEGATION_SAVE, matching = data.secretId)
            val delegation = start("create delegation") {
                secretService.delegate(data.secretId, data.projectId, data.owner)
            }
            pause.awaitReached()
            val attempt = observeNext(BEFORE_PRINCIPAL_LOCK)
            val revoke = start("revoke permission") {
                remove(data, data.sources.single(), removal)
            }
            assertBlocked(attempt, by = pause)

            pause.release()
            val delegationId = delegation.await()
            assertIs<SecretDelegationActiveException>(revoke.failure())
            assertTrue(delegations.existsById(delegationId))
        }

        assertTrue(bindings.existsById(data.sources.single().bindingId))
        assertTrue(
            permissionService.hasPermission(
                SystemPermissionNames.SECRETS_DELEGATE.key, data.owner.id, data.scopeId
            )
        )
        assertTrue(delegationExists(data))
    }

    @ParameterizedTest
    @CsvSource("USER,BINDING", "USER,MEMBER", "GROUP,BINDING", "GROUP,MEMBER")
    fun `last source removal commits before concurrent first delegation is denied`(
        type: PrincipalType,
        removal: Removal
    ) {
        val data = prepare(listOf(type), withDelegation = false)
        val source = data.sources.single()
        concurrency.run {
            val pause = pauseAt(BEFORE_BINDING_DELETE, matching = source.bindingId)
            val revoke = start("revoke permission") { remove(data, source, removal) }
            pause.awaitReached()
            val attempt = observeNext(BEFORE_PRINCIPAL_LOCK)
            val delegation = start("create delegation") {
                secretService.delegate(data.secretId, data.projectId, data.owner)
            }
            assertBlocked(attempt, by = pause)

            pause.release()
            revoke.await()
            val rejected = assertIs<ServiceException>(delegation.failure())
            assertEquals("insufficient_permission_for_operation", rejected.code)
        }

        assertFalse(bindings.existsById(source.bindingId))
        assertFalse(
            permissionService.hasPermission(
                SystemPermissionNames.SECRETS_DELEGATE.key, data.owner.id, data.scopeId
            )
        )
        assertFalse(delegationExists(data))
    }

    @Test
    fun `concurrent grant after member snapshot remains a valid permission source`() {
        val data = prepare(listOf(PrincipalType.USER), withDelegation = true)
        val source = data.sources.single()
        val grantedBindingId = concurrency.run {
            val pause = pauseAt(AFTER_BINDING_SNAPSHOT, matching = source.principalId)
            val revoke = start("remove member") { remove(data, source, Removal.MEMBER) }
            pause.awaitReached()
            val grant = start("grant another role") {
                memberService.assignRole(
                    data.manager, data.projectId, source.principalId, data.grantRoleId
                ).roleBindingId
            }
            val grantedBindingId = grant.await()

            pause.release()
            revoke.await()
            grantedBindingId
        }

        assertFalse(bindings.existsById(source.bindingId))
        assertTrue(bindings.existsById(assertNotNull(grantedBindingId)))
        assertTrue(delegations.existsById(assertNotNull(data.delegationId)))
        assertTrue(
            permissionService.hasPermission(
                SystemPermissionNames.SECRETS_DELEGATE.key, data.owner.id, data.scopeId
            )
        )
    }

    private fun prepare(types: List<PrincipalType>, withDelegation: Boolean): TestData =
        concurrency.inNewTransaction {
            val fixtures = IamProjectFixtures(entityManager)
            val owner = fixtures.user()
            val project = fixtures.project(ownerPrincipal = owner.principal)
            val manager = fixtures.principal()
            fixtures.grantSystemPermission(
                manager, project.scope,
                SystemPermissionNames.PROJECT_READ.key,
                SystemPermissionNames.PROJECT_MEMBERS_MANAGE.key
            )
            // Preserve read access even if deleteMember removes all direct user bindings.
            val readers = fixtures.group()
            owner.groups += readers
            fixtures.grantSystemPermission(
                readers.principal, project.scope, SystemPermissionNames.PROJECT_READ.key
            )
            val sources = types.map { type ->
                val principal = if (type == PrincipalType.USER) owner.principal else {
                    val group = fixtures.group()
                    owner.groups += group
                    group.principal
                }
                val binding = fixtures.grantSystemPermission(
                    principal, project.scope,
                    SystemPermissionNames.PROJECT_READ.key,
                    SystemPermissionNames.SECRETS_DELEGATE.key
                )
                Source(principal.id, binding.id)
            }
            val grantRole = fixtures.role(
                permissions = setOf(
                    fixtures.systemPermission(SystemPermissionNames.PROJECT_READ.key),
                    fixtures.systemPermission(SystemPermissionNames.SECRETS_DELEGATE.key)
                )
            )
            val secrets = SecretDelegationFixtures(entityManager)
            val secret = secrets.secret(owner.principal)
            val delegationId = if (withDelegation) {
                secrets.delegate(secret, project).id
            } else null
            fixtures.flush()
            TestData(manager, owner.principal, project.id, project.scope.id,
                secret.id, delegationId, sources, grantRole.id)
        }

    private fun remove(data: TestData, source: Source, removal: Removal) {
        when (removal) {
            Removal.BINDING -> memberService.deleteRoleBinding(
                data.manager, data.projectId, source.principalId, source.bindingId
            )
            Removal.MEMBER -> memberService.deleteMember(
                data.manager, data.projectId, source.principalId
            )
        }
    }

    private fun delegationExists(data: TestData): Boolean =
        concurrency.inNewTransaction {
            delegations.getByProjectAndSecret(data.projectId, data.secretId) != null
        }

    private data class Source(val principalId: UUID, val bindingId: UUID)

    private data class TestData(
        val manager: Principal,
        val owner: Principal,
        val projectId: UUID,
        val scopeId: UUID,
        val secretId: String,
        val delegationId: UUID?,
        val sources: List<Source>,
        val grantRoleId: UUID
    )
}

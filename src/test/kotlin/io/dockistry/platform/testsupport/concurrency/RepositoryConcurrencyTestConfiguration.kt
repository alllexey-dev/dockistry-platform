package io.dockistry.platform.testsupport.concurrency

import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.infrastructure.RoleBindingRepository
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretDelegationLock
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.infrastructure.ManagedServiceRepository
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_BINDING_SNAPSHOT
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.AFTER_REFERENCE_FLUSH
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_BINDING_DELETE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_DELEGATION_ROW_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_DELEGATION_SAVE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_PRINCIPAL_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_PROJECT_SAVE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_SECRET_SAVE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_SERVICE_ROW_LOCK
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID

/** Delegates to real repositories; only armed checkpoints affect participating workers. */
@TestConfiguration(proxyBeanMethods = false)
@Import(ConcurrencyTestConfiguration::class)
class RepositoryConcurrencyTestConfiguration {
    @Bean
    @Primary
    fun coordinatedSecretRepository(
        delegate: SecretRepository,
        concurrency: ConcurrencyTestSupport
    ): SecretRepository = CoordinatedSecretRepository(delegate, concurrency)

    @Bean
    @Primary
    fun coordinatedProjectRepository(
        delegate: ProjectRepository,
        concurrency: ConcurrencyTestSupport
    ): ProjectRepository = CoordinatedProjectRepository(delegate, concurrency)

    @Bean
    @Primary
    fun coordinatedDelegationRepository(
        delegate: ProjectSecretDelegationRepository,
        concurrency: ConcurrencyTestSupport
    ): ProjectSecretDelegationRepository = CoordinatedDelegationRepository(delegate, concurrency)

    @Bean
    @Primary
    fun coordinatedReferenceRepository(
        delegate: ManagedServiceSecretReferenceRepository,
        concurrency: ConcurrencyTestSupport
    ): ManagedServiceSecretReferenceRepository = CoordinatedReferenceRepository(delegate, concurrency)

    @Bean
    @Primary
    fun coordinatedPrincipalLock(
        delegate: SecretDelegationLock,
        jdbcTemplate: JdbcTemplate,
        concurrency: ConcurrencyTestSupport
    ): SecretDelegationLock = CoordinatedPrincipalLock(delegate, jdbcTemplate, concurrency)

    @Bean
    @Primary
    fun coordinatedRoleBindingRepository(
        delegate: RoleBindingRepository,
        concurrency: ConcurrencyTestSupport
    ): RoleBindingRepository = CoordinatedRoleBindingRepository(delegate, concurrency)

    @Bean
    @Primary
    fun coordinatedServiceRepository(
        delegate: ManagedServiceRepository,
        concurrency: ConcurrencyTestSupport
    ): ManagedServiceRepository = CoordinatedServiceRepository(delegate, concurrency)
}

// Named open decorators allow Spring to preserve inherited transactional advice.
private open class CoordinatedSecretRepository(
    private val delegate: SecretRepository,
    private val concurrency: ConcurrencyTestSupport
) : SecretRepository by delegate {
    // JpaRepository requires this generic signature for saveAndFlush overrides.
    override fun <S : Secret> saveAndFlush(entity: S): S {
        concurrency.checkpoint(BEFORE_SECRET_SAVE, entity.name)
        return delegate.saveAndFlush(entity)
    }
}

private open class CoordinatedProjectRepository(
    private val delegate: ProjectRepository,
    private val concurrency: ConcurrencyTestSupport
) : ProjectRepository by delegate {
    override fun <S : Project> saveAndFlush(entity: S): S {
        concurrency.checkpoint(BEFORE_PROJECT_SAVE, entity.ownerPrincipal.id)
        return delegate.saveAndFlush(entity)
    }
}

private open class CoordinatedDelegationRepository(
    private val delegate: ProjectSecretDelegationRepository,
    private val concurrency: ConcurrencyTestSupport
) : ProjectSecretDelegationRepository by delegate {
    override fun <S : ProjectSecretDelegation> saveAndFlush(entity: S): S {
        concurrency.checkpoint(BEFORE_DELEGATION_SAVE, entity.secret.id)
        return delegate.saveAndFlush(entity)
    }

    override fun findByProjectAndSecretForNoKeyUpdateBatch(
        projectId: UUID,
        secretIds: List<String>
    ): List<ProjectSecretDelegation> {
        concurrency.checkpoint(BEFORE_DELEGATION_ROW_LOCK, projectId)
        return delegate.findByProjectAndSecretForNoKeyUpdateBatch(projectId, secretIds)
    }
}

private open class CoordinatedReferenceRepository(
    private val delegate: ManagedServiceSecretReferenceRepository,
    private val concurrency: ConcurrencyTestSupport
) : ManagedServiceSecretReferenceRepository by delegate {
    override fun flush() {
        delegate.flush()
        concurrency.checkpoint(AFTER_REFERENCE_FLUSH)
    }
}

private class CoordinatedPrincipalLock(
    private val delegate: SecretDelegationLock,
    jdbcTemplate: JdbcTemplate,
    private val concurrency: ConcurrencyTestSupport
) : SecretDelegationLock(jdbcTemplate) {
    override fun lock(key: Long) {
        concurrency.checkpoint(BEFORE_PRINCIPAL_LOCK, key)
        delegate.lock(key)
    }
}

private open class CoordinatedRoleBindingRepository(
    private val delegate: RoleBindingRepository,
    private val concurrency: ConcurrencyTestSupport
) : RoleBindingRepository by delegate {
    override fun deleteBindingInProject(
        projectId: UUID,
        principalId: UUID,
        roleBindingId: UUID
    ): Int {
        concurrency.checkpoint(BEFORE_BINDING_DELETE, roleBindingId)
        return delegate.deleteBindingInProject(projectId, principalId, roleBindingId)
    }

    override fun deleteBindingsInProject(
        projectId: UUID,
        principalId: UUID,
        roleBindingIds: Collection<UUID>
    ): Int {
        roleBindingIds.forEach { concurrency.checkpoint(BEFORE_BINDING_DELETE, it) }
        return delegate.deleteBindingsInProject(projectId, principalId, roleBindingIds)
    }

    override fun findByScopeAndSubjectPrincipalId(
        scope: Scope,
        subjectPrincipalId: UUID
    ): List<RoleBinding> {
        val snapshot = delegate.findByScopeAndSubjectPrincipalId(scope, subjectPrincipalId)
        concurrency.checkpoint(AFTER_BINDING_SNAPSHOT, subjectPrincipalId)
        return snapshot
    }
}

private open class CoordinatedServiceRepository(
    private val delegate: ManagedServiceRepository,
    private val concurrency: ConcurrencyTestSupport
) : ManagedServiceRepository by delegate {
    override fun findByIdForUpdate(
        serviceId: UUID,
        lockTimeout: Duration
    ): ManagedService? {
        concurrency.checkpoint(BEFORE_SERVICE_ROW_LOCK, serviceId)
        return delegate.findByIdForUpdate(serviceId, lockTimeout)
    }

    override fun findByProjectIdAndIdForUpdate(
        projectId: UUID,
        serviceId: UUID,
        lockTimeout: Duration
    ): ManagedService? {
        concurrency.checkpoint(BEFORE_SERVICE_ROW_LOCK, serviceId)
        return delegate.findByProjectIdAndIdForUpdate(projectId, serviceId, lockTimeout)
    }
}

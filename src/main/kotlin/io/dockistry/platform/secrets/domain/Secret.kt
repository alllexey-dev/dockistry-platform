package io.dockistry.platform.secrets.domain

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.shared.domain.ResourceNameNormalizer
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(
    name = "secrets",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uc_secrets_owner_name",
            columnNames = ["owner_principal_id", "name"]
        )
    ]
)
class Secret(
    name: String,
    description: String? = null,

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_principal_id", nullable = false)
    val ownerPrincipal: Principal,

    createdAt: Instant,
    updatedAt: Instant
) {
    @Id
    @Column(name = "id", nullable = false, length = 16)
    val id: String = SecretIdGenerator.generate()

    @Column(name = "name", nullable = false, length = ResourceNameNormalizer.MAX_LENGTH)
    var name: String = normalizeName(name)
        protected set

    @Column(name = "description", columnDefinition = "text")
    var description: String? = description
        protected set

    @OneToMany(
        mappedBy = "secret",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    @OrderBy("createdAt ASC")
    val versions: MutableList<SecretVersion> = mutableListOf()

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = createdAt

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = updatedAt
        protected set

    fun addVersion(version: SecretVersion) {
        versions += version
    }

    private fun normalizeName(value: String): String =
        ResourceNameNormalizer.normalizeAndValidate(value, ::InvalidSecretNameException)
}

package io.dockistry.platform.secrets.domain

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.shared.domain.ResourceNameNormalizer
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
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

    encryptedValues: Map<String, String>,
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

    /** Secret keys are plaintext; every map value is an encrypted payload. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "encrypted_values", nullable = false, columnDefinition = "jsonb")
    var encryptedValues: Map<String, String> = encryptedValues.toMap()
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = createdAt

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = updatedAt
        protected set

    private fun normalizeName(value: String): String =
        ResourceNameNormalizer.normalizeAndValidate(value, ::InvalidSecretNameException)
}

package io.dockistry.platform.secrets.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(
    name = "secret_versions",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uc_secret_versions_secret_id_id",
            columnNames = ["secret_id", "id"]
        )
    ]
)
class SecretVersion(
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "secret_id", nullable = false)
    val secret: Secret,

    encryptedValues: Map<String, String>,
    createdAt: Instant,

    // Assigned before construction: values are encrypted with the version ID as associated data.
    @Id
    @Column(name = "id", nullable = false, length = 16)
    val id: String = SecretVersionIdGenerator.generate()
) {

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "encrypted_values", nullable = false, columnDefinition = "jsonb")
    val encryptedValues: Map<String, String> = encryptedValues.toMap()

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = createdAt
}

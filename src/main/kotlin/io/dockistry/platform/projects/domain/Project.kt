package io.dockistry.platform.projects.domain

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.shared.domain.ResourceDescriptionNormalizer
import io.dockistry.platform.shared.domain.ResourceNameNormalizer
import io.dockistry.platform.shared.domain.SlugValidator
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "projects")
class Project(
    name: String,

    slug: String,

    description: String? = null,

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    val ownerPrincipal: Principal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProjectStatus = ProjectStatus.ACTIVE,

    @OneToOne(cascade = [CascadeType.ALL], optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_id", nullable = false, unique = true)
    val scope: Scope,

    @Column(nullable = false)
    val createdAt: Instant
) {
    companion object {
        const val MAX_SLUG_LENGTH = 30
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Version
    @Column(nullable = false)
    var version: Long = 0
        protected set

    @field:Column(unique = true, nullable = false)
    var name: String = normalizeName(name)
        set(value) {
            field = normalizeName(value)
        }

    @field:Column(unique = true, nullable = false, length = MAX_SLUG_LENGTH)
    val slug: String = SlugValidator.validate(slug, MAX_SLUG_LENGTH) {
        InvalidProjectSlugException(
            "Project slug '$it' must be a slug up to $MAX_SLUG_LENGTH characters"
        )
    }

    @field:Column(length = ResourceDescriptionNormalizer.MAX_LENGTH)
    var description: String? = normalizeDescription(description)
        set(value) {
            field = normalizeDescription(value)
        }

    @OneToMany(mappedBy = "project", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val customRoles: MutableSet<Role> = mutableSetOf()

    @PrePersist
    @PreUpdate
    fun normalizeBeforeSave() {
        name = normalizeName(name)
        description = normalizeDescription(description)
    }

    private fun normalizeName(value: String): String =
        ResourceNameNormalizer.normalizeAndValidate(value, ::InvalidProjectNameException)

    private fun normalizeDescription(value: String?): String? =
        ResourceDescriptionNormalizer.normalizeAndValidate(
            value,
            ::InvalidProjectDescriptionException
        )
}

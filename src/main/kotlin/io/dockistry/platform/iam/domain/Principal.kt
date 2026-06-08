package io.dockistry.platform.iam.domain

import jakarta.persistence.*
import java.util.*

@Entity
@Table(name = "iam_principals")
class Principal(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val principalType: PrincipalType
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @OneToMany(
        mappedBy = "subjectPrincipal",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    val roleBindings: MutableSet<RoleBinding> = mutableSetOf()
}

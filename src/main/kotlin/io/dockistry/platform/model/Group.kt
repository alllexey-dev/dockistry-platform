package io.dockistry.platform.model

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToMany
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "groups")
class Group(val name: String) {
    @Id
    lateinit var id: UUID

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id")
    @MapsId
    lateinit var principal: Principal

    @ManyToMany(mappedBy = "groups")
    val members: MutableSet<User> = mutableSetOf()
}

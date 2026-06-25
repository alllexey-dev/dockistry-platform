package io.dockistry.platform.setup.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "setup_state_entries")
class SetupStateEntry(
    @Id
    @Column(name = "key")
    val key: String,

    @Column(name = "value", nullable = false)
    val value: String
)

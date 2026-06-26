package io.dockistry.platform.shared.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "system_settings")
class SystemSetting(
    @Id
    @Column(name = "key")
    val key: String,

    @Column(name = "value", nullable = false)
    val value: String
)

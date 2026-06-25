package io.dockistry.platform.setup.infrastructure

import io.dockistry.platform.setup.domain.SetupStateEntry
import org.springframework.data.jpa.repository.JpaRepository

interface SetupStateEntryRepository : JpaRepository<SetupStateEntry, String>

package io.dockistry.platform.shared.infrastructure

import io.dockistry.platform.shared.domain.SystemSetting
import org.springframework.data.jpa.repository.JpaRepository

interface SystemSettingRepository : JpaRepository<SystemSetting, String>

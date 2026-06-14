package io.dockistry.platform.iam.domain

import io.dockistry.platform.shared.exception.ForbiddenException

class InsufficientPermissionException(
    message: String
) : ForbiddenException(message, "insufficient_permission_for_operation")

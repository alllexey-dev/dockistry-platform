package io.dockistry.platform.setup.domain

import io.dockistry.platform.shared.exception.ConflictException

class InitialAdminAlreadyExistsException : ConflictException(
    message = "Initial admin already exists",
    code = "initial_admin_already_exists"
)

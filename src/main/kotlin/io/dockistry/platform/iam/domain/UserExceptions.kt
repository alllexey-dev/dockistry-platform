package io.dockistry.platform.iam.domain

import io.dockistry.platform.shared.exception.UnauthorizedException

class AuthenticatedUserNotFoundException(
    message: String = "Authenticated user doesn't exist",
    code: String = "authenticated_user_not_found"
) : UnauthorizedException(message, code)

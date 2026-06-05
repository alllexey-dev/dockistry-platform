package io.dockistry.platform.shared.api

data class ApiResponse<T>(
    val data: T? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null
)
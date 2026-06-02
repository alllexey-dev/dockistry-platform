package io.dockistry.platform.dto

data class ApiResponse<T>(
    val data: T? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null
)

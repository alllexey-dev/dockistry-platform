package io.dockistry.platform.services.domain

data class ServiceEndpoint(
    val port: Int,

    val protocol: ServiceEndpointProtocol = ServiceEndpointProtocol.TCP,

    val mode: ServiceEndpointMode,

    val externalPort: Int? = null
)

enum class ServiceEndpointProtocol {
    TCP,
    UDP
}

enum class ServiceEndpointMode {
    DIRECT,
    EDGE
}

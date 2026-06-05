package io.dockistry.platform.iam.domain

enum class ScopeType {
    GLOBAL,
    PROJECT,
    CONTAINER,
    SECRET,
    VOLUME,
    NETWORK,
    PROXY_RULE,
    STACK
}

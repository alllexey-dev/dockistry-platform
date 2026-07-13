package io.dockistry.platform.services.domain

data class ManagedServiceImageRef(
    val repository: String,
    val tag: String
) {
    fun asString(): String = "$repository:$tag"
}

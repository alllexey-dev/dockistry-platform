package io.dockistry.platform.services.domain

import io.dockistry.platform.secrets.domain.InvalidSecretReferenceFormatException
import io.dockistry.platform.secrets.domain.SecretValidator
import io.dockistry.platform.services.testsupport.managedServiceDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManagedServiceConfigurationTests {
    @Test
    fun `invalid secret reference formats are rejected`() {
        val reference = ManagedServiceSecretReference(
            secretId = "sec-AAAAAAAAAAAA",
            key = "password",
            envName = "PASSWORD"
        )
        val invalidReferences = listOf(
            reference.copy(secretId = "bad-id"),
            reference.copy(key = "bad key"),
            reference.copy(envName = "BAD-NAME!"),
            reference.copy(envName = "1PASSWORD"),
            reference.copy(envName = "A".repeat(SecretValidator.MAX_ENV_NAME_LENGTH + 1)),
            reference.copy(secretVersion = "bad-version")
        )

        invalidReferences.forEach { invalid ->
            val error = assertFailsWith<InvalidSecretReferenceFormatException> {
                ManagedServiceConfiguration.validated(
                    managedServiceDraft().copy(secretReferences = listOf(invalid))
                )
            }
            assertEquals("invalid_secret_reference_format", error.code)
        }
    }

    @Test
    fun `secret reference environment name may start with an underscore`() {
        val reference = ManagedServiceSecretReference(
            secretId = "sec-AAAAAAAAAAAA",
            key = "password",
            envName = "_PASSWORD_1"
        )

        val configuration = ManagedServiceConfiguration.validated(
            managedServiceDraft().copy(secretReferences = listOf(reference))
        )

        assertEquals("_PASSWORD_1", configuration.secretReferences.single().envName)
    }

    @Test
    fun `secret reference environment name of max length is accepted`() {
        val envName = "A".repeat(SecretValidator.MAX_ENV_NAME_LENGTH)
        val reference = ManagedServiceSecretReference(
            secretId = "sec-AAAAAAAAAAAA",
            key = "password",
            envName = envName
        )

        val configuration = ManagedServiceConfiguration.validated(
            managedServiceDraft().copy(secretReferences = listOf(reference))
        )

        assertEquals(envName, configuration.secretReferences.single().envName)
    }

    @Test
    fun `duplicate or literal environment names are rejected`() {
        val reference = ManagedServiceSecretReference(
            secretId = "sec-AAAAAAAAAAAA",
            key = "password",
            envName = "PASSWORD"
        )
        val invalidConfigurations = listOf(
            managedServiceDraft().copy(secretReferences = listOf(reference, reference)),
            managedServiceDraft(env = mapOf("PASSWORD" to "literal"))
                .copy(secretReferences = listOf(reference))
        )

        invalidConfigurations.forEach { invalid ->
            val error = assertFailsWith<InvalidManagedServiceConfigException> {
                ManagedServiceConfiguration.validated(invalid)
            }
            assertEquals("invalid_managed_service_config", error.code)
        }
    }
}

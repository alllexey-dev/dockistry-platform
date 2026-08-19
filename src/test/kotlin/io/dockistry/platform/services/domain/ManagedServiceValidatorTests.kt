package io.dockistry.platform.services.domain

import io.dockistry.platform.docker.domain.model.DockerRestartPolicyType
import io.dockistry.platform.services.testsupport.managedServiceDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ManagedServiceValidatorTests {

    @Test
    fun `image references are normalized into repository and tag`() {
        val defaultTag = ManagedServiceImageRefNormalizer.normalize("  NGINX  ")
        val explicitTag = ManagedServiceImageRefNormalizer.normalize(
            "Library/Postgres:16-Alpine"
        )

        assertEquals(ManagedServiceImageRef("nginx", "latest"), defaultTag)
        assertEquals(
            ManagedServiceImageRef("library/postgres", "16-Alpine"),
            explicitTag
        )
    }

    @Test
    fun `unsupported and malformed image references are rejected`() {
        val invalidRefs = listOf(
            "",
            "nginx@sha256:abc",
            "ghcr.io/team/image:latest",
            "localhost/team/image:latest",
            "team//image:latest",
            "team/image:"
        )

        invalidRefs.forEach { imageRef ->
            assertFailsWith<InvalidManagedServiceImageRefException>(imageRef) {
                ManagedServiceImageRefNormalizer.normalize(imageRef)
            }
        }
    }

    @Test
    fun `endpoint validation allows same direct port for different protocols`() {
        val endpoints = listOf(
            ServiceEndpoint(
                port = 8080,
                protocol = ServiceEndpointProtocol.TCP,
                mode = ServiceEndpointMode.DIRECT,
                externalPort = 18080
            ),
            ServiceEndpoint(
                port = 8080,
                protocol = ServiceEndpointProtocol.UDP,
                mode = ServiceEndpointMode.DIRECT,
                externalPort = 18080
            )
        )

        assertEquals(endpoints, ManagedServiceValidator.validateEndpoints(endpoints))
    }

    @Test
    fun `invalid endpoint combinations and duplicates are rejected`() {
        val invalidEndpointSets = listOf(
            listOf(ServiceEndpoint(port = 0, mode = ServiceEndpointMode.EDGE)),
            listOf(ServiceEndpoint(port = 80, mode = ServiceEndpointMode.DIRECT)),
            listOf(
                ServiceEndpoint(
                    port = 80,
                    mode = ServiceEndpointMode.EDGE,
                    externalPort = 8080
                )
            ),
            listOf(
                ServiceEndpoint(port = 80, mode = ServiceEndpointMode.EDGE),
                ServiceEndpoint(port = 80, mode = ServiceEndpointMode.EDGE)
            ),
            listOf(
                ServiceEndpoint(
                    port = 80,
                    mode = ServiceEndpointMode.DIRECT,
                    externalPort = 8080
                ),
                ServiceEndpoint(
                    port = 81,
                    mode = ServiceEndpointMode.DIRECT,
                    externalPort = 8080
                )
            )
        )

        invalidEndpointSets.forEach { endpoints ->
            assertFailsWith<InvalidManagedServiceConfigException> {
                ManagedServiceValidator.validateEndpoints(endpoints)
            }
        }
    }

    @Test
    fun `docker compatible managed volume names are accepted`() {
        val volumes = listOf(
            ServiceVolumeMount(name = "a1", mountPath = "/data"),
            ServiceVolumeMount(name = "Cache_01.back-up", mountPath = "/cache"),
            ServiceVolumeMount(
                name = "v".repeat(ManagedServiceValidator.MAX_VOLUME_NAME_LENGTH),
                mountPath = "/max-length"
            )
        )

        assertEquals(volumes, ManagedServiceValidator.validateVolumes(volumes))
    }

    @Test
    fun `invalid volume names paths and duplicates are rejected`() {
        val invalidVolumeSets = listOf(
            listOf(ServiceVolumeMount(name = "a", mountPath = "/data")),
            listOf(ServiceVolumeMount(name = "_data", mountPath = "/data")),
            listOf(ServiceVolumeMount(name = "data/name", mountPath = "/data")),
            listOf(
                ServiceVolumeMount(
                    name = "v".repeat(ManagedServiceValidator.MAX_VOLUME_NAME_LENGTH + 1),
                    mountPath = "/data"
                )
            ),
            listOf(ServiceVolumeMount(name = "data", mountPath = "relative")),
            listOf(
                ServiceVolumeMount(name = "data", mountPath = "/data"),
                ServiceVolumeMount(name = "data", mountPath = "/other")
            ),
            listOf(
                ServiceVolumeMount(name = "data", mountPath = "/shared"),
                ServiceVolumeMount(name = "cache", mountPath = "/shared")
            )
        )

        invalidVolumeSets.forEach { volumes ->
            assertFailsWith<InvalidManagedServiceConfigException> {
                ManagedServiceValidator.validateVolumes(volumes)
            }
        }
    }

    @Test
    fun `environment command and args validation rejects malformed values`() {
        assertEquals(
            mapOf("APP_ENV" to "production", "_DEBUG2" to "false"),
            ManagedServiceValidator.validateEnv(
                mapOf("APP_ENV" to "production", "_DEBUG2" to "false")
            )
        )
        assertFailsWith<InvalidManagedServiceConfigException> {
            ManagedServiceValidator.validateEnv(mapOf("INVALID-NAME" to "value"))
        }
        assertFailsWith<InvalidManagedServiceConfigException> {
            ManagedServiceValidator.ensureNotBlank(listOf("run", " "), "command")
        }
        assertNull(ManagedServiceValidator.ensureNotBlank(null, "args"))
    }

    @Test
    fun `restart policy and resource limits enforce dependent constraints`() {
        assertEquals(
            3,
            ManagedServiceValidator.validateRestartPolicy(
                DockerRestartPolicyType.ON_FAILURE,
                3
            )
        )
        assertFailsWith<InvalidManagedServiceConfigException> {
            ManagedServiceValidator.validateRestartPolicy(
                DockerRestartPolicyType.ALWAYS,
                3
            )
        }
        assertFailsWith<InvalidManagedServiceConfigException> {
            ManagedServiceValidator.validateRestartPolicy(
                DockerRestartPolicyType.ON_FAILURE,
                -1
            )
        }
        assertFailsWith<InvalidManagedServiceConfigException> {
            ManagedServiceValidator.validatePositive(0, "memoryLimitBytes")
        }
        assertEquals(
            512L,
            ManagedServiceValidator.validatePositive(512, "memoryLimitBytes")
        )
    }

    @Test
    fun `name and expected version validation reject invalid input`() {
        assertEquals("api-service", ManagedServiceValidator.validateName("api-service"))
        assertEquals(0, ManagedServiceValidator.validateExpectedVersion(0))

        assertFailsWith<InvalidManagedServiceNameException> {
            ManagedServiceValidator.validateName("Invalid Service")
        }
        assertFailsWith<InvalidManagedServiceConfigException> {
            ManagedServiceValidator.validateExpectedVersion(-1)
        }
    }

    @Test
    fun `validated configuration does not retain mutable request collections`() {
        val command = mutableListOf("run")
        val args = mutableListOf("--port", "8080")
        val env = mutableMapOf("APP_ENV" to "production")
        val endpoints = mutableListOf(
            ServiceEndpoint(port = 8080, mode = ServiceEndpointMode.EDGE)
        )
        val volumes = mutableListOf(
            ServiceVolumeMount(name = "data", mountPath = "/data")
        )
        val configuration = ManagedServiceConfiguration.validated(
            managedServiceDraft(
                command = command,
                args = args,
                env = env,
                endpoints = endpoints,
                volumes = volumes
            )
        )

        command += "changed"
        args.clear()
        env["ADDED"] = "later"
        endpoints.clear()
        volumes.clear()

        assertEquals(listOf("run"), configuration.command)
        assertEquals(listOf("--port", "8080"), configuration.args)
        assertEquals(mapOf("APP_ENV" to "production"), configuration.env)
        assertEquals(
            listOf(ServiceEndpoint(port = 8080, mode = ServiceEndpointMode.EDGE)),
            configuration.endpoints
        )
        assertEquals(
            listOf(ServiceVolumeMount(name = "data", mountPath = "/data")),
            configuration.volumes
        )
    }
}

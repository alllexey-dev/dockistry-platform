package io.dockistry.platform.services.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.InternalServerErrorException
import io.dockistry.platform.shared.exception.NotFoundException
import io.dockistry.platform.shared.exception.ServiceUnavailableException
import java.util.UUID

class ManagedServiceNotFoundException(
    message: String
) : NotFoundException(message, "managed_service_not_found")

class ManagedServiceAlreadyExistsException(
    projectId: UUID,
    serviceName: String
) : ConflictException(
    "Managed service '$serviceName' already exists in project $projectId",
    "managed_service_already_exists"
)

class ManagedServiceVersionConflictException(
    message: String
) : ConflictException(message, "managed_service_version_conflict")

class ManagedServiceOperationInProgressException(
    message: String = "Another managed service operation is currently in progress"
) : ConflictException(message, "managed_service_operation_in_progress")

class ManagedServiceOperationConcurrencyCheckFailureException :
    InternalServerErrorException(
        message = "Couldn't make sure there are no other managed service Docker " +
                "operations running concurrently",
        code = "managed_service_operation_concurrency_check_failure"
    )

class ManagedServiceExecutionCapacityExhaustedException :
    ServiceUnavailableException(
        message = "Managed service execution capacity is temporarily exhausted, try again later",
        code = "managed_service_execution_capacity_exhausted"
    )

class ManagedServiceDeletionInProgressException(
    message: String = "Managed service deletion is currently in progress"
) : ConflictException(message, "managed_service_deletion_in_progress")

class InvalidManagedServiceNameException(
    message: String
) : BadRequestException(message, "invalid_managed_service_name")

class InvalidManagedServiceImageRefException(
    message: String
) : BadRequestException(message, "invalid_managed_service_image_ref")

class InvalidManagedServiceConfigException(
    message: String
) : BadRequestException(message, "invalid_managed_service_config")

class DeployAlreadyInProcessException(
    message: String
) : ConflictException(message, "deploy_already_in_process")

class ManagedServiceStopRequiredException() : ConflictException(
    message = "Container for your service is currently already running, please stop the service and try again",
    code = "managed_service_stop_required"
)

class ContainerNameConflictSupportRequiredException() : ConflictException(
    message = "Container name conflicts with our system, please contact support",
    code = "container_name_conflict_support_required"
)

class ManagedServiceNotDeployedException(
    message: String
) : ConflictException(message = message, code = "managed_service_not_deployed")

class ManagedServiceNotRunningException(
    message: String
) : ConflictException(message = message, code = "managed_service_not_running")

class ManagedServiceDeploymentCanceled(
    message: String
) : ConflictException(message = message, code = "managed_service_deployment_canceled")

class ManagedServiceDeletionBlockedByDeployment(
    message: String
) : ConflictException(message = message, code = "managed_service_deletion_blocked_by_deployment")

class DeploymentNotFoundException(
    message: String
) : NotFoundException(message = message, code = "deployment_not_found")

class DeploymentOperationInProgressException :
    ConflictException(
        message = "Another deployment-related Docker operation is currently in progress",
        code = "deployment_operation_in_progress"
    )

class DeploymentOperationConcurrencyCheckFailureException :
    InternalServerErrorException(
        message = "Couldn't make sure there are no other deployment-related " +
                "Docker operations running concurrently",
        code = "deployment_operation_concurrency_check_failure"
    )

class DeploymentExecutionCapacityExhaustedException :
        ServiceUnavailableException(
            message = "Deployment execution capacity is temporarily exhausted, try again later",
            code = "deployment_execution_capacity_exhausted"
        )

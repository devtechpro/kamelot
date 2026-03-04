package io.devtech.integration.management.model

import java.time.Instant
import java.util.UUID

/**
 * Tracks a deployed integration — its artifact source, target agent, and lifecycle state.
 */
data class Deployment(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val artifactId: String? = null,
    val agentId: String,
    val integrationName: String,
    var state: DeploymentState = DeploymentState.PENDING,
    val properties: Map<String, String> = emptyMap(),
    var error: String? = null,
    val createdAt: Instant = Instant.now(),
)

enum class DeploymentState {
    PENDING,
    DEPLOYING,
    DEPLOYED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
}

/**
 * Agent info — a runtime target (local or remote) that hosts integrations.
 */
data class AgentInfo(
    val id: String,
    val name: String,
    val type: AgentType,
    val endpoint: String? = null,
    var status: AgentStatus = AgentStatus.CONNECTED,
)

enum class AgentType { LOCAL, REMOTE }

enum class AgentStatus { CONNECTED, DISCONNECTED }

/**
 * Management event — audit log entry for deployment lifecycle actions.
 */
data class ManagementEvent(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val type: EventType,
    val source: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, Any?> = emptyMap(),
)

enum class EventType {
    ARTIFACT_UPLOADED,
    DEPLOYMENT_CREATED,
    DEPLOYMENT_STARTING,
    DEPLOYMENT_STARTED,
    DEPLOYMENT_STOPPING,
    DEPLOYMENT_STOPPED,
    DEPLOYMENT_FAILED,
    DEPLOYMENT_UNDEPLOYED,
    AGENT_CONNECTED,
    AGENT_DISCONNECTED,
}

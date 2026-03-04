package io.devtech.integration.management.agent

import io.devtech.integration.management.model.AgentInfo

/**
 * Command sent to an agent to deploy an integration.
 */
data class DeployCommand(
    val deploymentId: String,
    val artifactId: String? = null,
    val integrationName: String,
    val properties: Map<String, String> = emptyMap(),
    /** JAR bytes — sent over HTTP to remote agents. Null for local deploys. */
    val jarBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeployCommand) return false
        return deploymentId == other.deploymentId
    }
    override fun hashCode(): Int = deploymentId.hashCode()
}

/**
 * Result from an agent command.
 */
data class AgentCommandResult(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
    val integrationStatus: Map<String, Any?>? = null,
)

/**
 * Abstraction over a runtime agent — local (same JVM) or remote (HTTP).
 *
 * The management plane talks to agents exclusively through this interface.
 * Each agent hosts one or more [IntegrationContextRuntime] instances.
 */
interface AgentConnection {
    val info: AgentInfo

    /** Deploy an integration to this agent. */
    fun deploy(cmd: DeployCommand): AgentCommandResult

    /** Start a deployed integration. */
    fun start(deploymentId: String): AgentCommandResult

    /** Stop a running integration. */
    fun stop(deploymentId: String): AgentCommandResult

    /** Undeploy (remove) an integration from this agent. */
    fun undeploy(deploymentId: String): AgentCommandResult

    /** Get status of all integrations on this agent. */
    fun status(): Map<String, Any?>

    /** Health check — is this agent reachable and functional? */
    fun healthCheck(): Boolean
}

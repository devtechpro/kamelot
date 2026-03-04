package io.devtech.integration.management

import io.devtech.integration.factory.IntegrationPackage
import io.devtech.integration.management.agent.AgentCommandResult
import io.devtech.integration.management.agent.AgentConnection
import io.devtech.integration.management.agent.DeployCommand
import io.devtech.integration.management.agent.LocalAgentConnection
import io.devtech.integration.management.model.*
import io.devtech.integration.management.artifact.ArtifactStore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Central orchestrator for the management plane.
 *
 * Manages agents (local/remote), deployments, artifacts, and lifecycle events.
 * All state changes flow through this class, which coordinates agents and
 * maintains the deployment registry and event log.
 */
class ManagementPlane(
    val artifactStore: ArtifactStore,
) {
    private val log = LoggerFactory.getLogger("mgmt.plane")

    private val agents = ConcurrentHashMap<String, AgentConnection>()
    private val deployments = ConcurrentHashMap<String, Deployment>()
    private val events = ConcurrentLinkedDeque<ManagementEvent>()
    private val maxEvents = 500

    // --- Agent registry ---

    fun registerAgent(agent: AgentConnection) {
        agents[agent.info.id] = agent
        addEvent(EventType.AGENT_CONNECTED, agent.info.id, "Agent registered: ${agent.info.name} (${agent.info.type})")
        log.info("Registered agent: {} ({}) type={}", agent.info.id, agent.info.name, agent.info.type)
    }

    fun getAgent(agentId: String): AgentConnection? = agents[agentId]

    fun listAgents(): List<AgentInfo> = agents.values.map { it.info }

    // --- Deployment from artifact ---

    /**
     * Deploy an artifact to an agent.
     * Stores a Deployment record and delegates to the agent.
     */
    fun deploy(
        artifactId: String,
        agentId: String,
        properties: Map<String, String> = emptyMap(),
        autoStart: Boolean = true,
    ): Deployment {
        val agent = agents[agentId] ?: error("Agent not found: $agentId")
        val meta = artifactStore.getMetadata(artifactId) ?: error("Artifact not found: $artifactId")

        val deployment = Deployment(
            artifactId = artifactId,
            agentId = agentId,
            integrationName = meta.integrationName,
            properties = properties,
            state = DeploymentState.DEPLOYING,
        )
        deployments[deployment.id] = deployment
        addEvent(EventType.DEPLOYMENT_CREATED, deployment.id, "Deploying ${meta.integrationName} to $agentId")

        val cmd = DeployCommand(
            deploymentId = deployment.id,
            artifactId = artifactId,
            integrationName = meta.integrationName,
            properties = properties,
        )

        val result = agent.deploy(cmd)
        if (result.success) {
            deployment.state = DeploymentState.DEPLOYED
            log.info("Deployed {} to {} (deployment={})", meta.integrationName, agentId, deployment.id)

            if (autoStart) {
                start(deployment.id)
            }
        } else {
            deployment.state = DeploymentState.FAILED
            deployment.error = result.error
            addEvent(EventType.DEPLOYMENT_FAILED, deployment.id, "Deploy failed: ${result.error}")
        }

        return deployment
    }

    /**
     * Deploy directly from an IntegrationPackage — used by the DSL for factory
     * and inline deploys. Only works with LocalAgentConnection.
     */
    fun deployDirect(
        pkg: IntegrationPackage,
        agentId: String,
        properties: Map<String, String> = emptyMap(),
        autoStart: Boolean = true,
    ): Deployment {
        val agent = agents[agentId] ?: error("Agent not found: $agentId")
        require(agent is LocalAgentConnection) { "Direct deploy only supported on local agents" }

        val deployment = Deployment(
            agentId = agentId,
            integrationName = pkg.integration.name,
            properties = properties,
            state = DeploymentState.DEPLOYING,
        )
        deployments[deployment.id] = deployment
        addEvent(EventType.DEPLOYMENT_CREATED, deployment.id, "Direct deploy ${pkg.integration.name} to $agentId")

        val result = agent.deployDirect(deployment.id, pkg)
        if (result.success) {
            deployment.state = DeploymentState.DEPLOYED
            log.info("Direct deployed {} to {} (deployment={})", pkg.integration.name, agentId, deployment.id)

            if (autoStart) {
                start(deployment.id)
            }
        } else {
            deployment.state = DeploymentState.FAILED
            deployment.error = result.error
            addEvent(EventType.DEPLOYMENT_FAILED, deployment.id, "Direct deploy failed: ${result.error}")
        }

        return deployment
    }

    // --- Lifecycle ---

    fun start(deploymentId: String): AgentCommandResult {
        val deployment = deployments[deploymentId] ?: error("Deployment not found: $deploymentId")
        val agent = agents[deployment.agentId] ?: error("Agent not found: ${deployment.agentId}")

        deployment.state = DeploymentState.STARTING
        addEvent(EventType.DEPLOYMENT_STARTING, deploymentId, "Starting ${deployment.integrationName}")

        val result = agent.start(deploymentId)
        if (result.success) {
            deployment.state = DeploymentState.RUNNING
            deployment.error = null
            addEvent(EventType.DEPLOYMENT_STARTED, deploymentId, "Started ${deployment.integrationName}")
        } else {
            deployment.state = DeploymentState.FAILED
            deployment.error = result.error
            addEvent(EventType.DEPLOYMENT_FAILED, deploymentId, "Start failed: ${result.error}")
        }
        return result
    }

    fun stop(deploymentId: String): AgentCommandResult {
        val deployment = deployments[deploymentId] ?: error("Deployment not found: $deploymentId")
        val agent = agents[deployment.agentId] ?: error("Agent not found: ${deployment.agentId}")

        deployment.state = DeploymentState.STOPPING
        addEvent(EventType.DEPLOYMENT_STOPPING, deploymentId, "Stopping ${deployment.integrationName}")

        val result = agent.stop(deploymentId)
        if (result.success) {
            deployment.state = DeploymentState.STOPPED
            addEvent(EventType.DEPLOYMENT_STOPPED, deploymentId, "Stopped ${deployment.integrationName}")
        } else {
            deployment.state = DeploymentState.FAILED
            deployment.error = result.error
            addEvent(EventType.DEPLOYMENT_FAILED, deploymentId, "Stop failed: ${result.error}")
        }
        return result
    }

    fun undeploy(deploymentId: String): AgentCommandResult {
        val deployment = deployments[deploymentId] ?: error("Deployment not found: $deploymentId")
        val agent = agents[deployment.agentId] ?: error("Agent not found: ${deployment.agentId}")

        val result = agent.undeploy(deploymentId)
        if (result.success) {
            deployments.remove(deploymentId)
            addEvent(EventType.DEPLOYMENT_UNDEPLOYED, deploymentId, "Undeployed ${deployment.integrationName}")
        }
        return result
    }

    // --- Queries ---

    fun getDeployment(id: String): Deployment? = deployments[id]

    fun listDeployments(): List<Deployment> = deployments.values.sortedByDescending { it.createdAt }

    fun listEvents(limit: Int = 50): List<ManagementEvent> = events.take(limit)

    fun health(): Map<String, Any?> {
        val agentStatuses = agents.map { (id, agent) ->
            mapOf(
                "id" to id,
                "name" to agent.info.name,
                "type" to agent.info.type.name,
                "healthy" to agent.healthCheck(),
                "status" to agent.info.status.name,
            )
        }
        val runningCount = deployments.values.count { it.state == DeploymentState.RUNNING }
        val failedCount = deployments.values.count { it.state == DeploymentState.FAILED }

        return mapOf(
            "status" to if (failedCount == 0) "healthy" else "degraded",
            "agents" to agentStatuses,
            "deployments" to mapOf(
                "total" to deployments.size,
                "running" to runningCount,
                "failed" to failedCount,
            ),
        )
    }

    // --- Events ---

    private fun addEvent(type: EventType, source: String, message: String, metadata: Map<String, Any?> = emptyMap()) {
        val event = ManagementEvent(type = type, source = source, message = message, metadata = metadata)
        events.addFirst(event)
        // Trim events to keep bounded
        while (events.size > maxEvents) {
            events.removeLast()
        }
    }
}

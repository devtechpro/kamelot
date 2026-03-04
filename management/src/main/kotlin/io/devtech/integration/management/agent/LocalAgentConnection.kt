package io.devtech.integration.management.agent

import io.devtech.integration.factory.IntegrationPackage
import io.devtech.integration.management.artifact.ArtifactStore
import io.devtech.integration.management.artifact.JarLoader
import io.devtech.integration.management.artifact.LoadedIntegration
import io.devtech.integration.management.model.AgentInfo
import io.devtech.integration.management.model.AgentStatus
import io.devtech.integration.management.model.AgentType
import io.devtech.integration.management.runtime.IntegrationContextRuntime
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Local (in-JVM) agent — runs integrations as [IntegrationContextRuntime] instances
 * in the same process as the management plane.
 *
 * Supports two deployment modes:
 * - **Direct**: from an [IntegrationPackage] (factory or inline DSL) — no JAR needed
 * - **Artifact**: from a JAR stored in [ArtifactStore] — loaded via [JarLoader]
 */
class LocalAgentConnection(
    agentId: String = "local",
    agentName: String = "local-agent",
    private val artifactStore: ArtifactStore? = null,
) : AgentConnection {

    private val log = LoggerFactory.getLogger("mgmt.agent.$agentId")

    override val info = AgentInfo(
        id = agentId,
        name = agentName,
        type = AgentType.LOCAL,
        status = AgentStatus.CONNECTED,
    )

    private val runtimes = ConcurrentHashMap<String, IntegrationContextRuntime>()
    private val loadedJars = ConcurrentHashMap<String, LoadedIntegration>()

    /**
     * Deploy directly from an IntegrationPackage — used for factory and inline deploys.
     * No JAR loading involved.
     */
    fun deployDirect(deploymentId: String, pkg: IntegrationPackage): AgentCommandResult {
        return try {
            val runtime = IntegrationContextRuntime(pkg.integration, pkg.adapterRefs)
            runtimes[deploymentId] = runtime
            log.info("Deployed '{}' directly (deployment={})", pkg.integration.name, deploymentId)
            AgentCommandResult(success = true, message = "Deployed ${pkg.integration.name}")
        } catch (e: Exception) {
            log.error("Direct deploy failed for {}: {}", deploymentId, e.message, e)
            AgentCommandResult(success = false, error = e.message)
        }
    }

    override fun deploy(cmd: DeployCommand): AgentCommandResult {
        return try {
            val store = artifactStore
                ?: return AgentCommandResult(success = false, error = "No artifact store configured")
            val artifactId = cmd.artifactId
                ?: return AgentCommandResult(success = false, error = "No artifactId in deploy command")
            val jarFile = store.getJarFile(artifactId)
                ?: return AgentCommandResult(success = false, error = "Artifact not found: $artifactId")
            val meta = store.getMetadata(artifactId)
                ?: return AgentCommandResult(success = false, error = "Metadata not found: $artifactId")

            val loaded = JarLoader.load(jarFile, meta.factoryClass, cmd.properties)
            loadedJars[cmd.deploymentId] = loaded

            val runtime = IntegrationContextRuntime(loaded.pkg.integration, loaded.pkg.adapterRefs)
            runtimes[cmd.deploymentId] = runtime

            log.info("Deployed '{}' from artifact {} (deployment={})", cmd.integrationName, artifactId, cmd.deploymentId)
            AgentCommandResult(success = true, message = "Deployed ${cmd.integrationName}")
        } catch (e: Exception) {
            log.error("Deploy failed for {}: {}", cmd.deploymentId, e.message, e)
            AgentCommandResult(success = false, error = e.message)
        }
    }

    override fun start(deploymentId: String): AgentCommandResult {
        val runtime = runtimes[deploymentId]
            ?: return AgentCommandResult(success = false, error = "Deployment not found: $deploymentId")
        return try {
            runtime.start()
            AgentCommandResult(success = true, message = "Started ${runtime.integration.name}", integrationStatus = runtime.status())
        } catch (e: Exception) {
            AgentCommandResult(success = false, error = e.message, integrationStatus = runtime.status())
        }
    }

    override fun stop(deploymentId: String): AgentCommandResult {
        val runtime = runtimes[deploymentId]
            ?: return AgentCommandResult(success = false, error = "Deployment not found: $deploymentId")
        return try {
            runtime.stop()
            AgentCommandResult(success = true, message = "Stopped ${runtime.integration.name}", integrationStatus = runtime.status())
        } catch (e: Exception) {
            AgentCommandResult(success = false, error = e.message, integrationStatus = runtime.status())
        }
    }

    override fun undeploy(deploymentId: String): AgentCommandResult {
        val runtime = runtimes.remove(deploymentId)
            ?: return AgentCommandResult(success = false, error = "Deployment not found: $deploymentId")
        return try {
            runtime.stop()
            // Clean up JAR resources if this was a JAR-loaded integration
            loadedJars.remove(deploymentId)?.let { JarLoader.cleanup(it) }
            log.info("Undeployed '{}' (deployment={})", runtime.integration.name, deploymentId)
            AgentCommandResult(success = true, message = "Undeployed ${runtime.integration.name}")
        } catch (e: Exception) {
            AgentCommandResult(success = false, error = e.message)
        }
    }

    override fun status(): Map<String, Any?> = mapOf(
        "agent" to info.id,
        "type" to info.type.name,
        "status" to info.status.name,
        "integrations" to runtimes.map { (id, rt) ->
            mapOf("deploymentId" to id) + rt.status()
        },
    )

    override fun healthCheck(): Boolean = true
}

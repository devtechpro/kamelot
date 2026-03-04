package io.devtech.integration.management.dsl

import io.devtech.integration.dsl.IntegrationBuilder
import io.devtech.integration.factory.DeploymentContext
import io.devtech.integration.factory.IntegrationFactory
import io.devtech.integration.factory.IntegrationPackage
import io.devtech.integration.management.ManagementPlane
import io.devtech.integration.management.agent.LocalAgentConnection
import io.devtech.integration.management.agent.RemoteAgentConnection
import io.devtech.integration.management.api.ManagementApi
import io.devtech.integration.management.artifact.ArtifactStore
import org.apache.camel.ProducerTemplate
import org.apache.camel.impl.DefaultCamelContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * DSL entry point for the management plane.
 *
 * ```kotlin
 * fun main() = manage(port = 9000) {
 *     localAgent()
 *     remoteAgent("worker-1", "http://worker1:8081")
 *
 *     deploy(EchoFactory())
 *     deploy(EchoFactory(), properties = mapOf("PORT" to "5500"))
 *
 *     deployInline("echo-dev") {
 *         val api = spec("examples/echo/specs/echo-openapi.yaml")
 *         expose(api, port = 5401)
 *         flow("echo", echoHandler)
 *     }
 * }
 * ```
 */
fun manage(
    port: Int = 9000,
    dataDir: File = File("management-data"),
    block: ManageBuilder.() -> Unit,
) {
    val builder = ManageBuilder(port, dataDir)
    builder.block()
    builder.run()
}

@DslMarker
annotation class ManageDsl

@ManageDsl
class ManageBuilder(
    private val port: Int,
    private val dataDir: File,
) {
    private val log = LoggerFactory.getLogger("mgmt.dsl")

    private val artifactStore = ArtifactStore(dataDir)
    private val plane = ManagementPlane(artifactStore)
    private var localAgentId: String? = null

    // Shared CamelContext for management-plane HTTP (remote agent calls)
    private val mgmtContext = DefaultCamelContext().apply {
        nameStrategy = org.apache.camel.impl.engine.DefaultCamelContextNameStrategy("mgmt-http")
    }
    private var mgmtTemplate: ProducerTemplate? = null
    private fun producerTemplate(): ProducerTemplate {
        return mgmtTemplate ?: mgmtContext.createProducerTemplate().also { mgmtTemplate = it }
    }

    private data class FactoryDeploy(
        val factory: IntegrationFactory,
        val agentId: String,
        val properties: Map<String, String>,
        val baseDir: File,
    )

    private data class InlineDeploy(
        val name: String,
        val agentId: String,
        val block: IntegrationBuilder.() -> Unit,
    )

    private val factoryDeploys = mutableListOf<FactoryDeploy>()
    private val inlineDeploys = mutableListOf<InlineDeploy>()

    /**
     * Register a local (in-JVM) agent. One per management plane.
     */
    fun localAgent(id: String = "local", name: String = "local-agent") {
        val agent = LocalAgentConnection(id, name, artifactStore)
        plane.registerAgent(agent)
        localAgentId = id
    }

    /**
     * Register a remote agent (separate JVM running AgentApi).
     */
    fun remoteAgent(id: String, endpoint: String, name: String = id) {
        val agent = RemoteAgentConnection(id, name, endpoint, ::producerTemplate)
        plane.registerAgent(agent)
    }

    /**
     * Deploy an integration from a factory class to the local agent.
     *
     * @param baseDir Base directory for resolving spec paths. Defaults to cwd.
     */
    fun deploy(
        factory: IntegrationFactory,
        agentId: String? = null,
        properties: Map<String, String> = emptyMap(),
        baseDir: File = File("."),
    ) {
        factoryDeploys += FactoryDeploy(factory, agentId ?: requireLocalAgent(), properties, baseDir)
    }

    /**
     * Deploy an inline integration definition to the local agent.
     * Useful during development — no JAR packaging needed.
     */
    fun deployInline(
        name: String,
        agentId: String? = null,
        block: IntegrationBuilder.() -> Unit,
    ) {
        inlineDeploys += InlineDeploy(name, agentId ?: requireLocalAgent(), block)
    }

    private fun requireLocalAgent(): String {
        return localAgentId ?: error("No local agent registered. Call localAgent() before deploy().")
    }

    internal fun run() {
        // Start management CamelContext for outbound HTTP (remote agent communication)
        mgmtContext.start()

        // Ensure at least one agent
        if (localAgentId == null && plane.listAgents().isEmpty()) {
            localAgent()
        }

        // Process factory deploys
        for (fd in factoryDeploys) {
            val ctx = DeploymentContext(baseDir = fd.baseDir, properties = fd.properties)
            val pkg = fd.factory.create(ctx)
            plane.deployDirect(pkg, fd.agentId, fd.properties)
        }

        // Process inline deploys
        for (id in inlineDeploys) {
            val builder = IntegrationBuilder(id.name).apply(id.block)
            val pkg = IntegrationPackage(builder.build(), builder.adapterRefs)
            plane.deployDirect(pkg, id.agentId)
        }

        // Start management API
        val api = ManagementApi(plane, port)
        api.start()

        log.info("Management plane ready — {} agent(s), {} deployment(s)",
            plane.listAgents().size, plane.listDeployments().size)
        log.info("Management API: http://0.0.0.0:{}", port)
        log.info("  Health:      http://0.0.0.0:{}/mgmt/health", port)
        log.info("  Deployments: http://0.0.0.0:{}/mgmt/deployments", port)
        log.info("  Artifacts:   http://0.0.0.0:{}/mgmt/artifacts", port)
        log.info("  Events:      http://0.0.0.0:{}/mgmt/events", port)

        // Block main thread
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Shutting down management plane...")
            api.stop()
            mgmtContext.stop()
        })

        Thread.currentThread().join()
    }
}

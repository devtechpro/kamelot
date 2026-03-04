package io.devtech.integration.management.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.devtech.integration.management.agent.LocalAgentConnection
import io.devtech.integration.management.artifact.ArtifactStore
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.impl.engine.DefaultCamelContextNameStrategy
import org.apache.camel.model.rest.RestBindingMode
import org.slf4j.LoggerFactory
import java.io.File

/**
 * REST API for a remote agent — runs on its own Camel REST DSL context (default port 8081).
 *
 * A remote agent is a standalone JVM that receives deployment commands from the
 * management plane. It has its own [ArtifactStore] and [LocalAgentConnection] to
 * manage integrations locally.
 *
 * Endpoints:
 * ```
 * POST   /agent/artifacts              Receive JAR bytes
 * POST   /agent/deploy                 Deploy integration from artifact
 * POST   /agent/integrations/{id}/start
 * POST   /agent/integrations/{id}/stop
 * DELETE /agent/integrations/{id}      Undeploy
 * GET    /agent/status                 Health + all integration statuses
 * ```
 */
class AgentApi(
    private val agent: LocalAgentConnection,
    private val artifactStore: ArtifactStore,
    private val port: Int = 8081,
    private val host: String = "0.0.0.0",
) {
    private val log = LoggerFactory.getLogger("agent.api.${agent.info.id}")
    private val mapper = jacksonObjectMapper()
    private val camelContext = DefaultCamelContext().apply {
        nameStrategy = DefaultCamelContextNameStrategy("agent-api-${agent.info.id}")
    }

    fun start() {
        camelContext.addRoutes(generateRoutes())
        camelContext.start()
        log.info("Agent API started on http://{}:{}", host, port)
    }

    fun stop() {
        camelContext.stop()
        log.info("Agent API stopped")
    }

    private fun generateRoutes(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            restConfiguration()
                .component("undertow")
                .host(host)
                .port(port)
                .bindingMode(RestBindingMode.off)

            rest("/agent")
                .post("/artifacts").to("direct:agent-receive-artifact")
                .post("/deploy").to("direct:agent-deploy")
                .post("/integrations/{id}/start").to("direct:agent-start")
                .post("/integrations/{id}/stop").to("direct:agent-stop")
                .delete("/integrations/{id}").to("direct:agent-undeploy")
                .get("/status").to("direct:agent-status")

            from("direct:agent-receive-artifact").routeId("agent-receive-artifact")
                .process { exchange -> receiveArtifact(exchange) }

            from("direct:agent-deploy").routeId("agent-deploy")
                .process { exchange -> deploy(exchange) }

            from("direct:agent-start").routeId("agent-start")
                .process { exchange -> start(exchange) }

            from("direct:agent-stop").routeId("agent-stop")
                .process { exchange -> stop(exchange) }

            from("direct:agent-undeploy").routeId("agent-undeploy")
                .process { exchange -> undeploy(exchange) }

            from("direct:agent-status").routeId("agent-status")
                .process { exchange -> status(exchange) }
        }
    }

    // --- Handlers ---

    private fun receiveArtifact(exchange: Exchange) {
        val fileName = exchange.message.getHeader("X-Filename", "upload.jar", String::class.java)
        val bytes = exchange.message.getBody(ByteArray::class.java)
        val meta = artifactStore.store(fileName, bytes)
        sendJson(exchange, 201, mapOf(
            "id" to meta.id,
            "integrationName" to meta.integrationName,
        ))
    }

    private fun deploy(exchange: Exchange) {
        val body: Map<String, Any?> = mapper.readValue(exchange.message.getBody(String::class.java))
        val deploymentId = body["deploymentId"] as? String ?: error("deploymentId required")
        val artifactId = body["artifactId"] as? String ?: error("artifactId required")
        val integrationName = body["integrationName"] as? String ?: "unknown"
        @Suppress("UNCHECKED_CAST")
        val properties = (body["properties"] as? Map<String, String>) ?: emptyMap()

        val cmd = io.devtech.integration.management.agent.DeployCommand(
            deploymentId = deploymentId,
            artifactId = artifactId,
            integrationName = integrationName,
            properties = properties,
        )
        val result = agent.deploy(cmd)
        val code = if (result.success) 200 else 500
        sendJson(exchange, code, mapOf(
            "success" to result.success,
            "message" to result.message,
            "error" to result.error,
        ))
    }

    private fun start(exchange: Exchange) {
        val deploymentId = exchange.message.getHeader("id", String::class.java)
        val result = agent.start(deploymentId)
        val code = if (result.success) 200 else 500
        sendJson(exchange, code, mapOf(
            "success" to result.success,
            "message" to result.message,
            "error" to result.error,
            "integrationStatus" to result.integrationStatus,
        ))
    }

    private fun stop(exchange: Exchange) {
        val deploymentId = exchange.message.getHeader("id", String::class.java)
        val result = agent.stop(deploymentId)
        val code = if (result.success) 200 else 500
        sendJson(exchange, code, mapOf(
            "success" to result.success,
            "message" to result.message,
            "error" to result.error,
            "integrationStatus" to result.integrationStatus,
        ))
    }

    private fun undeploy(exchange: Exchange) {
        val deploymentId = exchange.message.getHeader("id", String::class.java)
        val result = agent.undeploy(deploymentId)
        val code = if (result.success) 200 else 500
        sendJson(exchange, code, mapOf(
            "success" to result.success,
            "message" to result.message,
            "error" to result.error,
        ))
    }

    private fun status(exchange: Exchange) {
        sendJson(exchange, 200, agent.status())
    }

    // --- Helpers ---

    private fun sendJson(exchange: Exchange, statusCode: Int, body: Any?) {
        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode)
        exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
        exchange.message.body = mapper.writeValueAsString(body)
    }

    companion object {
        /**
         * Start a standalone remote agent with its own artifact store and API.
         */
        fun standalone(
            agentId: String = "remote-agent",
            agentName: String = "Remote Agent",
            port: Int = 8081,
            dataDir: File = File("agent-data"),
        ): AgentApi {
            val store = ArtifactStore(dataDir)
            val agent = LocalAgentConnection(agentId, agentName, store)
            return AgentApi(agent, store, port)
        }
    }
}

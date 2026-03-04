package io.devtech.integration.management.api

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.devtech.integration.management.ManagementPlane
import io.devtech.integration.model.redactSecrets
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.impl.engine.DefaultCamelContextNameStrategy
import org.apache.camel.model.rest.RestBindingMode
import org.slf4j.LoggerFactory

/**
 * REST API for the management plane — runs on its own Camel REST DSL context (default port 9000).
 *
 * Each ManagementApi gets its own [DefaultCamelContext] with its own `restConfiguration()`,
 * avoiding port conflicts with per-integration CamelContexts.
 *
 * Endpoints:
 * ```
 * POST   /mgmt/artifacts              Upload JAR (raw bytes, X-Filename header)
 * GET    /mgmt/artifacts              List artifacts
 * GET    /mgmt/artifacts/{id}/download
 * GET    /mgmt/agents                 List agents
 * POST   /mgmt/deployments            Deploy (artifactId, agentId, properties, autoStart)
 * GET    /mgmt/deployments            List deployments
 * POST   /mgmt/deployments/{id}/start Start integration
 * POST   /mgmt/deployments/{id}/stop  Stop integration
 * DELETE /mgmt/deployments/{id}       Undeploy
 * GET    /mgmt/health                 Aggregate health
 * GET    /mgmt/events                 Recent events
 * ```
 */
class ManagementApi(
    private val plane: ManagementPlane,
    private val port: Int = 9000,
    private val host: String = "0.0.0.0",
) {
    private val log = LoggerFactory.getLogger("mgmt.api")
    private val mapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
    private val camelContext = DefaultCamelContext().apply {
        nameStrategy = DefaultCamelContextNameStrategy("mgmt-api")
    }

    fun start() {
        camelContext.addRoutes(generateRoutes())
        camelContext.start()
        log.info("Management API started on http://{}:{}", host, port)
    }

    fun stop() {
        camelContext.stop()
        log.info("Management API stopped")
    }

    private fun generateRoutes(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            restConfiguration()
                .component("undertow")
                .host(host)
                .port(port)
                .bindingMode(RestBindingMode.off)

            // --- Artifacts ---
            rest("/mgmt")
                .post("/artifacts").to("direct:mgmt-upload-artifact")
                .get("/artifacts").to("direct:mgmt-list-artifacts")
                .get("/artifacts/{artifactId}/download").to("direct:mgmt-download-artifact")

            // --- Agents ---
            rest("/mgmt")
                .get("/agents").to("direct:mgmt-list-agents")

            // --- Deployments ---
            rest("/mgmt")
                .post("/deployments").to("direct:mgmt-create-deployment")
                .get("/deployments").to("direct:mgmt-list-deployments")
                .post("/deployments/{id}/start").to("direct:mgmt-start-deployment")
                .post("/deployments/{id}/stop").to("direct:mgmt-stop-deployment")
                .delete("/deployments/{id}").to("direct:mgmt-undeploy-deployment")

            // --- Health & Events ---
            rest("/mgmt")
                .get("/health").to("direct:mgmt-health")
                .get("/events").to("direct:mgmt-events")

            // --- Implementation routes ---

            from("direct:mgmt-upload-artifact").routeId("mgmt-upload-artifact")
                .process { exchange -> uploadArtifact(exchange) }

            from("direct:mgmt-list-artifacts").routeId("mgmt-list-artifacts")
                .process { exchange -> listArtifacts(exchange) }

            from("direct:mgmt-download-artifact").routeId("mgmt-download-artifact")
                .process { exchange -> downloadArtifact(exchange) }

            from("direct:mgmt-list-agents").routeId("mgmt-list-agents")
                .process { exchange -> listAgents(exchange) }

            from("direct:mgmt-create-deployment").routeId("mgmt-create-deployment")
                .process { exchange -> createDeployment(exchange) }

            from("direct:mgmt-list-deployments").routeId("mgmt-list-deployments")
                .process { exchange -> listDeployments(exchange) }

            from("direct:mgmt-start-deployment").routeId("mgmt-start-deployment")
                .process { exchange -> startDeployment(exchange) }

            from("direct:mgmt-stop-deployment").routeId("mgmt-stop-deployment")
                .process { exchange -> stopDeployment(exchange) }

            from("direct:mgmt-undeploy-deployment").routeId("mgmt-undeploy-deployment")
                .process { exchange -> undeployDeployment(exchange) }

            from("direct:mgmt-health").routeId("mgmt-health")
                .process { exchange -> health(exchange) }

            from("direct:mgmt-events").routeId("mgmt-events")
                .process { exchange -> events(exchange) }
        }
    }

    // --- Handlers ---

    private fun uploadArtifact(exchange: Exchange) {
        val fileName = exchange.message.getHeader("X-Filename", "upload.jar", String::class.java)
        val bytes = exchange.message.getBody(ByteArray::class.java)
        val meta = plane.artifactStore.store(fileName, bytes)
        sendJson(exchange, 201, mapOf(
            "id" to meta.id,
            "fileName" to meta.fileName,
            "integrationName" to meta.integrationName,
            "factoryClass" to meta.factoryClass,
            "storedAt" to meta.storedAt.toString(),
        ))
    }

    private fun listArtifacts(exchange: Exchange) {
        sendJson(exchange, 200, plane.artifactStore.list().map { meta ->
            mapOf(
                "id" to meta.id,
                "fileName" to meta.fileName,
                "integrationName" to meta.integrationName,
                "factoryClass" to meta.factoryClass,
                "storedAt" to meta.storedAt.toString(),
            )
        })
    }

    private fun downloadArtifact(exchange: Exchange) {
        val artifactId = exchange.message.getHeader("artifactId", String::class.java)
        val file = plane.artifactStore.getJarFile(artifactId)
        if (file == null) {
            sendJson(exchange, 404, mapOf("error" to "Artifact not found"))
            return
        }
        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, 200)
        exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/java-archive")
        exchange.message.body = file.readBytes()
    }

    private fun listAgents(exchange: Exchange) {
        sendJson(exchange, 200, plane.listAgents().map { agent ->
            mapOf(
                "id" to agent.id,
                "name" to agent.name,
                "type" to agent.type.name,
                "endpoint" to agent.endpoint,
                "status" to agent.status.name,
            )
        })
    }

    private fun createDeployment(exchange: Exchange) {
        val body: Map<String, Any?> = mapper.readValue(exchange.message.getBody(String::class.java))
        val artifactId = body["artifactId"] as? String ?: error("artifactId required")
        val agentId = body["agentId"] as? String ?: error("agentId required")
        @Suppress("UNCHECKED_CAST")
        val properties = (body["properties"] as? Map<String, String>) ?: emptyMap()
        val autoStart = body["autoStart"] as? Boolean ?: true

        val deployment = plane.deploy(artifactId, agentId, properties, autoStart)
        sendJson(exchange, 201, deploymentToMap(deployment))
    }

    private fun listDeployments(exchange: Exchange) {
        sendJson(exchange, 200, plane.listDeployments().map { deploymentToMap(it) })
    }

    private fun startDeployment(exchange: Exchange) {
        val deploymentId = exchange.message.getHeader("id", String::class.java)
        val result = plane.start(deploymentId)
        val code = if (result.success) 200 else 500
        sendJson(exchange, code, mapOf(
            "success" to result.success,
            "message" to result.message,
            "error" to result.error,
            "deployment" to plane.getDeployment(deploymentId)?.let { deploymentToMap(it) },
        ))
    }

    private fun stopDeployment(exchange: Exchange) {
        val deploymentId = exchange.message.getHeader("id", String::class.java)
        val result = plane.stop(deploymentId)
        val code = if (result.success) 200 else 500
        sendJson(exchange, code, mapOf(
            "success" to result.success,
            "message" to result.message,
            "error" to result.error,
            "deployment" to plane.getDeployment(deploymentId)?.let { deploymentToMap(it) },
        ))
    }

    private fun undeployDeployment(exchange: Exchange) {
        val deploymentId = exchange.message.getHeader("id", String::class.java)
        val result = plane.undeploy(deploymentId)
        val code = if (result.success) 200 else 500
        sendJson(exchange, code, mapOf(
            "success" to result.success,
            "message" to result.message,
            "error" to result.error,
        ))
    }

    private fun health(exchange: Exchange) {
        sendJson(exchange, 200, plane.health())
    }

    private fun events(exchange: Exchange) {
        val limit = exchange.message.getHeader("limit", "50", String::class.java).toIntOrNull() ?: 50
        sendJson(exchange, 200, plane.listEvents(limit).map { event ->
            mapOf(
                "id" to event.id,
                "type" to event.type.name,
                "source" to event.source,
                "message" to event.message,
                "timestamp" to event.timestamp.toString(),
                "metadata" to event.metadata,
            )
        })
    }

    // --- Helpers ---

    private fun deploymentToMap(d: io.devtech.integration.management.model.Deployment): Map<String, Any?> = mapOf(
        "id" to d.id,
        "artifactId" to d.artifactId,
        "agentId" to d.agentId,
        "integrationName" to d.integrationName,
        "state" to d.state.name,
        "properties" to d.properties.redactSecrets(),
        "error" to d.error,
        "createdAt" to d.createdAt.toString(),
    )

    private fun sendJson(exchange: Exchange, statusCode: Int, body: Any?) {
        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode)
        exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
        exchange.message.body = mapper.writeValueAsString(body)
    }
}

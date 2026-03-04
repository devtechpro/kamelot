package io.devtech.integration.management.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.devtech.integration.management.model.AgentInfo
import io.devtech.integration.management.model.AgentStatus
import io.devtech.integration.management.model.AgentType
import org.apache.camel.Exchange
import org.apache.camel.ProducerTemplate
import org.slf4j.LoggerFactory

/**
 * Remote agent connection — communicates with an agent running in a separate JVM
 * via Camel's HTTP component (ProducerTemplate).
 *
 * The remote agent must be running with AgentApi listening on [endpoint].
 */
class RemoteAgentConnection(
    agentId: String,
    agentName: String,
    private val endpoint: String,
    private val producerTemplate: () -> ProducerTemplate,
) : AgentConnection {

    private val log = LoggerFactory.getLogger("mgmt.remote.$agentId")
    private val mapper = jacksonObjectMapper()

    override val info = AgentInfo(
        id = agentId,
        name = agentName,
        type = AgentType.REMOTE,
        endpoint = endpoint,
        status = AgentStatus.CONNECTED,
    )

    override fun deploy(cmd: DeployCommand): AgentCommandResult {
        return try {
            // If we have JAR bytes, upload the artifact first
            if (cmd.jarBytes != null) {
                val exchange = camelRequest(
                    "POST", "/agent/artifacts",
                    body = cmd.jarBytes,
                    headers = mapOf("X-Filename" to "${cmd.integrationName}.jar"),
                )
                val responseCode = exchange.message.getHeader(Exchange.HTTP_RESPONSE_CODE, Int::class.java) ?: 200
                if (responseCode !in 200..299) {
                    return AgentCommandResult(
                        success = false,
                        error = "Artifact upload failed: ${exchange.message.getBody(String::class.java)}",
                    )
                }
            }

            // Send deploy command
            val body = mapper.writeValueAsString(mapOf(
                "deploymentId" to cmd.deploymentId,
                "artifactId" to cmd.artifactId,
                "integrationName" to cmd.integrationName,
                "properties" to cmd.properties,
            ))
            val exchange = camelRequest("POST", "/agent/deploy", body = body)
            parseResult(exchange.message.getBody(String::class.java) ?: "")
        } catch (e: Exception) {
            log.error("Remote deploy failed: {}", e.message)
            info.status = AgentStatus.DISCONNECTED
            AgentCommandResult(success = false, error = "Connection failed: ${e.message}")
        }
    }

    override fun start(deploymentId: String): AgentCommandResult {
        return try {
            val exchange = camelRequest("POST", "/agent/integrations/$deploymentId/start")
            parseResult(exchange.message.getBody(String::class.java) ?: "")
        } catch (e: Exception) {
            log.error("Remote start failed: {}", e.message)
            AgentCommandResult(success = false, error = "Connection failed: ${e.message}")
        }
    }

    override fun stop(deploymentId: String): AgentCommandResult {
        return try {
            val exchange = camelRequest("POST", "/agent/integrations/$deploymentId/stop")
            parseResult(exchange.message.getBody(String::class.java) ?: "")
        } catch (e: Exception) {
            log.error("Remote stop failed: {}", e.message)
            AgentCommandResult(success = false, error = "Connection failed: ${e.message}")
        }
    }

    override fun undeploy(deploymentId: String): AgentCommandResult {
        return try {
            val exchange = camelRequest("DELETE", "/agent/integrations/$deploymentId")
            parseResult(exchange.message.getBody(String::class.java) ?: "")
        } catch (e: Exception) {
            log.error("Remote undeploy failed: {}", e.message)
            AgentCommandResult(success = false, error = "Connection failed: ${e.message}")
        }
    }

    override fun status(): Map<String, Any?> {
        return try {
            val exchange = camelRequest("GET", "/agent/status")
            info.status = AgentStatus.CONNECTED
            mapper.readValue(exchange.message.getBody(String::class.java) ?: "{}")
        } catch (e: Exception) {
            info.status = AgentStatus.DISCONNECTED
            mapOf("agent" to info.id, "status" to "DISCONNECTED", "error" to e.message)
        }
    }

    override fun healthCheck(): Boolean {
        return try {
            val exchange = camelRequest("GET", "/agent/status")
            val responseCode = exchange.message.getHeader(Exchange.HTTP_RESPONSE_CODE, Int::class.java) ?: 200
            val healthy = responseCode in 200..299
            info.status = if (healthy) AgentStatus.CONNECTED else AgentStatus.DISCONNECTED
            healthy
        } catch (_: Exception) {
            info.status = AgentStatus.DISCONNECTED
            false
        }
    }

    /** Send an HTTP request to the remote agent via Camel's HTTP component. */
    private fun camelRequest(
        method: String,
        path: String,
        body: Any? = null,
        headers: Map<String, Any> = emptyMap(),
    ): Exchange {
        val uri = "${endpoint.trimEnd('/')}$path?bridgeEndpoint=true&throwExceptionOnFailure=false"
        return producerTemplate().request("http:$uri") { ex ->
            ex.message.setHeader(Exchange.HTTP_METHOD, method)
            if (body != null) {
                val contentType = if (body is ByteArray) "application/octet-stream" else "application/json"
                ex.message.setHeader("Content-Type", contentType)
                ex.message.body = body
            }
            headers.forEach { (k, v) -> ex.message.setHeader(k, v) }
        }
    }

    private fun parseResult(json: String): AgentCommandResult {
        return try {
            val map: Map<String, Any?> = mapper.readValue(json)
            AgentCommandResult(
                success = map["success"] as? Boolean ?: true,
                message = map["message"] as? String,
                error = map["error"] as? String,
                integrationStatus = map["integrationStatus"] as? Map<String, Any?>,
            )
        } catch (_: Exception) {
            AgentCommandResult(success = true, message = json)
        }
    }
}

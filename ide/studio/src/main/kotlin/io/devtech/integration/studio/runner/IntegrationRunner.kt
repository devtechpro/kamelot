package io.devtech.integration.studio.runner

import io.devtech.integration.management.runtime.IntegrationContextRuntime
import io.devtech.integration.management.runtime.IntegrationState
import io.devtech.integration.model.*
import io.devtech.integration.studio.model.Project
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages running integration instances from studio projects.
 *
 * Converts studio [Project] → framework [Integration] model, then delegates
 * to [IntegrationContextRuntime] for the actual Camel lifecycle.
 */
class IntegrationRunner {

    private val log = LoggerFactory.getLogger("studio.runner")
    private val runtimes = ConcurrentHashMap<String, IntegrationContextRuntime>()
    private val logBuffers = ConcurrentHashMap<String, LogBuffer>()
    private val adapterExecutors = ConcurrentHashMap<String, AdapterExecutor>()

    fun start(project: Project) {
        if (runtimes.containsKey(project.id)) {
            val existing = runtimes[project.id]!!
            if (existing.state == IntegrationState.RUNNING) {
                throw IllegalStateException("Integration '${project.name}' is already running")
            }
            // Clean up stale runtime
            stop(project.id)
        }

        val integration = buildIntegration(project)
        val runtime = IntegrationContextRuntime(integration)
        runtimes[project.id] = runtime

        log.info("Starting integration '{}' from project {}", project.name, project.id)
        runtime.start()
    }

    fun stop(projectId: String) {
        val runtime = runtimes.remove(projectId)
        runtime?.stop()
        logBuffers.remove(projectId)
        adapterExecutors.remove(projectId)?.close()
    }

    fun getLogBuffer(projectId: String): LogBuffer? = logBuffers[projectId]

    fun status(projectId: String): Map<String, Any?> {
        val runtime = runtimes[projectId]
            ?: return mapOf("state" to "STOPPED")
        return runtime.status()
    }

    fun isRunning(projectId: String): Boolean {
        return runtimes[projectId]?.state == IntegrationState.RUNNING
    }

    /**
     * If the project is currently running, stop and restart it with the latest config.
     * No-op if the project is not running.
     */
    fun hotReload(project: Project) {
        if (!isRunning(project.id)) return
        log.info("Hot-reloading integration '{}' (project {})", project.name, project.id)
        stop(project.id)
        val integration = buildIntegration(project)
        val runtime = IntegrationContextRuntime(integration)
        runtimes[project.id] = runtime
        runtime.start()
        log.info("Hot-reload complete for '{}'", project.name)
    }

    fun stopAll() {
        for (id in runtimes.keys.toList()) {
            try {
                stop(id)
            } catch (e: Exception) {
                log.warn("Error stopping integration {}: {}", id, e.message)
            }
        }
    }

    private fun buildIntegration(project: Project): Integration {
        val exposedSpecIds = project.expose?.specIds ?: emptyList()
        val exposedSpecFiles = project.specs.filter { it.id in exposedSpecIds }
        var exposeConfig: ExposeConfig? = null

        if (exposedSpecFiles.isNotEmpty()) {
            // Parse and merge all exposed specs into one combined Spec
            val allOperations = mutableMapOf<String, Operation>()
            val allSchemas = mutableMapOf<String, SchemaObject>()
            var combinedName = exposedSpecFiles.first().parsed.title

            for (specFile in exposedSpecFiles) {
                val parsed = parseSpecFromContent(specFile.content, specFile.filename)
                allOperations.putAll(parsed.operations)
                allSchemas.putAll(parsed.schemas)
            }

            if (exposedSpecFiles.size > 1) {
                combinedName = project.name
            }

            val combinedSpec = Spec(
                name = combinedName,
                path = exposedSpecFiles.first().filename,
                type = SpecType.OPENAPI,
                operations = allOperations,
                schemas = allSchemas,
            )

            exposeConfig = ExposeConfig(
                spec = combinedSpec,
                port = project.expose?.port ?: 8080,
                host = project.expose?.host ?: "0.0.0.0",
            )
        }

        // Build implementations from flows with a shared log buffer and adapter executor
        val logBuffer = LogBuffer()
        logBuffers[project.id] = logBuffer
        logBuffer.add("INFO", "Integration '${project.name}' starting")

        val adapterExecutor = if (project.adapters.isNotEmpty()) {
            AdapterExecutor(project.adapters).also { adapterExecutors[project.id] = it }
        } else null

        val implementations = project.flows.map { flow ->
            StepInterpreter.buildImplementation(flow, logBuffer, adapterExecutor)
        }

        return Integration(
            name = project.name,
            version = project.version,
            description = project.description,
            expose = exposeConfig,
            implementations = implementations,
        )
    }

    /**
     * Parse an OpenAPI spec from YAML content string, producing the framework [Spec] model.
     * Uses readContents() instead of read() to avoid SnakeYAML file-path encoding issues.
     */
    private fun parseSpecFromContent(yamlContent: String, filename: String): Spec {
        // Sanitize: replace C0/C1 control chars (except whitespace) that SnakeYAML rejects
        val sanitized = yamlContent.map { c ->
            if (c.code in 0x80..0x9F) ' ' else c
        }.joinToString("")

        val options = ParseOptions().apply { isResolve = true }
        val result = OpenAPIV3Parser().readContents(sanitized, null, options)
        val api = result.openAPI
            ?: throw IllegalArgumentException(
                "Failed to parse spec '$filename': ${result.messages.joinToString("; ")}"
            )

        return Spec(
            name = api.info?.title ?: filename.substringBeforeLast("."),
            path = filename,
            type = SpecType.OPENAPI,
            operations = parseOperations(api),
            schemas = parseSchemas(api),
        )
    }

    private fun parseOperations(api: OpenAPI): Map<String, Operation> {
        val operations = mutableMapOf<String, Operation>()
        api.paths?.forEach { (path, pathItem) ->
            pathItem.readOperationsMap()?.forEach { (method, op) ->
                val operationId = op.operationId ?: return@forEach
                val httpMethod = when (method) {
                    PathItem.HttpMethod.GET -> HttpMethod.GET
                    PathItem.HttpMethod.POST -> HttpMethod.POST
                    PathItem.HttpMethod.PUT -> HttpMethod.PUT
                    PathItem.HttpMethod.PATCH -> HttpMethod.PATCH
                    PathItem.HttpMethod.DELETE -> HttpMethod.DELETE
                    else -> return@forEach
                }
                val requestSchema = op.requestBody
                    ?.content?.get("application/json")
                    ?.schema?.`$ref`?.substringAfterLast("/")
                val responseSchema = op.responses
                    ?.get("200")?.content?.get("application/json")
                    ?.schema?.`$ref`?.substringAfterLast("/")
                val parameters = op.parameters?.map { param ->
                    Parameter(
                        name = param.name,
                        location = when (param.`in`) {
                            "path" -> ParameterLocation.PATH
                            "query" -> ParameterLocation.QUERY
                            "header" -> ParameterLocation.HEADER
                            else -> ParameterLocation.QUERY
                        },
                        required = param.required ?: false,
                        type = param.schema?.type ?: "string",
                    )
                } ?: emptyList()
                operations[operationId] = Operation(
                    operationId = operationId,
                    method = httpMethod,
                    path = path,
                    requestSchema = requestSchema,
                    responseSchema = responseSchema,
                    parameters = parameters,
                )
            }
        }
        return operations
    }

    private fun parseSchemas(api: OpenAPI): Map<String, SchemaObject> {
        val schemas = mutableMapOf<String, SchemaObject>()
        api.components?.schemas?.forEach { (name, schema) ->
            val fields = mutableMapOf<String, FieldDef>()
            schema.properties?.forEach { (fieldName, fieldSchema) ->
                fields[fieldName] = FieldDef(
                    name = fieldName,
                    type = when (fieldSchema.type) {
                        "string" -> FieldType.STRING
                        "integer" -> FieldType.INTEGER
                        "number" -> FieldType.NUMBER
                        "boolean" -> FieldType.BOOLEAN
                        "array" -> FieldType.ARRAY
                        "object" -> FieldType.OBJECT
                        else -> FieldType.STRING
                    },
                    format = fieldSchema.format,
                    description = fieldSchema.description,
                )
            }
            schemas[name] = SchemaObject(
                name = name,
                fields = fields,
                required = schema.required ?: emptyList(),
            )
        }
        return schemas
    }
}

package io.devtech.integration.studio.spec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import io.devtech.integration.studio.model.*
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

class SpecParser {

    private val yamlMapper = ObjectMapper(YAMLFactory())

    fun parse(yamlContent: String): ParsedSpec {
        // Parse raw YAML to extract $ref names before resolution
        val rawDoc = yamlMapper.readValue<Map<String, Any?>>(yamlContent)

        val options = ParseOptions().apply { isResolve = true }
        val result = OpenAPIV3Parser().readContents(yamlContent, null, options)
        val api = result.openAPI
            ?: throw IllegalArgumentException(
                "Failed to parse OpenAPI spec: ${result.messages.joinToString("; ")}"
            )

        return ParsedSpec(
            title = api.info?.title ?: "Untitled",
            operations = extractOperations(api, rawDoc),
            schemas = extractSchemas(api),
        )
    }

    private fun extractOperations(api: OpenAPI, rawDoc: Map<String, Any?>): List<OperationDef> {
        val operations = mutableListOf<OperationDef>()
        val paths = api.paths ?: return operations
        @Suppress("UNCHECKED_CAST")
        val rawPaths = rawDoc["paths"] as? Map<String, Any?> ?: emptyMap()

        for ((path, pathItem) in paths) {
            @Suppress("UNCHECKED_CAST")
            val rawPathItem = rawPaths[path] as? Map<String, Any?> ?: emptyMap()
            val methods = mapOf(
                "get" to pathItem.get,
                "post" to pathItem.post,
                "put" to pathItem.put,
                "patch" to pathItem.patch,
                "delete" to pathItem.delete,
            )

            for ((method, op) in methods) {
                if (op?.operationId == null) continue

                val parameters = mutableListOf<ParameterDef>()
                for (p in (pathItem.parameters ?: emptyList()) + (op.parameters ?: emptyList())) {
                    parameters.add(
                        ParameterDef(
                            name = p.name,
                            `in` = p.`in` ?: "query",
                            required = p.required ?: false,
                            type = p.schema?.type ?: "string",
                        )
                    )
                }

                // Extract $ref names from raw doc
                @Suppress("UNCHECKED_CAST")
                val rawOp = rawPathItem[method] as? Map<String, Any?>
                val requestSchema = extractRequestSchema(rawOp, op)
                val responseSchema = findResponseSchema(rawOp, op)

                operations.add(
                    OperationDef(
                        operationId = op.operationId,
                        method = method.uppercase(),
                        path = path,
                        summary = op.summary,
                        requestSchema = requestSchema,
                        responseSchema = responseSchema,
                        parameters = parameters,
                    )
                )
            }
        }
        return operations
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractRequestSchema(rawOp: Map<String, Any?>?, op: io.swagger.v3.oas.models.Operation): String? {
        // Try raw $ref first
        val rawBody = rawOp?.get("requestBody") as? Map<String, Any?>
        val rawContent = rawBody?.get("content") as? Map<String, Any?>
        val rawJson = rawContent?.get("application/json") as? Map<String, Any?>
        val rawSchema = rawJson?.get("schema") as? Map<String, Any?>
        val ref = rawSchema?.get("\$ref") as? String
        if (ref != null) return ref.split("/").last()

        // Fall back to resolved title
        return op.requestBody?.content?.get("application/json")?.schema?.title
    }

    @Suppress("UNCHECKED_CAST")
    private fun findResponseSchema(rawOp: Map<String, Any?>?, op: io.swagger.v3.oas.models.Operation): String? {
        val rawResponses = rawOp?.get("responses") as? Map<String, Any?>
        // Try raw $ref first, then resolved title
        for (responses in listOfNotNull(rawResponses)) {
            for (code in listOf("200", "201", "2XX", "default")) {
                val resp = responses[code] as? Map<String, Any?> ?: continue
                val content = resp["content"] as? Map<String, Any?> ?: continue
                val json = content["application/json"] as? Map<String, Any?> ?: continue
                val schema = json["schema"] as? Map<String, Any?> ?: continue
                val ref = schema["\$ref"] as? String
                if (ref != null) return ref.split("/").last()
                val items = schema["items"] as? Map<String, Any?>
                val itemsRef = items?.get("\$ref") as? String
                if (itemsRef != null) return itemsRef.split("/").last()
            }
        }
        // Fall back to resolved
        val resolvedResponses = op.responses ?: return null
        for (code in listOf("200", "201", "2XX", "default")) {
            val resp = resolvedResponses[code] ?: continue
            val schema = resp.content?.get("application/json")?.schema ?: continue
            if (schema.title != null) return schema.title
        }
        return null
    }

    private fun extractSchemas(api: OpenAPI): List<SchemaDef> {
        val schemas = mutableListOf<SchemaDef>()
        val components = api.components?.schemas ?: return schemas

        for ((name, schema) in components) {
            val fields = mutableListOf<FieldDef>()
            for ((fieldName, fieldSchema) in (schema.properties ?: emptyMap())) {
                fields.add(
                    FieldDef(
                        name = fieldName,
                        type = fieldSchema.type ?: "string",
                        format = fieldSchema.format,
                        description = fieldSchema.description,
                    )
                )
            }
            schemas.add(
                SchemaDef(
                    name = name,
                    fields = fields,
                    required = schema.required ?: emptyList(),
                )
            )
        }
        return schemas
    }
}

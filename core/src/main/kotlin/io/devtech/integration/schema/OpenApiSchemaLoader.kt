package io.devtech.integration.schema

import io.devtech.integration.model.*
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/**
 * Loads an OpenAPI spec file and produces a typed [Spec] model.
 * This is the foundation of spec-first: every field reference is validated against this.
 */
object OpenApiSchemaLoader {

    fun load(path: String): Spec {
        val options = ParseOptions().apply {
            isResolve = true
        }
        // Resolve relative paths against working directory
        val resolvedPath = java.io.File(path).let {
            if (it.exists()) it.absolutePath else path
        }
        val result = OpenAPIV3Parser().read(resolvedPath, null, options)
            ?: throw IllegalArgumentException("Failed to parse OpenAPI spec: $path (resolved: $resolvedPath, cwd: ${System.getProperty("user.dir")})")

        return Spec(
            name = result.info?.title ?: path.substringAfterLast("/").substringBeforeLast("."),
            path = path,
            type = SpecType.OPENAPI,
            operations = parseOperations(result),
            schemas = parseSchemas(result),
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
                    enumValues = fieldSchema.enum?.map { it.toString() },
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

    /**
     * Validates that all field references in an implementation exist in the target schema.
     */
    fun validateFieldRef(spec: Spec, schemaName: String, fieldName: String): Boolean {
        val schema = spec.schemas[schemaName] ?: return false
        return schema.fields.containsKey(fieldName)
    }
}

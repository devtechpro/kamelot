package io.devtech.integration.studio.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

data class Project(
    val id: String,
    val name: String,
    val version: Int = 1,
    val description: String? = null,
    val specs: MutableList<SpecFile> = mutableListOf(),
    var expose: ExposeConfig? = null,
    val adapters: MutableList<AdapterConfig> = mutableListOf(),
    val flows: MutableList<FlowConfig> = mutableListOf(),
    val createdAt: String,
    var updatedAt: String,
)

data class SpecFile(
    val id: String,
    val filename: String,
    val content: String,
    val parsed: ParsedSpec,
)

data class ParsedSpec(
    val title: String,
    val operations: List<OperationDef>,
    val schemas: List<SchemaDef>,
)

data class OperationDef(
    val operationId: String,
    val method: String,
    val path: String,
    val summary: String? = null,
    val requestSchema: String? = null,
    val responseSchema: String? = null,
    val parameters: List<ParameterDef> = emptyList(),
)

data class ParameterDef(
    val name: String,
    val `in`: String,
    val required: Boolean = false,
    val type: String = "string",
)

data class SchemaDef(
    val name: String,
    val fields: List<FieldDef>,
    val required: List<String> = emptyList(),
)

data class FieldDef(
    val name: String,
    val type: String,
    val format: String? = null,
    val description: String? = null,
)

data class ExposeConfig(
    val specIds: List<String> = emptyList(),
    val port: Int = 8080,
    val host: String = "0.0.0.0",
)

data class AdapterConfig(
    val id: String,
    val name: String,
    val specId: String? = null,
    val type: String,
    val baseUrl: String? = null,
    val postgres: PostgresAdapterConfig? = null,
)

data class PostgresAdapterConfig(
    val url: String,
    val username: String,
    val password: String? = null,
    val table: String,
    val schema: String? = null,
)

data class FlowConfig(
    val operationId: String,
    val steps: MutableList<StepConfig> = mutableListOf(),
    val statusCode: Int? = null,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(RespondStep::class, name = "respond"),
    JsonSubTypes.Type(ProcessStep::class, name = "process"),
    JsonSubTypes.Type(CallStep::class, name = "call"),
    JsonSubTypes.Type(LogStep::class, name = "log"),
    JsonSubTypes.Type(MapStep::class, name = "map"),
)
sealed class StepConfig {
    abstract val type: String
}

data class RespondStep(
    override val type: String = "respond",
    val fields: List<FieldEntry> = emptyList(),
) : StepConfig()

data class MapStep(
    override val type: String = "map",
    val fields: List<FieldEntry> = emptyList(),
) : StepConfig()

data class FieldEntry(val key: String, val value: String, val mode: String = "set")

data class ProcessStep(
    override val type: String = "process",
    val name: String = "",
    val expression: String = "body",
) : StepConfig()

data class CallStep(
    override val type: String = "call",
    val adapterName: String = "",
    val method: String = "GET",
    val path: String = "/",
) : StepConfig()

data class LogStep(
    override val type: String = "log",
    val message: String = "",
    val level: String = "INFO",
) : StepConfig()

// Request DTOs
data class CreateProjectRequest(val name: String, val description: String? = null)
data class UploadSpecRequest(val filename: String, val content: String)

package io.devtech.integration.model

/**
 * Top-level integration definition — the root of everything.
 */
data class Integration(
    val name: String,
    val version: Int = 1,
    val description: String? = null,
    val specs: List<Spec> = emptyList(),
    val adapters: List<Adapter> = emptyList(),
    val flows: List<Flow> = emptyList(),
    val expose: ExposeConfig? = null,
    val implementations: List<Implementation> = emptyList(),
)

/**
 * A parsed API spec — OpenAPI or GraphQL. The typed interface for an adapter.
 */
data class Spec(
    val name: String,
    val path: String,
    val type: SpecType,
    val operations: Map<String, Operation> = emptyMap(),
    val schemas: Map<String, SchemaObject> = emptyMap(),
)

enum class SpecType { OPENAPI, GRAPHQL }

/**
 * An API operation (endpoint) parsed from a spec.
 */
data class Operation(
    val operationId: String,
    val method: HttpMethod,
    val path: String,
    val requestSchema: String? = null,
    val responseSchema: String? = null,
    val parameters: List<Parameter> = emptyList(),
)

data class Parameter(
    val name: String,
    val location: ParameterLocation,
    val required: Boolean = false,
    val type: String = "string",
)

enum class ParameterLocation { PATH, QUERY, HEADER }

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

/**
 * A schema object from the spec — represents a type (EchoRequest, EchoResponse, etc.)
 */
data class SchemaObject(
    val name: String,
    val fields: Map<String, FieldDef> = emptyMap(),
    val required: List<String> = emptyList(),
)

data class FieldDef(
    val name: String,
    val type: FieldType,
    val format: String? = null,
    val description: String? = null,
    val enumValues: List<String>? = null,
)

enum class FieldType { STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT }

/**
 * An adapter — a spec bound to connection config.
 */
data class Adapter(
    val name: String,
    val spec: Spec? = null,
    val baseUrl: String = "",
    val auth: AuthConfig? = null,
    val config: Map<String, Any> = emptyMap(),
)

data class AuthConfig(
    val type: AuthType,
    val secret: Secret? = null,
    val header: String? = null,
    val param: String? = null,
)

enum class AuthType { BEARER, API_KEY_HEADER, API_KEY_PARAM, BASIC, OAUTH2 }

/**
 * A flow — a triggered pipeline.
 */
data class Flow(
    val name: String,
    val description: String? = null,
    val trigger: Trigger,
    val steps: List<Step> = emptyList(),
    val errorConfig: ErrorConfig? = null,
)

sealed class Trigger {
    data class Schedule(val intervalMs: Long) : Trigger()
    data class Cron(val expression: String) : Trigger()
    data class Webhook(val adapterName: String, val events: List<String>) : Trigger()
    data class After(val flowName: String) : Trigger()
    data object Manual : Trigger()
    data object ApiCall : Trigger()
}

sealed class Step {
    data class Call(
        val adapterName: String,
        val method: HttpMethod,
        val path: String,
        val config: CallConfig = CallConfig(),
    ) : Step()

    data class Transform(val mappings: List<FieldMapping>) : Step()
    data class Filter(val expression: String) : Step()
    data class Log(val message: String, val level: LogLevel = LogLevel.INFO) : Step()
    data class Process(
        val name: String = "",
        val handler: (Map<String, Any?>) -> Any?,
    ) : Step()
    data class Respond(
        val statusCode: Int = 200,
        val block: io.devtech.integration.dsl.ResponseBuilder.() -> Unit,
    ) : Step()
    data class MapFields(
        val block: io.devtech.integration.dsl.ResponseBuilder.() -> Unit,
    ) : Step()
}

data class CallConfig(
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val bodyExpression: String? = null,
)

data class FieldMapping(
    val source: String,
    val target: String,
    val transform: FieldTransform? = null,
)

sealed class FieldTransform {
    data class Format(val pattern: String) : FieldTransform()
    data class Mapped(val mappings: Map<String, String>, val default: String? = null) : FieldTransform()
    data class Constant(val value: String) : FieldTransform()
    data object Now : FieldTransform()
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Expose config — a spec-first server definition.
 */
data class ExposeConfig(
    val spec: Spec,
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val docsPath: String? = "/docs",
)

/**
 * Retry configuration — maps to Camel's onException with redelivery.
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val delayMs: Long = 500,
    val backoffMultiplier: Double = 2.0,
    val maxDelayMs: Long = 60_000,
)

/**
 * Circuit breaker configuration — maps to Camel's circuitBreaker() with Resilience4j.
 */
data class CircuitBreakerConfig(
    val slidingWindowSize: Int = 10,
    val minimumCalls: Int = 5,
    val failureRateThreshold: Float = 50.0f,
    val waitDurationInOpenStateMs: Long = 30_000,
    val permittedCallsInHalfOpen: Int = 3,
)

/**
 * Error handling configuration for a flow — retry and/or circuit breaker.
 */
data class ErrorConfig(
    val retry: RetryConfig? = null,
    val circuitBreaker: CircuitBreakerConfig? = null,
)

/**
 * An implementation wires a spec operation to handler logic.
 */
data class Implementation(
    val operationId: String,
    val steps: List<Step> = emptyList(),
    val handler: ((RequestContext) -> ResponseContext)? = null,
    val errorConfig: ErrorConfig? = null,
)

/**
 * Runtime context passed to handlers.
 */
data class RequestContext(
    val body: Map<String, Any?> = emptyMap(),
    val pathParams: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

data class ResponseContext(
    val statusCode: Int = 200,
    val body: Any? = null,
    val headers: Map<String, String> = emptyMap(),
)

package io.devtech.integration.dsl

import io.devtech.integration.model.*
import io.devtech.integration.schema.OpenApiSchemaLoader


/**
 * Top-level DSL entry point.
 *
 * ```kotlin
 * val echo = integration("echo-service") {
 *     version = 1
 *     description = "Simple echo service"
 *
 *     val api = spec("specs/echo-openapi.yaml")
 *     expose(api, port = 5400)
 *
 *     flow("echo", echoHandler)
 *     flow("health", healthHandler)
 * }
 * ```
 */
fun integration(name: String, block: IntegrationBuilder.() -> Unit): Integration {
    return IntegrationBuilder(name).apply(block).build()
}

@DslMarker
annotation class IntegrationDsl

@IntegrationDsl
class IntegrationBuilder(private val name: String) {
    var version: Int = 1
    var description: String? = null

    private val specs = mutableListOf<Spec>()
    private val adapters = mutableListOf<Adapter>()
    private val flows = mutableListOf<Flow>()
    private var expose: ExposeConfig? = null
    private val implementations = mutableListOf<Implementation>()
    private val _adapterRefs = mutableListOf<AdapterRef>()

    /** Live AdapterRef instances — used by the runtime to inject ProducerTemplate. */
    val adapterRefs: List<AdapterRef> get() = _adapterRefs.toList()

    /**
     * Load a spec from file. Returns a [SpecRef] for typed access to operations and schemas.
     */
    fun spec(path: String): SpecRef {
        val loaded = OpenApiSchemaLoader.load(path)
        specs += loaded
        return SpecRef(loaded)
    }

    /**
     * Expose an API — shorthand for common case (just port).
     *
     * ```kotlin
     * expose(api, port = 5400)
     * ```
     */
    fun expose(specRef: SpecRef, port: Int, host: String = "0.0.0.0") {
        expose = ExposeConfig(spec = specRef.spec, port = port, host = host)
    }

    /**
     * Expose an API — block form for advanced config.
     *
     * ```kotlin
     * expose(api) {
     *     port = 5400
     *     host = "0.0.0.0"
     * }
     * ```
     */
    fun expose(specRef: SpecRef, block: ExposeBuilder.() -> Unit) {
        expose = ExposeBuilder(specRef.spec).apply(block).build()
    }

    /**
     * Define an adapter — a typed connection to an external API.
     *
     * ```kotlin
     * val usersApi = adapter("jsonplaceholder", spec("specs/jp-openapi.yaml")) {
     *     baseUrl = "https://jsonplaceholder.typicode.com"
     * }
     * ```
     */
    fun adapter(name: String, specRef: SpecRef, block: AdapterBuilder.() -> Unit = {}): AdapterRef {
        val builder = AdapterBuilder(name, specRef.spec).apply(block)
        adapters += builder.build()
        val ref = builder.buildRef()
        _adapterRefs += ref
        return ref
    }

    /**
     * Wire a handler to a spec operation. The operationId must exist in the exposed spec.
     *
     * The operationId comes from the OpenAPI spec — each method+path combination has
     * a unique operationId. Multiple methods on the same path are separate operations:
     *
     * ```yaml
     * # In the OpenAPI spec:
     * paths:
     *   /users:
     *     get:
     *       operationId: listUsers
     *     post:
     *       operationId: createUser
     * ```
     *
     * ```kotlin
     * flow("listUsers") { ... }
     * flow("createUser") { ... }
     * ```
     *
     * Two forms — simple handler or block with optional error config:
     * ```kotlin
     * // Simple — pass handler directly (no trailing lambda)
     * flow("echo", echoHandler)
     *
     * // Block — with optional error handling config (trailing lambda)
     * flow("health") { handle { _ -> respond("status" to "ok") } }
     * flow("enrich") {
     *     onError {
     *         retry { maxAttempts = 3; delayMs = 500 }
     *         circuitBreaker { failureRateThreshold = 50f }
     *     }
     *     handle(enrichHandler(usersApi))
     * }
     * ```
     */
    @Suppress("UNUSED_PARAMETER")
    fun flow(operationId: String, handlerFn: (RequestContext) -> ResponseContext, unused: Unit = Unit) {
        validateFlowOperation(operationId)
        implementations += Implementation(operationId = operationId, handler = handlerFn)
        flows += Flow(
            name = operationId,
            trigger = Trigger.ApiCall,
            steps = listOf(Step.Process(operationId) { data ->
                val req = RequestContext(body = data)
                handlerFn(req).body
            }),
        )
    }

    /**
     * Wire a handler to a spec operation with optional error handling config.
     * Use trailing lambda syntax: `flow("op") { handle(...); onError { ... } }`.
     *
     * ```kotlin
     * flow("enrich") {
     *     onError {
     *         retry { maxAttempts = 3; delayMs = 500 }
     *         circuitBreaker { failureRateThreshold = 50f }
     *     }
     *     handle(enrichHandler(usersApi))
     * }
     * ```
     */
    fun flow(operationId: String, block: FlowBuilder.() -> Unit) {
        validateFlowOperation(operationId)
        val builder = FlowBuilder(operationId).apply(block)
        val impl = builder.build()
        implementations += impl
        _adapterRefs += builder.adapterRefs.filter { ref -> _adapterRefs.none { it.name == ref.name } }
        flows += Flow(
            name = operationId,
            trigger = Trigger.ApiCall,
            steps = if (impl.steps.isNotEmpty()) impl.steps else impl.handler?.let { h ->
                listOf(Step.Process(operationId) { data ->
                    val req = RequestContext(body = data)
                    h(req).body
                })
            } ?: emptyList(),
            errorConfig = impl.errorConfig,
        )
    }

    /**
     * Define a triggered (non-API) flow — timer, cron, event-chained, or manual.
     *
     * ```kotlin
     * triggered("sync-users") {
     *     every(5.minutes)
     *     call(usersApi, HttpMethod.GET, "/users")
     *     process { data -> transform(data) }
     *     log("Users synced")
     * }
     * ```
     */
    fun triggered(name: String, block: TriggeredFlowBuilder.() -> Unit) {
        val builder = TriggeredFlowBuilder(name).apply(block)
        flows += builder.build()
        _adapterRefs += builder.adapterRefs.filter { ref -> _adapterRefs.none { it.name == ref.name } }
    }

    private fun validateFlowOperation(operationId: String) {
        val spec = expose?.spec
            ?: error("Call expose() before flow(). No spec available to validate operation '$operationId'.")
        require(spec.operations.containsKey(operationId)) {
            "Operation '$operationId' not found in spec '${spec.name}'. Available: ${spec.operations.keys}"
        }
        require(implementations.none { it.operationId == operationId }) {
            "Operation '$operationId' already has a handler. Each operation can only be wired once."
        }
    }

    fun build(): Integration {
        return Integration(
            name = name,
            version = version,
            description = description,
            specs = specs,
            adapters = adapters,
            flows = flows,
            expose = expose,
            implementations = implementations,
        )
    }
}

/**
 * Typed reference to a loaded spec. Provides access to operations and schemas by name.
 */
class SpecRef(internal val spec: Spec) {
    val name: String get() = spec.name

    /** Access a schema by name — e.g. `EchoApi["EchoResponse"]` */
    operator fun get(schemaName: String): SchemaRef {
        val schema = spec.schemas[schemaName]
            ?: throw IllegalArgumentException("Schema '$schemaName' not found in spec '${spec.name}'. Available: ${spec.schemas.keys}")
        return SchemaRef(spec, schema)
    }
}

/**
 * Typed reference to a schema within a spec.
 */
class SchemaRef(internal val spec: Spec, internal val schema: SchemaObject) {
    val name: String get() = schema.name
    fun hasField(fieldName: String): Boolean = schema.fields.containsKey(fieldName)
    val fieldNames: Set<String> get() = schema.fields.keys
}


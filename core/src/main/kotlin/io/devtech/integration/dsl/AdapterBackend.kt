package io.devtech.integration.dsl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.devtech.integration.model.AuthConfig
import io.devtech.integration.model.AuthType
import io.devtech.integration.model.Secret
import org.apache.camel.Exchange
import org.apache.camel.ProducerTemplate
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Backend interface for adapter calls. Implementations handle the actual transport
 * (HTTP, in-memory, Postgres, etc.) while [AdapterRef] provides the user-facing API.
 */
interface AdapterBackend {
    fun execute(
        method: String,
        path: String,
        body: Any? = null,
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Any?
}

/**
 * Marker for backends that need a [ProducerTemplate] injected at runtime.
 * The runtime calls [bindProducerTemplate] after CamelContext is available
 * but before routes accept traffic.
 */
interface CamelBindable {
    fun bindProducerTemplate(template: ProducerTemplate)
}

/**
 * Camel-native HTTP backend — calls external REST APIs via Camel's HTTP component.
 * Uses [ProducerTemplate] with dynamic URIs, giving us Camel's connection pooling,
 * redirect handling, and the full component ecosystem.
 *
 * The ProducerTemplate is injected at runtime via [bindProducerTemplate].
 */
class CamelHttpBackend(
    private val adapterName: String,
    private val baseUrl: String,
    private val auth: AuthConfig? = null,
) : AdapterBackend, CamelBindable {

    private val mapper = jacksonObjectMapper()
    private val _producerTemplate = AtomicReference<ProducerTemplate?>(null)

    override fun bindProducerTemplate(template: ProducerTemplate) {
        _producerTemplate.set(template)
    }

    override fun execute(
        method: String,
        path: String,
        body: Any?,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
    ): Any? {
        val template = _producerTemplate.get()
            ?: error("Adapter '$adapterName': CamelContext not yet initialized. HTTP calls can only be made after the runtime has started.")

        val base = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val uri = "$base/$cleanPath?bridgeEndpoint=true&throwExceptionOnFailure=false"

        val camelHeaders = mutableMapOf<String, Any>(
            Exchange.HTTP_METHOD to method,
        )

        if (queryParams.isNotEmpty()) {
            val qs = queryParams.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
            }
            camelHeaders[Exchange.HTTP_QUERY] = qs
        }

        auth?.let { applyAuth(camelHeaders, it) }
        headers.forEach { (k, v) -> camelHeaders[k] = v }

        val requestBody = if (body != null) {
            camelHeaders["Content-Type"] = "application/json"
            mapper.writeValueAsString(body)
        } else {
            null
        }

        val exchange = template.request("http:$uri") { outExchange ->
            outExchange.message.headers.putAll(camelHeaders)
            outExchange.message.body = requestBody
        }

        val responseCode = exchange.message.getHeader(Exchange.HTTP_RESPONSE_CODE, Int::class.java) ?: 200
        val responseBody = exchange.message.getBody(String::class.java)

        if (responseCode !in 200..299) {
            throw AdapterCallException(adapterName, method, path, responseCode, responseBody)
        }

        return if (responseBody.isNullOrBlank()) {
            emptyMap<String, Any?>()
        } else {
            mapper.readValue<Any>(responseBody)
        }
    }

    private fun applyAuth(headers: MutableMap<String, Any>, auth: AuthConfig) {
        when (auth.type) {
            AuthType.BEARER -> headers["Authorization"] = "Bearer ${auth.secret?.value ?: ""}"
            AuthType.API_KEY_HEADER -> headers[auth.header ?: "X-API-Key"] = auth.secret?.value ?: ""
            AuthType.API_KEY_PARAM -> {} // handled in URL construction (future)
            AuthType.BASIC -> headers["Authorization"] = "Basic ${auth.secret?.value ?: ""}"
            AuthType.OAUTH2 -> headers["Authorization"] = "Bearer ${auth.secret?.value ?: ""}"
        }
    }
}

/**
 * In-memory backend — ConcurrentHashMap-based storage for **testing and prototyping only**.
 *
 * Not a production backend: no Camel involvement, no persistence, no metrics.
 * Use [CamelHttpBackend] for HTTP APIs or [PostgresBackend] for database storage.
 *
 * Routes requests by path pattern:
 * - `GET /items` → list all (supports `search` and `limit` query params)
 * - `GET /items/42` → get by ID
 * - `POST /items` → create (auto-generates ID)
 * - `PUT /items/42` → update
 * - `DELETE /items/42` → delete
 */
class InMemoryBackend(private val adapterName: String) : AdapterBackend {

    private val store = ConcurrentHashMap<String, ConcurrentHashMap<Long, Map<String, Any?>>>()
    private val sequences = ConcurrentHashMap<String, AtomicLong>()

    override fun execute(
        method: String,
        path: String,
        body: Any?,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
    ): Any? {
        val (collection, id) = parsePath(path)
        val table = store.getOrPut(collection) { ConcurrentHashMap() }
        val seq = sequences.getOrPut(collection) { AtomicLong(0) }

        return when (method.uppercase()) {
            "GET" -> if (id != null) {
                table[id] ?: throw AdapterCallException(adapterName, method, path, 404, "Not found")
            } else {
                var items = table.values.toList()
                queryParams["search"]?.let { search ->
                    val lower = search.lowercase()
                    items = items.filter { row ->
                        row.values.any { it?.toString()?.lowercase()?.contains(lower) == true }
                    }
                }
                queryParams["limit"]?.toIntOrNull()?.let { limit ->
                    items = items.take(limit)
                }
                items
            }

            "POST" -> {
                @Suppress("UNCHECKED_CAST")
                val data = (body as? Map<String, Any?>) ?: emptyMap()
                val newId = seq.incrementAndGet()
                val record = data + ("id" to newId)
                table[newId] = record
                record
            }

            "PUT" -> {
                requireNotNull(id) { "PUT requires an ID in path" }
                if (!table.containsKey(id)) {
                    throw AdapterCallException(adapterName, method, path, 404, "Not found")
                }
                @Suppress("UNCHECKED_CAST")
                val data = (body as? Map<String, Any?>) ?: emptyMap()
                val record = data + ("id" to id)
                table[id] = record
                record
            }

            "DELETE" -> {
                requireNotNull(id) { "DELETE requires an ID in path" }
                val removed = table.remove(id)
                    ?: throw AdapterCallException(adapterName, method, path, 404, "Not found")
                removed
            }

            else -> throw AdapterCallException(adapterName, method, path, 405, "Method not allowed")
        }
    }

    private fun parsePath(path: String): Pair<String, Long?> {
        val segments = path.trim('/').split("/")
        val collection = segments.first()
        val id = segments.getOrNull(1)?.toLongOrNull()
        return collection to id
    }
}

/**
 * Postgres backend — routes SQL through Camel's JDBC component.
 *
 * Registers a [javax.sql.DataSource] in the Camel registry at bind time,
 * then sends all SQL via `jdbc:dataSourceName`. This gives us Camel's connection
 * lifecycle management, tracing, and metrics for database operations.
 *
 * Routes requests by path pattern (same as InMemoryBackend):
 * - `GET /contacts` → `SELECT * FROM contacts`
 * - `GET /contacts/42` → `SELECT * FROM contacts WHERE id = 42`
 * - `POST /contacts` → `INSERT INTO contacts (...) VALUES (...) RETURNING *`
 * - `PUT /contacts/42` → `UPDATE contacts SET ... WHERE id = 42 RETURNING *`
 * - `DELETE /contacts/42` → `DELETE FROM contacts WHERE id = 42 RETURNING *`
 */
class PostgresBackend(
    private val adapterName: String,
    private val url: String,
    private val username: String,
    private val password: Secret,
    private val table: String,
    private val spec: io.devtech.integration.model.Spec? = null,
    private val schema: String? = null,
) : AdapterBackend, CamelBindable {

    private val mapper = jacksonObjectMapper()
    private val _producerTemplate = AtomicReference<ProducerTemplate?>(null)
    private val dsName = "ds-${adapterName}"
    private var tableCreated = false

    override fun bindProducerTemplate(template: ProducerTemplate) {
        _producerTemplate.set(template)
        val ds = com.zaxxer.hikari.HikariDataSource().apply {
            jdbcUrl = this@PostgresBackend.url
            username = this@PostgresBackend.username
            password = this@PostgresBackend.password.value
            maximumPoolSize = 10
            poolName = "pool-$adapterName"
        }
        template.camelContext.registry.bind(dsName, javax.sql.DataSource::class.java, ds)
    }

    override fun execute(
        method: String,
        path: String,
        body: Any?,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
    ): Any? {
        val (_, id) = parsePath(path)

        return when (method.uppercase()) {
            "GET" -> if (id != null) {
                val rows = sqlQuery("SELECT * FROM $table WHERE id = :#id", mapOf("id" to id))
                rows.firstOrNull() ?: throw AdapterCallException(adapterName, method, path, 404, "Not found")
            } else {
                val (sql, params) = buildListQuery(queryParams)
                sqlQuery(sql, params)
            }

            "POST" -> {
                @Suppress("UNCHECKED_CAST")
                val data = (body as? Map<String, Any?>) ?: emptyMap()
                ensureTable(data)
                val cols = data.keys.toList()
                val placeholders = cols.joinToString { ":#${it}" }
                val sql = "INSERT INTO $table (${cols.joinToString()}) VALUES ($placeholders) RETURNING *"
                val params = data.mapValues { (_, v) -> toJdbcValue(v) }
                val rows = sqlQuery(sql, params)
                rows.firstOrNull() ?: emptyMap<String, Any?>()
            }

            "PUT" -> {
                requireNotNull(id) { "PUT requires an ID in path" }
                @Suppress("UNCHECKED_CAST")
                val data = (body as? Map<String, Any?>) ?: emptyMap()
                val cols = data.keys.filter { it != "id" }
                val setClause = cols.joinToString { "$it = :#${it}" }
                val sql = "UPDATE $table SET $setClause WHERE id = :#id RETURNING *"
                val params = cols.associate { it to toJdbcValue(data[it]) } + ("id" to id)
                val rows = sqlQuery(sql, params)
                rows.firstOrNull() ?: throw AdapterCallException(adapterName, method, path, 404, "Not found")
            }

            "DELETE" -> {
                requireNotNull(id) { "DELETE requires an ID in path" }
                val rows = sqlQuery("DELETE FROM $table WHERE id = :#id RETURNING *", mapOf("id" to id))
                rows.firstOrNull() ?: throw AdapterCallException(adapterName, method, path, 404, "Not found")
            }

            else -> throw AdapterCallException(adapterName, method, path, 405, "Method not allowed")
        }
    }

    /** Send SQL through Camel's SQL component with named parameter binding. */
    @Suppress("UNCHECKED_CAST")
    private fun sqlQuery(sql: String, params: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> {
        val template = _producerTemplate.get()
            ?: error("Adapter '$adapterName': CamelContext not yet initialized.")

        val exchange = template.request("sql:$sql?dataSource=#$dsName") { ex ->
            // camel-sql resolves :#paramName from message body (Map), then headers
            ex.message.body = params
        }
        val result = exchange.message.body
        return when (result) {
            is List<*> -> result as List<Map<String, Any?>>
            else -> emptyList()
        }
    }

    /** Send raw DDL through Camel's JDBC component (no parameter binding needed). */
    private fun ddlExecute(sql: String) {
        val template = _producerTemplate.get()
            ?: error("Adapter '$adapterName': CamelContext not yet initialized.")
        template.request("jdbc:$dsName") { ex -> ex.message.body = sql }
    }

    private fun ensureTable(sampleData: Map<String, Any?>) {
        if (tableCreated) return
        val ddl = resolveSchema()?.let { schemaObj ->
            MigrationGenerator.generateCreate(schemaObj, table)
        } ?: fallbackDdl(sampleData)
        ddlExecute(ddl)
        tableCreated = true
    }

    /**
     * Find the entity schema to use for DDL. Prefers explicit [schema] name,
     * falls back to auto-detecting the schema with an `id` field.
     */
    private fun resolveSchema(): io.devtech.integration.model.SchemaObject? {
        val s = spec ?: return null
        if (schema != null) return s.schemas[schema]
        return s.schemas.values.firstOrNull { it.fields.containsKey("id") }
    }

    /** Legacy fallback: infer column types from the first INSERT's data. */
    private fun fallbackDdl(sampleData: Map<String, Any?>): String {
        val dataCols = sampleData.filter { it.key != "id" }.map { (k, v) ->
            when {
                v is Int || v is Long -> "$k BIGINT"
                v is Double || v is Float -> "$k DOUBLE PRECISION"
                v is Boolean -> "$k BOOLEAN"
                else -> "$k TEXT"
            }
        }
        val allCols = listOf("id BIGSERIAL PRIMARY KEY") + dataCols
        return "CREATE TABLE IF NOT EXISTS $table (${allCols.joinToString()})"
    }

    private fun buildListQuery(queryParams: Map<String, String>): Pair<String, Map<String, Any?>> {
        val sb = StringBuilder("SELECT * FROM $table")
        val params = mutableMapOf<String, Any?>()
        queryParams["search"]?.let { search ->
            sb.append(" WHERE CAST(ROW($table.*) AS TEXT) ILIKE :#searchPattern")
            params["searchPattern"] = "%$search%"
        }
        sb.append(" ORDER BY id")
        queryParams["limit"]?.toIntOrNull()?.let { limit ->
            sb.append(" LIMIT :#resultLimit")
            params["resultLimit"] = limit
        }
        return sb.toString() to params
    }

    private fun parsePath(path: String): Pair<String, Long?> {
        val segments = path.trim('/').split("/")
        val id = segments.getOrNull(1)?.toLongOrNull()
        return segments.first() to id
    }

    private fun toJdbcValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> mapper.writeValueAsString(value)
        is List<*> -> mapper.writeValueAsString(value)
        else -> value
    }
}

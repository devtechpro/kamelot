package io.devtech.integration.studio

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.devtech.integration.studio.codegen.DslGenerator
import io.devtech.integration.studio.model.*
import io.devtech.integration.studio.runner.IntegrationRunner
import io.devtech.integration.studio.spec.SpecParser
import io.devtech.integration.studio.store.ProjectStore
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.impl.engine.DefaultCamelContextNameStrategy
import org.apache.camel.model.rest.RestBindingMode
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

fun main() {
    val app = StudioApp()
    app.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        app.stop()
    })

    // Keep alive
    Thread.currentThread().join()
}

class StudioApp(
    private val port: Int = 5532,
    private val host: String = "0.0.0.0",
) {
    private val log = LoggerFactory.getLogger("studio")
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    private val store = ProjectStore()
    private val specParser = SpecParser()
    private val dslGenerator = DslGenerator()
    private val runner = IntegrationRunner()
    private val camelContext = DefaultCamelContext().apply {
        nameStrategy = DefaultCamelContextNameStrategy("studio-bff")
    }

    fun start() {
        camelContext.addRoutes(generateRoutes())
        camelContext.start()
        log.info("Studio BFF started on http://{}:{}", host, port)
    }

    fun stop() {
        runner.stopAll()
        camelContext.stop()
        log.info("Studio BFF stopped")
    }

    private fun generateRoutes(): RouteBuilder = object : RouteBuilder() {
        override fun configure() {
            restConfiguration()
                .component("undertow")
                .host(host)
                .port(port)
                .bindingMode(RestBindingMode.off)
                .corsHeaderProperty("Access-Control-Allow-Origin", "*")
                .corsHeaderProperty("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .corsHeaderProperty("Access-Control-Allow-Headers", "Content-Type, Accept")
                .enableCORS(true)

            // --- Health ---
            rest("/api")
                .get("/health").to("direct:studio-health")

            // --- Projects ---
            rest("/api")
                .get("/projects").to("direct:studio-list-projects")
                .post("/projects").to("direct:studio-create-project")
                .get("/projects/{id}").to("direct:studio-get-project")
                .put("/projects/{id}").to("direct:studio-update-project")
                .delete("/projects/{id}").to("direct:studio-delete-project")

            // --- Specs ---
            rest("/api")
                .post("/projects/{id}/specs").to("direct:studio-upload-spec")
                .delete("/projects/{id}/specs/{specId}").to("direct:studio-delete-spec")

            // --- DSL ---
            rest("/api")
                .get("/projects/{id}/dsl").to("direct:studio-generate-dsl")

            // --- Connectors ---
            rest("/api")
                .post("/projects/{id}/connectors/test").to("direct:studio-test-connection")
                .get("/projects/{id}/connectors/{adapterId}/tables").to("direct:studio-db-tables")
                .get("/projects/{id}/connectors/{adapterId}/tables/{table}/columns").to("direct:studio-db-columns")
                .get("/projects/{id}/connectors/{adapterId}/tables/{table}/rows").to("direct:studio-db-rows")

            // --- Runner ---
            rest("/api")
                .post("/projects/{id}/run").to("direct:studio-run")
                .post("/projects/{id}/stop").to("direct:studio-stop")
                .get("/projects/{id}/status").to("direct:studio-status")
                .get("/projects/{id}/logs").to("direct:studio-logs")

            // --- Implementation routes ---

            from("direct:studio-health").routeId("studio-health")
                .process { exchange -> health(exchange) }

            from("direct:studio-list-projects").routeId("studio-list-projects")
                .process { exchange -> listProjects(exchange) }

            from("direct:studio-create-project").routeId("studio-create-project")
                .process { exchange -> createProject(exchange) }

            from("direct:studio-get-project").routeId("studio-get-project")
                .process { exchange -> getProject(exchange) }

            from("direct:studio-update-project").routeId("studio-update-project")
                .process { exchange -> updateProject(exchange) }

            from("direct:studio-delete-project").routeId("studio-delete-project")
                .process { exchange -> deleteProject(exchange) }

            from("direct:studio-upload-spec").routeId("studio-upload-spec")
                .process { exchange -> uploadSpec(exchange) }

            from("direct:studio-delete-spec").routeId("studio-delete-spec")
                .process { exchange -> deleteSpec(exchange) }

            from("direct:studio-generate-dsl").routeId("studio-generate-dsl")
                .process { exchange -> generateDsl(exchange) }

            from("direct:studio-test-connection").routeId("studio-test-connection")
                .process { exchange -> testConnection(exchange) }

            from("direct:studio-db-tables").routeId("studio-db-tables")
                .process { exchange -> dbListTables(exchange) }

            from("direct:studio-db-columns").routeId("studio-db-columns")
                .process { exchange -> dbListColumns(exchange) }

            from("direct:studio-db-rows").routeId("studio-db-rows")
                .process { exchange -> dbListRows(exchange) }

            from("direct:studio-run").routeId("studio-run")
                .process { exchange -> runProject(exchange) }

            from("direct:studio-stop").routeId("studio-stop")
                .process { exchange -> stopProject(exchange) }

            from("direct:studio-status").routeId("studio-status")
                .process { exchange -> projectStatus(exchange) }

            from("direct:studio-logs").routeId("studio-logs")
                .process { exchange -> projectLogs(exchange) }
        }
    }

    // --- Handlers ---

    private fun health(exchange: Exchange) {
        sendJson(exchange, 200, mapOf("status" to "ok"))
    }

    private fun listProjects(exchange: Exchange) {
        sendJson(exchange, 200, store.list())
    }

    private fun createProject(exchange: Exchange) {
        val body: CreateProjectRequest = readBody(exchange)
        val now = Instant.now().toString()
        val project = Project(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = body.name,
            description = body.description,
            createdAt = now,
            updatedAt = now,
        )
        store.save(project)
        sendJson(exchange, 201, project)
    }

    private fun getProject(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val project = store.get(id)
        if (project == null) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return
        }
        sendJson(exchange, 200, project)
    }

    private fun updateProject(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val existing = store.get(id)
        if (existing == null) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return
        }

        // Merge incoming JSON with existing project
        val bodyStr = exchange.message.getBody(String::class.java) ?: "{}"
        val updates: Map<String, Any?> = mapper.readValue(bodyStr)

        val updated = existing.copy(
            name = (updates["name"] as? String) ?: existing.name,
            version = (updates["version"] as? Number)?.toInt() ?: existing.version,
            description = if (updates.containsKey("description")) updates["description"] as? String else existing.description,
        )

        // Update mutable collections if provided
        if (updates.containsKey("specs")) {
            val specsJson = mapper.writeValueAsString(updates["specs"])
            val specs: List<SpecFile> = mapper.readValue(specsJson)
            updated.specs.clear()
            updated.specs.addAll(specs)
        }

        val oldSpecIds = updated.expose?.specIds ?: emptyList()

        if (updates.containsKey("expose")) {
            updated.expose = if (updates["expose"] != null) {
                val exposeJson = mapper.writeValueAsString(updates["expose"])
                mapper.readValue(exposeJson, ExposeConfig::class.java)
            } else null
        }
        if (updates.containsKey("adapters")) {
            val adaptersJson = mapper.writeValueAsString(updates["adapters"])
            val adapters: List<AdapterConfig> = mapper.readValue(adaptersJson)
            updated.adapters.clear()
            updated.adapters.addAll(adapters)
        }
        if (updates.containsKey("flows")) {
            val flowsJson = mapper.writeValueAsString(updates["flows"])
            val flows: List<FlowConfig> = mapper.readValue(flowsJson)
            updated.flows.clear()
            updated.flows.addAll(flows)
        }

        // Sync flows when expose.specIds changes
        val newSpecIds = updated.expose?.specIds ?: emptyList()
        if (oldSpecIds.toSet() != newSpecIds.toSet()) {
            syncFlowsForExposedSpecs(updated, oldSpecIds.toSet(), newSpecIds.toSet())
        }

        store.save(updated)
        hotReloadIfRunning(updated)
        sendJson(exchange, 200, updated)
    }

    private fun deleteProject(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val deleted = store.delete(id)
        if (!deleted) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return
        }
        sendJson(exchange, 200, mapOf("deleted" to true))
    }

    private fun uploadSpec(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val project = store.get(id)
        if (project == null) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return
        }

        val body: UploadSpecRequest = readBody(exchange)

        // Parse the OpenAPI spec
        val parsed = try {
            specParser.parse(body.content)
        } catch (e: Exception) {
            sendJson(exchange, 400, mapOf("error" to "Invalid spec: ${e.message}"))
            return
        }

        val specFile = SpecFile(
            id = UUID.randomUUID().toString().substring(0, 8),
            filename = body.filename,
            content = body.content,
            parsed = parsed,
        )
        project.specs.add(specFile)

        // No auto-expose, no auto-flow-creation — spec goes to library only

        store.save(project)
        sendJson(exchange, 201, specFile)
    }

    private fun deleteSpec(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val specId = exchange.message.getHeader("specId", String::class.java)
        val project = store.get(id)
        if (project == null) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return
        }

        val spec = project.specs.find { it.id == specId }
        if (spec == null) {
            sendJson(exchange, 404, mapOf("error" to "Spec not found"))
            return
        }

        // Collect operation IDs that belong only to this spec
        val specOpIds = spec.parsed.operations.map { it.operationId }.toSet()
        val otherSpecOpIds = project.specs
            .filter { it.id != specId }
            .flatMap { it.parsed.operations.map { op -> op.operationId } }
            .toSet()
        val orphanedOps = specOpIds - otherSpecOpIds

        project.specs.removeIf { it.id == specId }

        // Remove from expose.specIds if present
        if (project.expose != null && specId in project.expose!!.specIds) {
            project.expose = project.expose!!.copy(
                specIds = project.expose!!.specIds.filter { it != specId }
            )
        }

        // Remove flows whose operationId belongs only to the deleted spec
        project.flows.removeIf { it.operationId in orphanedOps }

        store.save(project)
        hotReloadIfRunning(project)
        sendJson(exchange, 200, mapOf("deleted" to true))
    }

    // --- Connector handlers ---

    private fun testConnection(exchange: Exchange) {
        val body: Map<String, Any?> = readBody(exchange)
        val type = body["type"] as? String
        if (type != "postgres") {
            sendJson(exchange, 400, mapOf("ok" to false, "error" to "Only postgres test is supported"))
            return
        }
        val pg = body["postgres"] as? Map<*, *>
        val url = pg?.get("url") as? String
        val username = pg?.get("username") as? String
        val password = pg?.get("password") as? String ?: ""

        if (url.isNullOrBlank() || username.isNullOrBlank()) {
            sendJson(exchange, 400, mapOf("ok" to false, "error" to "URL and username are required"))
            return
        }

        try {
            DriverManager.getConnection(url, username, password).use { conn ->
                val meta = conn.metaData
                val serverInfo = "${meta.databaseProductName} ${meta.databaseProductVersion}"
                sendJson(exchange, 200, mapOf("ok" to true, "server" to serverInfo))
            }
        } catch (e: Exception) {
            val msg = e.message?.let { it.substringBefore("\n").take(200) } ?: "Connection failed"
            sendJson(exchange, 200, mapOf("ok" to false, "error" to msg))
        }
    }

    private fun resolveAdapter(exchange: Exchange): Pair<Project, AdapterConfig>? {
        val id = exchange.message.getHeader("id", String::class.java)
        val adapterId = exchange.message.getHeader("adapterId", String::class.java)
        val project = store.get(id)
        if (project == null) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return null
        }
        val adapter = project.adapters.find { it.id == adapterId }
        if (adapter == null) {
            sendJson(exchange, 404, mapOf("error" to "Connector not found"))
            return null
        }
        if (adapter.type != "postgres" || adapter.postgres == null) {
            sendJson(exchange, 400, mapOf("error" to "Connector is not a postgres type"))
            return null
        }
        return project to adapter
    }

    private fun dbListTables(exchange: Exchange) {
        val (_, adapter) = resolveAdapter(exchange) ?: return
        val pg = adapter.postgres!!
        try {
            DriverManager.getConnection(pg.url, pg.username, pg.password ?: "").use { conn ->
                val schema = pg.schema ?: "public"
                val tables = mutableListOf<Map<String, Any?>>()
                val rs = conn.metaData.getTables(null, schema, null, arrayOf("TABLE"))
                while (rs.next()) {
                    val tableName = rs.getString("TABLE_NAME")
                    // Get row count
                    val countRs = conn.prepareStatement("SELECT COUNT(*) FROM \"$schema\".\"$tableName\"").executeQuery()
                    val rowCount = if (countRs.next()) countRs.getLong(1) else 0L
                    countRs.close()
                    tables.add(mapOf("name" to tableName, "rowCount" to rowCount))
                }
                rs.close()
                sendJson(exchange, 200, mapOf("tables" to tables))
            }
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf("error" to (e.message?.take(200) ?: "Connection failed")))
        }
    }

    private fun dbListColumns(exchange: Exchange) {
        val (_, adapter) = resolveAdapter(exchange) ?: return
        val pg = adapter.postgres!!
        val table = exchange.message.getHeader("table", String::class.java)
        try {
            DriverManager.getConnection(pg.url, pg.username, pg.password ?: "").use { conn ->
                val schema = pg.schema ?: "public"
                // Get primary key columns
                val pkCols = mutableSetOf<String>()
                val pkRs = conn.metaData.getPrimaryKeys(null, schema, table)
                while (pkRs.next()) {
                    pkCols.add(pkRs.getString("COLUMN_NAME"))
                }
                pkRs.close()

                val columns = mutableListOf<Map<String, Any?>>()
                val colRs = conn.metaData.getColumns(null, schema, table, null)
                while (colRs.next()) {
                    val colName = colRs.getString("COLUMN_NAME")
                    columns.add(mapOf(
                        "name" to colName,
                        "type" to colRs.getString("TYPE_NAME"),
                        "nullable" to (colRs.getInt("NULLABLE") == 1),
                        "isPrimaryKey" to (colName in pkCols),
                    ))
                }
                colRs.close()
                sendJson(exchange, 200, mapOf("columns" to columns))
            }
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf("error" to (e.message?.take(200) ?: "Connection failed")))
        }
    }

    private fun dbListRows(exchange: Exchange) {
        val (_, adapter) = resolveAdapter(exchange) ?: return
        val pg = adapter.postgres!!
        val table = exchange.message.getHeader("table", String::class.java)
        val limit = exchange.message.getHeader("limit", String::class.java)?.toIntOrNull() ?: 50
        val offset = exchange.message.getHeader("offset", String::class.java)?.toIntOrNull() ?: 0
        val schema = pg.schema ?: "public"
        try {
            DriverManager.getConnection(pg.url, pg.username, pg.password ?: "").use { conn ->
                // Get total count
                val countStmt = conn.prepareStatement("SELECT COUNT(*) FROM \"$schema\".\"$table\"")
                val countRs = countStmt.executeQuery()
                val total = if (countRs.next()) countRs.getLong(1) else 0L
                countRs.close()
                countStmt.close()

                // Get rows
                val stmt = conn.prepareStatement("SELECT * FROM \"$schema\".\"$table\" LIMIT ? OFFSET ?")
                stmt.setInt(1, limit.coerceAtMost(200))
                stmt.setInt(2, offset)
                val rs = stmt.executeQuery()
                val meta = rs.metaData
                val rows = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    val row = mutableMapOf<String, Any?>()
                    for (i in 1..meta.columnCount) {
                        row[meta.getColumnName(i)] = rs.getObject(i)
                    }
                    rows.add(row)
                }
                rs.close()
                stmt.close()
                sendJson(exchange, 200, mapOf(
                    "rows" to rows,
                    "total" to total,
                    "limit" to limit,
                    "offset" to offset,
                ))
            }
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf("error" to (e.message?.take(200) ?: "Connection failed")))
        }
    }

    private fun generateDsl(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val project = store.get(id)
        if (project == null) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return
        }
        val dsl = dslGenerator.generate(project)
        sendJson(exchange, 200, mapOf("dsl" to dsl))
    }

    // --- Runner handlers ---

    private fun runProject(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val project = store.get(id)
        if (project == null) {
            sendJson(exchange, 404, mapOf("error" to "Project not found"))
            return
        }
        try {
            runner.start(project)
            val status = runner.status(id).toMutableMap()
            status["hotReload"] = true
            sendJson(exchange, 200, status)
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf(
                "error" to (e.message ?: "Failed to start integration"),
                "state" to "FAILED",
            ))
        }
    }

    private fun stopProject(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        try {
            runner.stop(id)
            sendJson(exchange, 200, mapOf("state" to "STOPPED"))
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf("error" to (e.message ?: "Failed to stop integration")))
        }
    }

    private fun projectStatus(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val status = runner.status(id).toMutableMap()
        status["hotReload"] = runner.isRunning(id)
        sendJson(exchange, 200, status)
    }

    private fun projectLogs(exchange: Exchange) {
        val id = exchange.message.getHeader("id", String::class.java)
        val since = exchange.message.getHeader("since", String::class.java)?.toLongOrNull() ?: 0L
        val buffer = runner.getLogBuffer(id)
        if (buffer == null) {
            sendJson(exchange, 200, mapOf("entries" to emptyList<Any>(), "cursor" to 0))
            return
        }
        val entries = buffer.since(since)
        val cursor = entries.lastOrNull()?.id ?: since
        sendJson(exchange, 200, mapOf("entries" to entries, "cursor" to cursor))
    }

    // --- Flow sync ---

    /**
     * When expose.specIds changes, create flow stubs for newly exposed operations
     * and remove flows for operations that are no longer exposed.
     */
    private fun syncFlowsForExposedSpecs(project: Project, oldIds: Set<String>, newIds: Set<String>) {
        val added = newIds - oldIds
        val removed = oldIds - newIds

        // Create flow stubs for newly exposed specs
        for (specId in added) {
            val spec = project.specs.find { it.id == specId } ?: continue
            for (op in spec.parsed.operations) {
                if (project.flows.none { it.operationId == op.operationId }) {
                    project.flows.add(FlowConfig(operationId = op.operationId))
                }
            }
        }

        // Remove flows for unexposed specs (only if operation doesn't belong to another exposed spec)
        val remainingExposedOps = project.specs
            .filter { it.id in newIds }
            .flatMap { it.parsed.operations.map { op -> op.operationId } }
            .toSet()
        for (specId in removed) {
            val spec = project.specs.find { it.id == specId } ?: continue
            for (op in spec.parsed.operations) {
                if (op.operationId !in remainingExposedOps) {
                    project.flows.removeIf { it.operationId == op.operationId }
                }
            }
        }
    }

    // --- Hot reload ---

    private fun hotReloadIfRunning(project: Project) {
        try {
            runner.hotReload(project)
        } catch (e: Exception) {
            log.warn("Hot-reload failed for '{}': {}", project.name, e.message)
        }
    }

    // --- Helpers ---

    private inline fun <reified T> readBody(exchange: Exchange): T {
        val bodyStr = exchange.message.getBody(String::class.java) ?: "{}"
        return mapper.readValue(bodyStr)
    }

    private fun sendJson(exchange: Exchange, statusCode: Int, body: Any?) {
        exchange.message.setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode)
        exchange.message.setHeader(Exchange.CONTENT_TYPE, "application/json")
        exchange.message.body = mapper.writeValueAsString(body)
    }
}

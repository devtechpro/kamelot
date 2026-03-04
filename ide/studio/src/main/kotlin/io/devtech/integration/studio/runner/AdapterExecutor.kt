package io.devtech.integration.studio.runner

import io.devtech.integration.model.RequestContext
import io.devtech.integration.studio.model.AdapterConfig
import io.devtech.integration.studio.model.CallStep
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Executes adapter calls against real backends (postgres, http).
 * Manages JDBC connections per adapter (simple pool — one connection per adapter name).
 */
class AdapterExecutor(
    private val adapters: List<AdapterConfig>,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger("studio.adapter")
    private val connections = mutableMapOf<String, Connection>()

    /**
     * Execute a call step. Returns the result as a Map (single row) or List<Map> (multiple rows),
     * or null if the adapter/method isn't supported.
     */
    fun execute(
        step: CallStep,
        body: Map<String, Any?>,
        req: RequestContext,
        logBuffer: LogBuffer?,
    ): Any? {
        val adapter = adapters.find { it.name == step.adapterName }
        if (adapter == null) {
            logBuffer?.add("ERROR", "  call: adapter '${step.adapterName}' not found")
            return null
        }

        return when (adapter.type) {
            "postgres" -> executePostgres(adapter, step, body, req, logBuffer)
            else -> {
                logBuffer?.add("WARN", "  call(${step.adapterName}, ${step.method}, \"${step.path}\") — ${adapter.type} not yet supported")
                null
            }
        }
    }

    private fun executePostgres(
        adapter: AdapterConfig,
        step: CallStep,
        body: Map<String, Any?>,
        req: RequestContext,
        logBuffer: LogBuffer?,
    ): Any? {
        val pg = adapter.postgres ?: run {
            logBuffer?.add("ERROR", "  call: adapter '${adapter.name}' has no postgres config")
            return null
        }
        val table = pg.table
        val conn = getConnection(adapter.name, pg.url, pg.username, pg.password ?: "")

        return when (step.method) {
            "GET" -> {
                val pathId = extractPathId(step.path, req)
                if (pathId != null) {
                    // GET with ID → single row
                    val idColumn = detectIdColumn(conn, table)
                    logBuffer?.add("DEBUG", "  call: SELECT * FROM $table WHERE $idColumn = '$pathId'")
                    selectOne(conn, table, idColumn, pathId)
                } else {
                    // GET without ID → list all
                    logBuffer?.add("DEBUG", "  call: SELECT * FROM $table")
                    selectAll(conn, table)
                }
            }

            "POST" -> {
                logBuffer?.add("DEBUG", "  call: INSERT INTO $table (${body.keys.joinToString()}) ...")
                insert(conn, table, body)
            }

            "PUT" -> {
                val pathId = extractPathId(step.path, req)
                val idColumn = detectIdColumn(conn, table)
                if (pathId != null) {
                    logBuffer?.add("DEBUG", "  call: UPDATE $table SET ... WHERE $idColumn = '$pathId'")
                    update(conn, table, body, idColumn, pathId)
                } else {
                    logBuffer?.add("WARN", "  call: PUT without path ID, skipping")
                    null
                }
            }

            "DELETE" -> {
                val pathId = extractPathId(step.path, req)
                val idColumn = detectIdColumn(conn, table)
                if (pathId != null) {
                    logBuffer?.add("DEBUG", "  call: DELETE FROM $table WHERE $idColumn = '$pathId'")
                    delete(conn, table, idColumn, pathId)
                } else {
                    logBuffer?.add("WARN", "  call: DELETE without path ID, skipping")
                    null
                }
            }

            else -> {
                logBuffer?.add("WARN", "  call: method ${step.method} not supported for postgres")
                null
            }
        }
    }

    /**
     * Extract path ID value from request path params.
     * The call step path may use a different param name (e.g. {product_id}) than the API path ({id}).
     * We try: the exact param name from the step path, then fall back to any single path param.
     */
    private fun extractPathId(stepPath: String, req: RequestContext): String? {
        // Extract param name from step path: /products/{product_id} → "product_id"
        val paramMatch = Regex("\\{(\\w+)}").find(stepPath)
        if (paramMatch != null) {
            val paramName = paramMatch.groupValues[1]
            // Try exact match first
            val value = req.pathParams[paramName]
            if (value != null) return value
        }
        // Fall back: return any path param value (usually there's just one — "id")
        return req.pathParams.values.firstOrNull()
    }

    private fun detectIdColumn(conn: Connection, table: String): String {
        try {
            val meta = conn.metaData
            // Try primary key first
            val pk = meta.getPrimaryKeys(null, null, table)
            if (pk.next()) {
                val col = pk.getString("COLUMN_NAME")
                pk.close()
                return col
            }
            pk.close()

            // No PK — scan columns for one ending in "_id" or named "id"
            val cols = meta.getColumns(null, null, table, null)
            val columnNames = mutableListOf<String>()
            while (cols.next()) {
                columnNames.add(cols.getString("COLUMN_NAME"))
            }
            cols.close()

            // Prefer exact "id", then anything ending with "_id"
            if ("id" in columnNames) return "id"
            columnNames.find { it.endsWith("_id") }?.let { return it }
            // Last resort: first column
            if (columnNames.isNotEmpty()) return columnNames[0]
        } catch (_: Exception) {}
        return "id"
    }

    private fun selectAll(conn: Connection, table: String): List<Map<String, Any?>> {
        val stmt = conn.prepareStatement("SELECT * FROM $table")
        val rs = stmt.executeQuery()
        val rows = resultSetToList(rs)
        rs.close()
        stmt.close()
        return rows
    }

    private fun selectOne(conn: Connection, table: String, idColumn: String, id: String): Map<String, Any?>? {
        val stmt = conn.prepareStatement("SELECT * FROM $table WHERE $idColumn = ?")
        stmt.setString(1, id)
        val rs = stmt.executeQuery()
        val row = if (rs.next()) resultSetToMap(rs) else null
        rs.close()
        stmt.close()
        return row
    }

    private fun insert(conn: Connection, table: String, body: Map<String, Any?>): Map<String, Any?> {
        ensureTable(conn, table, body)
        val columns = body.keys.toList()
        val placeholders = columns.joinToString { "?" }
        val sql = "INSERT INTO $table (${columns.joinToString()}) VALUES ($placeholders)"
        val stmt = conn.prepareStatement(sql)
        for ((i, col) in columns.withIndex()) {
            setParam(stmt, i + 1, body[col])
        }
        stmt.executeUpdate()
        stmt.close()
        return body
    }

    private fun update(conn: Connection, table: String, body: Map<String, Any?>, idColumn: String, id: String): Map<String, Any?>? {
        val columns = body.keys.filter { it != idColumn }
        if (columns.isEmpty()) return selectOne(conn, table, idColumn, id)
        val setClauses = columns.joinToString { "$it = ?" }
        val sql = "UPDATE $table SET $setClauses WHERE $idColumn = ?"
        val stmt = conn.prepareStatement(sql)
        for ((i, col) in columns.withIndex()) {
            setParam(stmt, i + 1, body[col])
        }
        stmt.setString(columns.size + 1, id)
        stmt.executeUpdate()
        stmt.close()
        // Return the updated row
        return selectOne(conn, table, idColumn, id)
    }

    private fun delete(conn: Connection, table: String, idColumn: String, id: String): Map<String, Any?> {
        val stmt = conn.prepareStatement("DELETE FROM $table WHERE $idColumn = ?")
        stmt.setString(1, id)
        val affected = stmt.executeUpdate()
        stmt.close()
        return mapOf("deleted" to (affected > 0))
    }

    /**
     * Auto-create table if it doesn't exist, deriving column types from the body.
     */
    private fun ensureTable(conn: Connection, table: String, body: Map<String, Any?>) {
        val meta = conn.metaData
        val tables = meta.getTables(null, null, table, arrayOf("TABLE"))
        val exists = tables.next()
        tables.close()
        if (exists) return

        val columns = body.entries.joinToString(",\n  ") { (key, value) ->
            val sqlType = when (value) {
                is Int, is Long -> "BIGINT"
                is Double, is Float, is Number -> "DOUBLE PRECISION"
                is Boolean -> "BOOLEAN"
                else -> "TEXT"
            }
            "$key $sqlType"
        }
        val sql = "CREATE TABLE $table (\n  $columns\n)"
        log.info("Auto-creating table: {}", sql)
        conn.createStatement().use { it.execute(sql) }
    }

    private fun setParam(stmt: java.sql.PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.setNull(index, java.sql.Types.NULL)
            is Int -> stmt.setInt(index, value)
            is Long -> stmt.setLong(index, value)
            is Double -> stmt.setDouble(index, value)
            is Float -> stmt.setFloat(index, value)
            is Number -> stmt.setDouble(index, value.toDouble())
            is Boolean -> stmt.setBoolean(index, value)
            else -> stmt.setString(index, value.toString())
        }
    }

    private fun resultSetToList(rs: ResultSet): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        while (rs.next()) {
            rows.add(resultSetToMap(rs))
        }
        return rows
    }

    private fun resultSetToMap(rs: ResultSet): Map<String, Any?> {
        val meta = rs.metaData
        val row = mutableMapOf<String, Any?>()
        for (i in 1..meta.columnCount) {
            row[meta.getColumnName(i)] = rs.getObject(i)
        }
        return row
    }

    private fun getConnection(name: String, url: String, username: String, password: String): Connection {
        val existing = connections[name]
        if (existing != null && !existing.isClosed) return existing
        val conn = DriverManager.getConnection(url, username, password)
        conn.autoCommit = true
        connections[name] = conn
        return conn
    }

    override fun close() {
        for ((_, conn) in connections) {
            try { conn.close() } catch (_: Exception) {}
        }
        connections.clear()
    }
}

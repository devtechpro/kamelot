package io.devtech.integration.studio.runner

import io.devtech.integration.model.RequestContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Evaluates simple expressions used in flow step fields.
 *
 * Supported expressions:
 * - `req.body["field"]` or `body["field"]` → extract from request body
 * - `req.pathParams["x"]` → extract path param
 * - `req.queryParams["x"]` → extract query param
 * - `now()` → current ISO timestamp
 * - `uuid()` → random UUID
 * - `today()` → current date (ISO)
 * - anything else → literal string
 */
object ExpressionEvaluator {

    fun evaluate(expression: String, req: RequestContext): Any? {
        val expr = expression.trim()

        // Function calls
        if (expr == "now()") return Instant.now().toString()
        if (expr == "uuid()") return UUID.randomUUID().toString()
        if (expr == "today()") return LocalDate.now().toString()

        // Body field access: req.body["field"] or body["field"]
        val bodyMatch = Regex("""(?:req\.)?body\["(.+?)"]""").matchEntire(expr)
        if (bodyMatch != null) {
            val key = bodyMatch.groupValues[1]
            return extractNested(req.body, key)
        }

        // Path param access: req.pathParams["field"]
        val pathMatch = Regex("""req\.pathParams\["(.+?)"]""").matchEntire(expr)
        if (pathMatch != null) {
            return req.pathParams[pathMatch.groupValues[1]]
        }

        // Query param access: req.queryParams["field"]
        val queryMatch = Regex("""req\.queryParams\["(.+?)"]""").matchEntire(expr)
        if (queryMatch != null) {
            return req.queryParams[queryMatch.groupValues[1]]
        }

        // Literal string (remove quotes if present)
        if (expr.startsWith("\"") && expr.endsWith("\"")) {
            return expr.substring(1, expr.length - 1)
        }

        return expr
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractNested(map: Map<String, Any?>, key: String): Any? {
        // Support dot-notation for nested access: "address.city"
        val parts = key.split(".")
        var current: Any? = map
        for (part in parts) {
            current = (current as? Map<String, Any?>)?.get(part) ?: return null
        }
        return current
    }
}

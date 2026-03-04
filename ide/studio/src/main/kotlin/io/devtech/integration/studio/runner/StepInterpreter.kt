package io.devtech.integration.studio.runner

import io.devtech.integration.model.*
import io.devtech.integration.studio.model.*
import org.slf4j.LoggerFactory

/**
 * Converts studio [FlowConfig] steps into framework [Implementation] objects
 * that can be executed by [IntegrationContextRuntime].
 */
object StepInterpreter {

    private val log = LoggerFactory.getLogger("studio.runner")

    /**
     * Build an [Implementation] from a studio [FlowConfig].
     * The handler chains all steps sequentially.
     * When [logBuffer] is provided, debug-level request logs are written to it.
     * When [adapterExecutor] is provided, call steps execute real adapter operations.
     */
    fun buildImplementation(
        flow: FlowConfig,
        logBuffer: LogBuffer? = null,
        adapterExecutor: AdapterExecutor? = null,
    ): Implementation {
        val statusCode = flow.statusCode ?: 200

        if (flow.steps.isEmpty()) {
            return Implementation(
                operationId = flow.operationId,
                handler = { _ ->
                    logBuffer?.add("DEBUG", "→ ${flow.operationId} (no steps)")
                    ResponseContext(statusCode = statusCode)
                },
            )
        }

        return Implementation(
            operationId = flow.operationId,
            handler = { req ->
                logBuffer?.add("INFO", "→ ${flow.operationId}")
                val result = executeSteps(flow.steps, req, statusCode, logBuffer, adapterExecutor)
                logBuffer?.add("DEBUG", "← ${result.statusCode}")
                result
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeSteps(
        steps: List<StepConfig>,
        req: RequestContext,
        defaultStatusCode: Int,
        logBuffer: LogBuffer?,
        adapterExecutor: AdapterExecutor?,
    ): ResponseContext {
        var body: Map<String, Any?> = req.body
        var response: ResponseContext? = null

        for (step in steps) {
            when (step) {
                is ProcessStep -> {
                    log.debug("Process step '{}' executed", step.name)
                    logBuffer?.add("DEBUG", "  process(\"${step.name}\")")
                }

                is LogStep -> {
                    val message = step.message
                    when (step.level) {
                        "DEBUG" -> log.debug(message)
                        "WARN" -> log.warn(message)
                        "ERROR" -> log.error(message)
                        else -> log.info(message)
                    }
                    logBuffer?.add(step.level, "  log: $message")
                }

                is CallStep -> {
                    if (adapterExecutor != null) {
                        try {
                            val result = adapterExecutor.execute(step, body, req, logBuffer)
                            when (result) {
                                is Map<*, *> -> body = result as Map<String, Any?>
                                is List<*> -> {
                                    // List result — wrap so respond can access it
                                    body = mapOf("_list" to result, "_isList" to true)
                                }
                                null -> logBuffer?.add("WARN", "  call: returned null")
                            }
                        } catch (e: Exception) {
                            logBuffer?.add("ERROR", "  call: ${e.message}")
                            log.error("Call step failed: {}", e.message, e)
                        }
                    } else {
                        logBuffer?.add("WARN", "  call(${step.adapterName}, ${step.method}, \"${step.path}\") — not wired")
                    }
                }

                is MapStep -> {
                    val mapped = mutableMapOf<String, Any?>()
                    for (field in step.fields) {
                        mapped[field.key] = if (field.mode == "to") {
                            body[field.value]
                        } else {
                            ExpressionEvaluator.evaluate(field.value, req)
                        }
                    }
                    logBuffer?.add("DEBUG", "  map: ${body.keys} → ${mapped.keys}")
                    body = mapped
                }

                is RespondStep -> {
                    // If the body is a list result from a call, map each row
                    if (body["_isList"] == true && step.fields.isNotEmpty()) {
                        val rows = body["_list"] as? List<Map<String, Any?>> ?: emptyList()
                        val mappedRows = rows.map { row ->
                            val mappedRow = mutableMapOf<String, Any?>()
                            for (field in step.fields) {
                                mappedRow[field.key] = if (field.mode == "to") {
                                    row[field.value]
                                } else {
                                    ExpressionEvaluator.evaluate(field.value, req)
                                }
                            }
                            mappedRow
                        }
                        logBuffer?.add("DEBUG", "  respond: ${mappedRows.size} rows, fields=${step.fields.map { it.key }}")
                        response = ResponseContext(
                            statusCode = defaultStatusCode,
                            body = mappedRows,
                        )
                    } else {
                        val responseBody = mutableMapOf<String, Any?>()
                        for (field in step.fields) {
                            responseBody[field.key] = if (field.mode == "to") {
                                body[field.value]
                            } else {
                                ExpressionEvaluator.evaluate(field.value, req)
                            }
                        }
                        logBuffer?.add("DEBUG", "  respond: ${responseBody.keys}")
                        response = ResponseContext(
                            statusCode = defaultStatusCode,
                            body = responseBody,
                        )
                    }
                }
            }
        }

        return response ?: ResponseContext(statusCode = defaultStatusCode, body = body)
    }
}

package io.devtech.integration.studio.codegen

import io.devtech.integration.studio.model.*

class DslGenerator {

    fun generate(project: Project): String {
        val lines = mutableListOf<String>()

        lines += """fun main() = execute("${esc(project.name)}") {"""
        lines += "${I(1)}version = ${project.version}"
        if (project.description != null) {
            lines += """${I(1)}description = "${esc(project.description)}""""
        }
        lines += ""

        // Spec + expose
        val exposedSpecIds = project.expose?.specIds ?: emptyList()
        val exposedSpecs = project.specs.filter { it.id in exposedSpecIds }
        if (exposedSpecs.isNotEmpty()) {
            for ((i, spec) in exposedSpecs.withIndex()) {
                val varName = if (exposedSpecs.size == 1) "api" else "api${i + 1}"
                lines += """${I(1)}val $varName = spec("specs/${spec.filename}")"""
            }
            val specVars = if (exposedSpecs.size == 1) "api" else exposedSpecs.indices.joinToString(", ") { "api${it + 1}" }
            lines += "${I(1)}expose($specVars, port = ${project.expose?.port ?: 8080})"
            lines += ""
        }

        // Adapters (connectors)
        for (adapter in project.adapters) {
            val boundSpec = project.specs.find { it.id == adapter.specId }
            lines += generateAdapter(adapter, boundSpec)
            lines += ""
        }

        // Flows
        for (flow in project.flows) {
            lines += generateFlow(flow, project.adapters)
            lines += ""
        }

        lines += "}"
        return lines.joinToString("\n")
    }

    private fun generateAdapter(adapter: AdapterConfig, boundSpec: SpecFile?): List<String> {
        val lines = mutableListOf<String>()
        val varName = toCamelCase(adapter.name)
        val specPath = if (boundSpec != null) "specs/${boundSpec.filename}" else "specs/${adapter.name}-spec.yaml"

        when (adapter.type) {
            "http" -> {
                lines += """${I(1)}val $varName = adapter("${adapter.name}", spec("$specPath")) {"""
                if (adapter.baseUrl != null) {
                    lines += """${I(2)}baseUrl = "${esc(adapter.baseUrl)}""""
                }
                lines += "${I(1)}}"
            }
            "postgres" -> {
                val pg = adapter.postgres
                lines += """${I(1)}val $varName = adapter("${adapter.name}", spec("$specPath")) {"""
                lines += "${I(2)}postgres {"
                if (pg?.url != null) lines += """${I(3)}url = env("POSTGRES_URL", "${esc(pg.url)}")"""
                if (pg?.username != null) lines += """${I(3)}username = env("POSTGRES_USER", "${esc(pg.username)}")"""
                lines += """${I(3)}password = secret("POSTGRES_PASSWORD", "postgres")"""
                if (pg?.table != null) lines += """${I(3)}table = "${esc(pg.table)}""""
                if (pg?.schema != null) lines += """${I(3)}schema = "${esc(pg.schema)}""""
                lines += "${I(2)}}"
                lines += "${I(1)}}"
            }
            else -> {
                lines += """${I(1)}val $varName = adapter("${adapter.name}", spec("$specPath")) {"""
                lines += "${I(2)}inMemory()"
                lines += "${I(1)}}"
            }
        }
        return lines
    }

    private fun generateFlow(flow: FlowConfig, adapters: List<AdapterConfig>): List<String> {
        val lines = mutableListOf<String>()

        // Single respond step → respond builder
        if (flow.steps.size == 1 && flow.steps[0] is RespondStep) {
            val step = flow.steps[0] as RespondStep
            lines += """${I(1)}flow("${flow.operationId}") {"""
            lines += "${I(2)}respond {"
            for (f in step.fields) {
                lines += "${I(3)}${fieldLine(f)}"
            }
            lines += "${I(2)}}"
            lines += "${I(1)}}"
            return lines
        }

        // Multi-step declarative
        val hasNonRespond = flow.steps.any { it !is RespondStep }
        if (hasNonRespond || flow.steps.size > 1) {
            lines += """${I(1)}flow("${flow.operationId}") {"""
            if (flow.statusCode != null && flow.statusCode != 200) {
                lines += "${I(2)}statusCode = ${flow.statusCode}"
            }
            for (step in flow.steps) {
                lines += generateStep(step, adapters, 2)
            }
            lines += "${I(1)}}"
            return lines
        }

        // Empty flow
        lines += """${I(1)}flow("${flow.operationId}") {"""
        lines += "${I(2)}handle { _ -> respond(statusCode = 200) }"
        lines += "${I(1)}}"
        return lines
    }

    private fun generateStep(step: StepConfig, adapters: List<AdapterConfig>, depth: Int): List<String> {
        val lines = mutableListOf<String>()
        when (step) {
            is ProcessStep -> {
                lines += """${I(depth)}process("${esc(step.name)}") { body ->"""
                val expr = step.expression.trim().ifEmpty { "body" }
                lines += "${I(depth + 1)}$expr"
                lines += "${I(depth)}}"
            }
            is CallStep -> {
                val varName = toCamelCase(step.adapterName)
                lines += """${I(depth)}call($varName, HttpMethod.${step.method}, "${esc(step.path)}")"""
            }
            is LogStep -> {
                val levelSuffix = if (step.level != "INFO") ", LogLevel.${step.level}" else ""
                lines += """${I(depth)}log("${esc(step.message)}"$levelSuffix)"""
            }
            is RespondStep -> {
                lines += "${I(depth)}respond {"
                for (f in step.fields) {
                    lines += "${I(depth + 1)}${fieldLine(f)}"
                }
                lines += "${I(depth)}}"
            }

            is MapStep -> {
                lines += "${I(depth)}map {"
                for (f in step.fields) {
                    lines += "${I(depth + 1)}${fieldLine(f)}"
                }
                lines += "${I(depth)}}"
            }
        }
        return lines
    }

    private fun fieldLine(f: FieldEntry): String {
        return if (f.mode == "to") {
            """"${esc(f.key)}" to "${esc(f.value)}""""
        } else {
            """"${esc(f.key)}" set ${resolveValue(f.value)}"""
        }
    }

    private fun resolveValue(value: String): String {
        if (value.startsWith("req.") || value.startsWith("body.") || value.startsWith("body[")) return value
        if (value.matches(Regex("^(now|uuid|today|slugify|env|secret)\\(.*"))) return value
        return """"${esc(value)}""""
    }

    private fun toCamelCase(name: String): String =
        name.replace(Regex("[-_](\\w)")) { it.groupValues[1].uppercase() }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private fun I(n: Int) = "    ".repeat(n)
    }
}

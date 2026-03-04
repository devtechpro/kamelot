package io.devtech.integration.studio.store

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.devtech.integration.studio.model.ExposeConfig
import io.devtech.integration.studio.model.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

class ProjectStore(private val dataDir: Path = Path.of("ide/bff/data/projects")) {

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    init {
        Files.createDirectories(dataDir)
    }

    fun list(): List<Project> {
        return dataDir.listDirectoryEntries("*.json")
            .map { migrate(mapper.readValue<Project>(it.toFile())) }
            .sortedByDescending { it.updatedAt }
    }

    fun get(id: String): Project? {
        val file = dataDir.resolve("$id.json")
        if (!file.exists()) return null
        return migrate(mapper.readValue<Project>(file.toFile()))
    }

    /**
     * Migrate legacy specId → specIds if needed.
     * Reads the raw JSON to check for the old single-specId field.
     */
    private fun migrate(project: Project): Project {
        if (project.expose != null && project.expose!!.specIds.isEmpty()) {
            // Check raw JSON for legacy specId field
            val file = dataDir.resolve("${project.id}.json")
            if (file.exists()) {
                val raw: Map<String, Any?> = mapper.readValue(file.toFile())
                val exposeRaw = raw["expose"] as? Map<*, *>
                val legacySpecId = exposeRaw?.get("specId") as? String
                if (legacySpecId != null) {
                    project.expose = ExposeConfig(
                        specIds = listOf(legacySpecId),
                        port = project.expose!!.port,
                        host = project.expose!!.host,
                    )
                    // Persist the migration
                    save(project)
                }
            }
        }
        return project
    }

    fun save(project: Project) {
        project.updatedAt = java.time.Instant.now().toString()
        mapper.writerWithDefaultPrettyPrinter()
            .writeValue(dataDir.resolve("${project.id}.json").toFile(), project)
    }

    fun delete(id: String): Boolean {
        val file = dataDir.resolve("$id.json")
        return Files.deleteIfExists(file)
    }
}

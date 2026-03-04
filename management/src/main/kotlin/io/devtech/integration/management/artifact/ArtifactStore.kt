package io.devtech.integration.management.artifact

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Metadata extracted from a stored integration JAR.
 *
 * JARs must contain `META-INF/integration.properties` with:
 * ```properties
 * integration.name=echo-service
 * integration.factory=io.devtech.integration.echo.EchoFactory
 * ```
 */
data class ArtifactMetadata(
    val id: String,
    val fileName: String,
    val integrationName: String,
    val factoryClass: String,
    val storedAt: Instant = Instant.now(),
)

/**
 * Stores integration JARs on the filesystem and reads their metadata.
 *
 * JARs are stored in `baseDir/artifacts/` with UUID-based filenames to avoid collisions.
 * Metadata is read from `META-INF/integration.properties` inside the JAR.
 */
class ArtifactStore(baseDir: File) {
    private val log = LoggerFactory.getLogger(ArtifactStore::class.java)
    private val artifactDir = File(baseDir, "artifacts").also { it.mkdirs() }
    private val metadata = ConcurrentHashMap<String, ArtifactMetadata>()

    init {
        // Scan existing artifacts on startup
        artifactDir.listFiles { f -> f.extension == "jar" }?.forEach { jarFile ->
            try {
                val props = readProperties(jarFile)
                if (props != null) {
                    val id = jarFile.nameWithoutExtension
                    val meta = ArtifactMetadata(
                        id = id,
                        fileName = jarFile.name,
                        integrationName = props.getProperty("integration.name", "unknown"),
                        factoryClass = props.getProperty("integration.factory", ""),
                        storedAt = Instant.ofEpochMilli(jarFile.lastModified()),
                    )
                    metadata[id] = meta
                    log.info("Found existing artifact: {} ({})", meta.integrationName, id)
                }
            } catch (e: Exception) {
                log.warn("Skipping invalid artifact {}: {}", jarFile.name, e.message)
            }
        }
    }

    /**
     * Store a JAR file and extract its metadata.
     * Returns metadata including the generated artifact ID.
     */
    fun store(fileName: String, bytes: ByteArray): ArtifactMetadata {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val targetFile = File(artifactDir, "$id.jar")
        targetFile.writeBytes(bytes)

        val props = readProperties(targetFile)
            ?: run {
                targetFile.delete()
                error("JAR missing META-INF/integration.properties: $fileName")
            }

        val meta = ArtifactMetadata(
            id = id,
            fileName = fileName,
            integrationName = props.getProperty("integration.name")
                ?: error("Missing integration.name in $fileName"),
            factoryClass = props.getProperty("integration.factory")
                ?: error("Missing integration.factory in $fileName"),
        )
        metadata[id] = meta
        log.info("Stored artifact: {} ({}) — {} bytes", meta.integrationName, id, bytes.size)
        return meta
    }

    fun getJarFile(id: String): File? {
        val file = File(artifactDir, "$id.jar")
        return if (file.exists()) file else null
    }

    fun getMetadata(id: String): ArtifactMetadata? = metadata[id]

    fun list(): List<ArtifactMetadata> = metadata.values.sortedByDescending { it.storedAt }

    fun delete(id: String): Boolean {
        val file = File(artifactDir, "$id.jar")
        val deleted = file.delete()
        metadata.remove(id)
        if (deleted) log.info("Deleted artifact: {}", id)
        return deleted
    }

    private fun readProperties(jarFile: File): Properties? {
        return JarFile(jarFile).use { jar ->
            val entry = jar.getJarEntry("META-INF/integration.properties") ?: return null
            Properties().apply { load(jar.getInputStream(entry)) }
        }
    }
}

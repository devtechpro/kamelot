package io.devtech.integration.management.artifact

import io.devtech.integration.factory.DeploymentContext
import io.devtech.integration.factory.IntegrationFactory
import io.devtech.integration.factory.IntegrationPackage
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Result of loading an integration from a JAR — the package plus resources
 * that need cleanup when the integration is undeployed.
 */
data class LoadedIntegration(
    val pkg: IntegrationPackage,
    val extractedDir: File,
    val classLoader: URLClassLoader,
)

/**
 * Loads an [IntegrationFactory] from a JAR file, extracts embedded resources
 * (spec files) to a temp directory, and invokes the factory.
 *
 * The extracted directory becomes the [DeploymentContext.baseDir], allowing
 * the factory to resolve spec paths via `ctx.specPath("specs/echo-openapi.yaml")`.
 */
object JarLoader {
    private val log = LoggerFactory.getLogger(JarLoader::class.java)

    /**
     * Load an integration from a JAR file.
     *
     * @param jarFile The JAR containing the factory class and spec resources
     * @param factoryClass Fully qualified class name of the [IntegrationFactory]
     * @param properties Runtime property overrides (port, URLs, secrets)
     * @return A [LoadedIntegration] with the created package and resources to clean up
     */
    fun load(
        jarFile: File,
        factoryClass: String,
        properties: Map<String, String> = emptyMap(),
    ): LoadedIntegration {
        log.info("Loading integration from {} (factory: {})", jarFile.name, factoryClass)

        // Extract JAR contents to a temp directory for spec file resolution
        val extractedDir = File.createTempFile("integration-", "-extracted").apply {
            delete()
            mkdirs()
        }
        extractJar(jarFile, extractedDir)
        log.debug("Extracted JAR to {}", extractedDir.absolutePath)

        // Create a classloader that includes both the JAR and extracted resources
        val classLoader = URLClassLoader(
            arrayOf(jarFile.toURI().toURL(), extractedDir.toURI().toURL()),
            Thread.currentThread().contextClassLoader,
        )

        try {
            // Load and instantiate the factory
            val factoryClazz = classLoader.loadClass(factoryClass)
            val factory = factoryClazz.getDeclaredConstructor().newInstance() as IntegrationFactory

            // Create the integration package
            val ctx = DeploymentContext(baseDir = extractedDir, properties = properties)
            val pkg = factory.create(ctx)

            log.info("Loaded integration '{}' from JAR (version {})", pkg.integration.name, pkg.integration.version)
            return LoadedIntegration(pkg = pkg, extractedDir = extractedDir, classLoader = classLoader)
        } catch (e: Exception) {
            // Clean up on failure
            cleanup(extractedDir, classLoader)
            throw e
        }
    }

    /**
     * Clean up resources from a loaded integration.
     * Call this when undeploying an integration that was loaded from a JAR.
     */
    fun cleanup(loaded: LoadedIntegration) {
        cleanup(loaded.extractedDir, loaded.classLoader)
    }

    private fun cleanup(extractedDir: File, classLoader: URLClassLoader) {
        try { classLoader.close() } catch (_: Exception) {}
        try { extractedDir.deleteRecursively() } catch (_: Exception) {}
    }

    private fun extractJar(jarFile: File, targetDir: File) {
        JarFile(jarFile).use { jar ->
            for (entry in jar.entries()) {
                if (entry.isDirectory) {
                    File(targetDir, entry.name).mkdirs()
                    continue
                }
                // Skip class files and manifest — we only need resource files (specs, configs)
                if (entry.name.endsWith(".class") || entry.name.startsWith("META-INF/MANIFEST")) continue

                val outFile = File(targetDir, entry.name)
                outFile.parentFile?.mkdirs()
                jar.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

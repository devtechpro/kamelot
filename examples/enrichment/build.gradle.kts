plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
}

application {
    mainClass.set("io.devtech.integration.enrichment.EnrichmentIntegrationKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    environment("INTEGRATION_DEBUG", System.getenv("INTEGRATION_DEBUG") ?: "false")
}

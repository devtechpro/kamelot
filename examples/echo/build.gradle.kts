plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
}

application {
    mainClass.set("io.devtech.integration.echo.EchoIntegrationKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Pass INTEGRATION_DEBUG env var through to the JVM process
    environment("INTEGRATION_DEBUG", System.getenv("INTEGRATION_DEBUG") ?: "false")
}

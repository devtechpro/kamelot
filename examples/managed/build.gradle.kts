plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    implementation(project(":management"))
    implementation(project(":examples:echo"))
}

application {
    mainClass.set("io.devtech.integration.managed.ManagedExampleKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    environment("INTEGRATION_DEBUG", System.getenv("INTEGRATION_DEBUG") ?: "false")
}

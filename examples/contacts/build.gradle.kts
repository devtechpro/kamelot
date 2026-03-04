plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    runtimeOnly("org.postgresql:postgresql:42.7.5")
}

application {
    mainClass.set("io.devtech.integration.contacts.ContactsIntegrationKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    environment("INTEGRATION_DEBUG", System.getenv("INTEGRATION_DEBUG") ?: "false")
}

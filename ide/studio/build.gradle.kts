plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":management"))

    // Jackson for JSON API
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    // OpenAPI spec parsing (implementation scope in :core, not transitive)
    implementation("io.swagger.parser.v3:swagger-parser:2.1.38")

    // Postgres driver for test-connection
    runtimeOnly("org.postgresql:postgresql:42.7.5")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.devtech.integration.studio.StudioAppKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}

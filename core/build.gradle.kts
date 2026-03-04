plugins {
    `java-library`
}

val camelVersion = "4.18.0"
val micrometerVersion = "1.14.4"

dependencies {
    api("org.apache.camel:camel-main:$camelVersion")
    api("org.apache.camel:camel-undertow:$camelVersion")
    api("org.apache.camel:camel-jackson:$camelVersion")
    api("org.apache.camel:camel-rest:$camelVersion")
    api("org.apache.camel:camel-micrometer:$camelVersion")
    api("org.apache.camel:camel-http:$camelVersion")
    api("org.apache.camel:camel-resilience4j:$camelVersion")
    api("org.apache.camel:camel-quartz:$camelVersion")
    api("org.apache.camel:camel-timer:$camelVersion")
    api("org.apache.camel:camel-json-validator:$camelVersion")
    api("org.apache.camel:camel-jdbc:$camelVersion")
    api("org.apache.camel:camel-sql:$camelVersion")
    api("com.zaxxer:HikariCP:6.2.1")

    implementation("io.swagger.parser.v3:swagger-parser:2.1.38")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    // Logging
    api("ch.qos.logback:logback-classic:1.5.16")

    // Metrics
    api("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    // Test
    testImplementation("org.apache.camel:camel-direct:$camelVersion")
    testImplementation("org.apache.camel:camel-log:$camelVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

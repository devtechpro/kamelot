plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("io.devtech.integration.ApplicationKt")
}

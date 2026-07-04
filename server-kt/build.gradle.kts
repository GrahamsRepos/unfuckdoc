plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

group = "com.unfuckdoc"
version = "0.1.0"

repositories { mavenCentral() }

val ktor = "3.0.3"

dependencies {
    // Ktor server (Netty) + JSON content negotiation
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")

    // Kotlin-optimised Guice DSL (brings Guice 7 transitively) + standard jakarta.inject annotations
    implementation("dev.misfitlabs.kotlinguice4:kotlin-guice:3.0.0")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

    // OpenSearch official Java client (+ Apache HttpClient5 transport)
    implementation("org.opensearch.client:opensearch-java:2.12.0")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // Neural embeddings for semantic field-name matching (DJL + MiniLM)
    implementation(platform("ai.djl:bom:0.30.0"))
    implementation("ai.djl:api")
    implementation("ai.djl.huggingface:tokenizers")
    runtimeOnly("ai.djl.pytorch:pytorch-engine")

    // CSV parsing + logging
    implementation("org.apache.commons:commons-csv:1.12.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor")
    testImplementation("io.mockk:mockk:1.13.13")
}

application {
    mainClass.set("com.unfuckdoc.ApplicationKt")
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }

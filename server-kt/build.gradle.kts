plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

group = "com.unfuckdoc"
version = "0.1.0"

repositories { mavenCentral() }

val ktor = "3.0.3"
val koin = "4.0.0"

dependencies {
    // Ktor server (Netty) + JSON content negotiation
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")

    // Koin DI (Ktor integration)
    implementation("io.insert-koin:koin-ktor:$koin")
    implementation("io.insert-koin:koin-logger-slf4j:$koin")

    // OpenSearch official Java client (+ Apache HttpClient5 transport)
    implementation("org.opensearch.client:opensearch-java:2.12.0")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // CSV parsing + logging
    implementation("org.apache.commons:commons-csv:1.12.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
}

application {
    mainClass.set("com.unfuckdoc.ApplicationKt")
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }

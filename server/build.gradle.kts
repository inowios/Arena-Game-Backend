plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
}

group = "com.arenacity"
version = "0.1.0"

repositories { mavenCentral() }

val ktor = "3.2.3"
val exposed = "0.61.0"
val koin = "4.1.0"

dependencies {
    implementation(project(":match-engine"))

    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktor")
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktor")
    implementation("io.ktor:ktor-server-request-validation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-client-core-jvm:$ktor")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor")

    implementation("org.jetbrains.exposed:exposed-core:$exposed")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.flywaydb:flyway-core:11.20.3")
    implementation("org.flywaydb:flyway-database-postgresql:11.20.3")

    implementation("io.insert-koin:koin-ktor:$koin")
    implementation("io.insert-koin:koin-logger-slf4j:$koin")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("com.arenacity.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=${project.ext.has("development")}")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    manifest { attributes("Implementation-Version" to project.version) }   // shown by GET /admin/stats
}

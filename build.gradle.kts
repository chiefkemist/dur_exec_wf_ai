plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.allopen") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:quarkus-camel-bom:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("org.apache.camel.quarkus:camel-quarkus-language")
    implementation("org.apache.camel.quarkus:camel-quarkus-core")
    implementation("org.apache.camel.quarkus:camel-quarkus-direct")
    implementation("org.apache.camel.quarkus:camel-quarkus-bean")
    implementation("org.apache.camel.quarkus:camel-quarkus-google-pubsub")
    implementation("org.apache.camel.quarkus:camel-quarkus-langchain4j-tools")
    implementation("org.apache.camel.quarkus:camel-quarkus-aws2-lambda")
//    implementation("org.apache.camel.quarkus:camel-quarkus-tika")
    implementation("org.apache.camel.quarkus:camel-quarkus-aws2-s3")
    implementation("org.apache.camel.quarkus:camel-quarkus-saga")
    implementation("org.apache.camel.quarkus:camel-quarkus-aws2-eventbridge")
    implementation("org.apache.camel.quarkus:camel-quarkus-rest-openapi")
    implementation("org.apache.camel.quarkus:camel-quarkus-aws-bedrock")
    implementation("org.apache.camel.quarkus:camel-quarkus-langchain4j-chat")
    implementation("org.apache.camel.quarkus:camel-quarkus-exec")
    implementation("org.apache.camel.quarkus:camel-quarkus-ssh")
    implementation("org.apache.camel.quarkus:camel-quarkus-aws2-iam")
    implementation("org.apache.camel.quarkus:camel-quarkus-aws2-ddb")
    implementation("org.apache.camel.quarkus:camel-quarkus-scheduler")
    implementation("org.apache.camel.quarkus:camel-quarkus-timer")
    implementation("org.apache.camel.quarkus:camel-quarkus-langchain4j-agent")
    implementation("org.apache.camel.quarkus:camel-quarkus-langchain4j-web-search")
    implementation("org.apache.camel.quarkus:camel-quarkus-ref")
    implementation("org.apache.camel.quarkus:camel-quarkus-mustache")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini:1.3.0")
    implementation("com.google.genai:google-genai:1.0.0")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Database dependencies for durable execution
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkiverse.jdbc:quarkus-jdbc-sqlite:3.0.11")

    // Scheduler for crash recovery checks
    implementation("io.quarkus:quarkus-scheduler")

    // Kotlin serialization for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Jackson for JSON serialization (already present but ensuring it's there)
    // implementation("io.quarkus:quarkus-rest-jackson") - already added above

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
}

group = "com.outcastgeek.ubntth"
version = "1.0.0-SNAPSHOT"

//java {
//    sourceCompatibility = JavaVersion.VERSION_21
//    targetCompatibility = JavaVersion.VERSION_21
//}
java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.withType<JavaExec> {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Configure Quarkus Dev mode JVM args for Java 24+
tasks.named<io.quarkus.gradle.tasks.QuarkusDev>("quarkusDev") {
    jvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

//kotlin {
//    compilerOptions {
//        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
//        javaParameters = true
//    }
//}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24
        javaParameters = true
    }
}

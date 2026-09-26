import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.spring") version "2.0.20"
    kotlin("plugin.jpa") version "2.0.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

group = "com.budgethunter"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Security: Force commons-lang3 to secure version (fixes CVE - Uncontrolled Recursion vulnerability)
    implementation("org.apache.commons:commons-lang3:3.18.0")

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")  // For Flux SSE support
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")  // Health checks & monitoring

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")  // Coroutines ↔ Reactor bridge

    // OpenAPI/Swagger Documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Rate Limiting (Token Bucket algorithm)
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Google ID token verification (Sign in with Google)
    implementation("com.google.api-client:google-api-client:2.9.1")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Database
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")  // For WebTestClient
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")  // For runTest
    testImplementation("io.projectreactor:reactor-test")  // For StepVerifier
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Quality gates run by CI (.github/workflows/ci.yml). Both linters start from a baseline so
// the existing code passes as-is; new code is held to the rules. Regenerate the baselines
// with `./gradlew ktlintGenerateBaseline detektBaseline` only when deliberately accepting debt.
ktlint {
    version.set("1.3.1")
    baseline.set(file("config/ktlint/baseline.xml"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
}

// detekt 1.23.7 is compiled against Kotlin 2.0.10 and refuses to run on anything else, but the
// Spring dependency-management plugin bumps every configuration to the project's Kotlin version.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.10")
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}

// Coverage floor enforced by `./gradlew check` (line coverage was ~86% when this was added).
kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
}

group = "com.budgethunter"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

// Boot 3.5.16 pins netty 4.1.135.Final, which is affected by GHSA-c4c3-7fpv-j4q5 (SNI routing
// bypass via a fragmented TLS ClientHello, fixed in 4.1.137.Final). Netty arrives through
// reactor-netty and terminates no TLS here - the app serves plain HTTP on Tomcat behind Nginx -
// but this is a patch-line bump, far cheaper than carrying an exception in the dependency review.
extra["netty.version"] = "4.1.138.Final"

// Boot 3.5.16 pins tomcat 10.1.55, affected by three critical advisories fixed in 10.1.58:
// GHSA-9xv2-5v5q-p794 (DIGEST auth capture-replay), GHSA-gcx9-497g-6cp6 (improper access
// control) and GHSA-h3x4-894j-xpx5 (FORM auth incorrect authorization).
extra["tomcat.version"] = "10.1.60"

// Boot 3.5.16 pins the PostgreSQL driver at 42.7.11, affected by GHSA-j92g-9f8w-j867: a server
// offering unsupported certificate algorithms silently downgrades channel-binding auth instead
// of failing. Fixed in 42.7.12. Production talks to PostgreSQL over SCRAM, so this is the one
// override here that touches a path the app actually uses.
extra["postgresql.version"] = "42.7.13"

repositories {
    mavenCentral()
}

dependencies {
    // Security: Force commons-lang3 to secure version (fixes CVE - Uncontrolled Recursion vulnerability)
    implementation("org.apache.commons:commons-lang3:3.21.0")

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // For Flux SSE support
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // Health checks & monitoring

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor") // Coroutines ↔ Reactor bridge

    // OpenAPI/Swagger Documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.1")

    // Rate Limiting (Token Bucket algorithm)
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Google ID token verification (Sign in with Google)
    implementation("com.google.api-client:google-api-client:2.9.1")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // Database
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // Schema migrations, applied by the app itself on startup under the production profile.
    // flyway-database-postgresql is not optional: since Flyway 10 the Postgres support lives
    // outside flyway-core, and Flyway refuses to run against Postgres without it.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux") // For WebTestClient
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test") // For runTest
    testImplementation("io.projectreactor:reactor-test") // For StepVerifier
}

// compilerOptions, not kotlinOptions: the latter is gone from Kotlin 2.1's Gradle plugin, which
// is why bumping any one Kotlin plugin on its own stopped the build script from compiling. This
// DSL has existed since 1.8, so it works on the current version too.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Quality gates run by CI (.github/workflows/ci.yml). ktlint runs with no baseline: with the
// style pinned in .editorconfig the tree already passes, so any new violation fails the build
// immediately. detekt still starts from one - its findings are design issues that need reading,
// not reformatting. Regenerate it with `./gradlew detektBaseline` only when accepting debt.
ktlint {
    version.set("1.3.1")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
}

// detekt refuses to run on any Kotlin but the one it was compiled against, while the Spring
// dependency-management plugin bumps every configuration to the project's Kotlin version - hence
// the pin. It has to move with detekt: 1.23.7 wanted 2.0.10, 1.23.8 wants 2.0.21. When a detekt
// bump fails with "compiled with Kotlin X but is currently running with Y", this is the line.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
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

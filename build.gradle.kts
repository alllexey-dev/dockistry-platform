plugins {
	kotlin("jvm") version "2.3.20"
	kotlin("plugin.spring") version "2.3.20"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.20"
}

group = "io.dockistry"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("com.auth0:java-jwt:4.5.0")

	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")

	implementation("com.github.slugify:slugify:4.0.0")
	implementation("com.ibm.icu:icu4j:78.3")
	implementation("com.github.docker-java:docker-java-core:3.7.1")
	implementation("com.github.docker-java:docker-java-transport-zerodep:3.7.1")

	constraints {
		implementation("org.bouncycastle:bcpkix-jdk18on:1.84") {
			because("Fixes CVE-2026-5588 in docker-java's transitive dependency")
		}
		implementation("org.bouncycastle:bcprov-jdk18on:1.84") {
			because("Fixes CVE-2026-0636 in docker-java's transitive dependency")
		}
		implementation("org.bouncycastle:bcutil-jdk18on:1.84") {
			because("Keeps the Bouncy Castle modules on the same release")
		}
	}

	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	jvmToolchain(21)

	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.test {
	useJUnitPlatform {
		excludeTags("benchmark", "docker-stats", "dind")
	}
}

tasks.register<Test>("dindTest") {
	description = "Runs Docker-in-Docker integration tests"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath

	useJUnitPlatform {
		includeTags("dind")
		excludeTags("docker-stats")
	}

	testLogging {
		events("passed", "skipped", "failed")
	}

	outputs.upToDateWhen { false }
}

tasks.register<Test>("dockerStatsTest") {
	description = "Runs Docker stats integration tests"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath

	useJUnitPlatform {
		includeTags("docker-stats")
	}

	testLogging {
		showStandardStreams = true
		events("passed", "skipped", "failed", "standardOut", "standardError")
	}

	outputs.upToDateWhen { false }
}

tasks.register<Test>("benchmarkTest") {
	description = "Runs benchmark-like tests"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath

	useJUnitPlatform {
		includeTags("benchmark")
	}

	testLogging {
		showStandardStreams = true
		events("passed", "skipped", "failed", "standardOut", "standardError")
	}

	// Useful for benchmarks, otherwise Gradle may say UP-TO-DATE and not run it.
	outputs.upToDateWhen { false }
}

plugins {
	java
	id("org.springframework.boot") version "4.0.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.resume"
version = "0.0.1-SNAPSHOT"
description = "Asian Game Transportation Simplified"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.redisson:redisson:3.40.2")
	
	// ============================================
	// Monitoring: Prometheus + Micrometer
	// ============================================
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	
	// ============================================
	// Database
	// ============================================
	runtimeOnly("com.mysql:mysql-connector-j")          // MySQL
	runtimeOnly("com.h2database:h2")                    // H2 (테스트용)
	
	// ============================================
	// Test
	// ============================================
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("com.github.codemonstur:embedded-redis:1.4.3")
	testImplementation("org.testcontainers:testcontainers:1.19.3")
	testImplementation("org.testcontainers:mysql:1.19.3")
	testImplementation("org.testcontainers:junit-jupiter:1.19.3")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
	testLogging {
		events("passed", "skipped", "failed", "standardOut", "standardError")
		showStandardStreams = true
	}

	// 테스트 JVM 메모리 제한 (시스템 프로퍼티로 조절 가능)
	val testMaxHeap = System.getProperty("test.maxHeap") ?: "2g"
	maxHeapSize = testMaxHeap
}

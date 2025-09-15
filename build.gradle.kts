plugins {
	java
	id("org.springframework.boot") version "3.5.5"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "de.localyhost"
version = "0.0.1-SNAPSHOT"
description = "Localyhost"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
    implementation("org.jetbrains:annotations:24.0.1")
    implementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("javax.servlet:javax.servlet-api:4.0.1")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

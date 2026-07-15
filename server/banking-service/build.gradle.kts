plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(libs.org.springframework.boot.spring.boot.starter.web)
    implementation(libs.org.springframework.boot.spring.boot.starter.data.jpa)
    implementation(libs.org.springframework.boot.spring.boot.starter.actuator)
    implementation(libs.io.micrometer.micrometer.registry.prometheus)
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.com.h2database.h2)
    runtimeOnly(libs.org.postgresql.postgresql)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
}

description = "banking-service"

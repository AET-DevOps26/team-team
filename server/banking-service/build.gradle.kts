plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(libs.org.springframework.boot.spring.boot.starter.web)
    implementation(libs.org.springframework.boot.spring.boot.starter.data.jpa)
    implementation(libs.org.springframework.boot.spring.boot.starter.actuator)
    implementation(libs.io.micrometer.micrometer.registry.prometheus)
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    runtimeOnly(libs.org.postgresql.postgresql)
    testImplementation(libs.org.springframework.boot.spring.boot.starter.test)
}

description = "banking-service"

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // bcprov 1.81 은 CVE-2025-14813(CRITICAL). spring-cloud-starter 5.0.0 이 전이로 끌어오며,
    // spring-cloud 를 쓰는 모듈은 이 서비스뿐이다. Boot BOM 밖이라 Boot 상향으로는 해소되지 않는다.
    // 실측: run 31611508158 의 `Trivy image scan (CRITICAL gate)` 가 settlement-gateway 이미지에서
    // 이 CVE 하나로 실패했다(이미지 스캔은 .trivyignore.yaml 을 쓰지 않는다).
    // constraint 를 쓰는 이유: 직접 의존을 새로 추가하지 않고 전이 버전만 끌어올린다.
    constraints {
        implementation("org.bouncycastle:bcprov-jdk18on:1.84") {
            because("CVE-2025-14813 (CRITICAL) — spring-cloud-starter 경유 1.81 을 수정판으로 올린다")
        }
    }

    // Spring Cloud Gateway (Reactive)
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Prometheus
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

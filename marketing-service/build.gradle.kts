plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ marketing-service 는 자체 DB(lemuel_marketing) 를 소유하는 DB-per-service 다.
//   담는 것: 이벤트 프로모션 운영 도구 — 출석체크(연속/누적/매일 + 평일/주말 규칙)와 럭키박스(확률 추첨).
//   담지 않는 것: 포인트 원장. 보상은 lemuel.marketing.reward_requested 로 **요청만** 내고
//   적립은 order-service 의 포인트 원장이 수행한다 (README 불변식 — 서비스 간 연계는 Kafka 만).

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")   // 버전드 내부 라이브러리(composite build 로 로컬 치환)
    testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))   // 아키텍처 가드 픽스처

    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Flyway — 자체 DB(lemuel_marketing, schema=marketing) 마이그레이션을 직접 책임진다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka — 발행(outbox → lemuel.marketing.reward_requested) + 구독(lemuel.point.granted 로 보상 확정).
    // app.kafka.enabled=true 일 때만 컨슈머·인프라 빈 활성 (shared-common KafkaConfig 조건부).
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Caffeine
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Prometheus
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql:42.7.3")

    // dotenv
    implementation("io.github.cdimascio:java-dotenv:5.2.2")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Test
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core")
    // ArchUnit 1.4.x 부터 Java 25 클래스 파싱 지원 (1.3.0 은 실패)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}

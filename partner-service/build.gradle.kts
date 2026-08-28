plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ partner-service 는 자체 DB(lemuel_partner) 를 소유하는 DB-per-service 다.
//   담는 것: 제휴 기업(입점 조직)이 "우리 몰에서 자기가 판 것"을 보는 백오피스의 읽기모델.
//   담지 않는 것: 주문·결제·상품 원본. 이 서비스는 원본을 하나도 갖지 않는다 — 전부
//   Kafka 이벤트로 받아 자기 프로젝션을 쌓고, 그 프로젝션만 조회한다.
//
//   그래서 발행 토픽이 0 이다. outbox 인프라가 그대로 배선되는 것은 shared-common 을 루트
//   스캔하기 때문이고(폴러가 빠지면 outbox-poller-gate 가 FAIL), 폴링은 빈 테이블을 훑는다.
//   나중에 이 서비스가 무언가를 발행하게 되면 그때 배선이 이미 되어 있다.

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

    // Flyway — 자체 DB(lemuel_partner, schema=partner) 마이그레이션을 직접 책임진다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka — 구독 전용. 발행은 0 이다(위 주석 참조).
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

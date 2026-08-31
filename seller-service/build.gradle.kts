plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ seller-service 는 자체 DB(lemuel_seller) 를 소유하는 DB-per-service 다.
//   담는 것: 셀러가 직접 쓰는 **상품 등록 신청서**(원본) 와 송장 등록 요청(원본), 그리고 그
//   신청서를 심사·출고 화면에 붙이기 위한 조직·주문·상품 프로젝션(사본).
//
//   partner-service 와 다른 점이 여기다. 파트너 콘솔은 원본이 0 이라 발행 토픽도 0 이었지만,
//   셀러 백오피스에는 원본이 있고 그 원본이 다른 서비스의 상태를 바꿔야 한다:
//     · 승인된 신청서 → 카탈로그 상품 등록(order-service 소유)
//     · 등록한 송장   → 배송 출고 처리(order-service shipping 슬라이스 소유)
//   둘 다 여기서 직접 쓰지 않는다. 남의 원장에 직접 쓰는 순간 DB-per-service 는 이름만 남는다.
//   대신 lemuel.seller.product_approved / lemuel.seller.shipment_registered 로 **요청**을 내고,
//   order-service 가 그 요청을 받아 자기 원장에 쓴다(marketing → order 포인트 지급과 같은 형태).

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

    // Flyway — 자체 DB(lemuel_seller, schema=seller) 마이그레이션을 직접 책임진다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka — 구독(사본 갱신) + 발행(승인·출고 요청) 양방향.
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

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")   // 버전드 내부 라이브러리(composite build 로 로컬 치환)
    testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))   // 이벤트 계약 스키마·검증기·정본 샘플 (ADR 0024)
    // 커머스 코어 전용 모듈 — 다른 서비스 코드를 번들하지 않는다(경계는 Kafka 이벤트뿐).

    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka (PaymentCaptured/RefundCompleted publish)
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // QueryDSL
    implementation("com.querydsl:querydsl-jpa:5.0.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.0.0:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    // iText (refund 영수증 등)
    implementation("com.itextpdf:itext-core:8.0.5")
    implementation("com.itextpdf:font-asian:8.0.5")

    // Caffeine
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Redis — cart.store=redis 일 때 장바구니 어댑터 백엔드 (Lettuce, 연결 lazy)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Resilience4j (Toss PG)
    // spring-boot3 가 아니라 spring-boot4 다. 이 프로젝트는 Boot 4 인데 여태 Boot3 전용 모듈을
    // 쓰고 있었고, 2.4.0 부터 들어간 SpringBoot3Verifier 가 그걸 기동 시점에 예외로 막는다
    // ("Module ...resilience4j-spring-boot3 is only compatible with Spring Boot 3.x").
    // 2.3.0 이하엔 그 검증기가 없어서 조용히 굴러갔을 뿐, 조합 자체가 원래 틀렸다.
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    // Rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")

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

    // MapStruct
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    // Test
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
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

val querydslDir = layout.buildDirectory.dir("generated/querydsl")

tasks.withType<JavaCompile>().configureEach {
    options.generatedSourceOutputDirectory.set(querydslDir.get().asFile)
}

tasks.named("clean") {
    doLast {
        delete(querydslDir)
    }
}

sourceSets {
    named("main") {
        java.srcDir(querydslDir)
    }
}

// src/main/resources/fashion-copilot 은 제출물 플러그인(스킬·MCP)으로,
// 서비스 런타임이 읽는 리소스가 아니다. 배포 jar 에 실리지 않도록 리소스 처리에서 제외한다.
tasks.named<ProcessResources>("processResources") {
    exclude("fashion-copilot/**")
}

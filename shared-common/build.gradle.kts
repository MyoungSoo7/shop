// 독립 빌드(composite/polyrepo): 루트 subprojects {} 설정을 더 이상 상속받지 않으므로
// toolchain·repositories·dependency-management·jacoco 를 자체 선언한다.
plugins {
    `java-library`
    `java-test-fixtures`   // 이벤트 계약(contract-as-code) 스키마·검증기·정본 샘플 제공 (ADR 0024)
    `maven-publish`
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

// -parameters: 메서드 파라미터 이름을 바이트코드에 보존한다 — 루트 build.gradle.kts 의 subprojects 설정과 동일하게.
// shared-common 은 독립 빌드라 그 설정을 상속받지 않아 그동안 빠져 있었다. 그래서 이 모듈의 @Bean 메서드는
// "파라미터명 = 빈 이름" 폴백을 쓸 수 없었고, 같은 타입 빈이 둘 이상인 컨텍스트(서비스가 자체 KafkaTemplate 을
// 추가 정의하는 경우 등)에서 주입이 모호해져 기동이 깨진다 — 실제로 settlement DLQ E2E 컨텍스트 로드가
// 이 이유로 실패했다. (Spring Data 의 @Query 이름 파라미터 추론에도 같은 이유로 필요 — 루트 주석 참조.)
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

group = "github.lms.lemuel"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

dependencies {
    // Spring Boot 의 BOM 을 사용하기 위해 dependency-management 만 적용 (boot plugin 자체는 X — 라이브러리 모듈)
    // ⚠ 루트 build.gradle.kts 의 org.springframework.boot 플러그인 버전과 같은 값을 유지할 것 —
    //   composite build 로 로컬 치환되므로 여기만 뒤처지면 서비스마다 다른 BOM 이 섞인다.
    api(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))

    // Spring 코어
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver")
    api("org.springframework:spring-context")
    api("org.springframework:spring-web")
    api("org.springframework:spring-tx")
    api("jakarta.servlet:jakarta.servlet-api")
    api("jakarta.validation:jakarta.validation-api")
    api("jakarta.persistence:jakarta.persistence-api")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.slf4j:slf4j-api")

    // JPA (audit, outbox, processed-events 가 JpaEntity 사용)
    api("org.springframework.data:spring-data-jpa")
    api("org.hibernate.orm:hibernate-core")

    // Kafka (outbox publisher 용)
    api("org.springframework.kafka:spring-kafka")

    // JWT (양 서비스 + gateway 공통)
    api("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // 구조화 로그
    api("net.logstash.logback:logstash-logback-encoder:7.4")

    // Rate limiting
    api("com.bucket4j:bucket4j-core:8.10.1")

    // PDF (common/pdf 가 iText 사용 시)
    api("com.itextpdf:itext-core:8.0.5")
    api("com.itextpdf:font-asian:8.0.5")

    // SpringDoc (OpenApiConfig 등에서 사용)
    api("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Spring Security (SecurityConfig 등)
    api("org.springframework.security:spring-security-config")
    api("org.springframework.security:spring-security-web")
    api("org.springframework.security:spring-security-crypto")

    // Micrometer (outbox publisher 메트릭 등)
    api("io.micrometer:micrometer-core")

    // 분산 트레이싱 — Micrometer Tracing + OpenTelemetry Bridge.
    // OutboxEvent 가 traceparent 를 영속화해 비동기 경계에서도 trace context 가 끊기지 않게 한다.
    api("io.micrometer:micrometer-tracing")
    api("io.micrometer:micrometer-tracing-bridge-otel")
    api("io.opentelemetry:opentelemetry-exporter-otlp")

    // Spring Boot autoconfigure (ConditionalOnProperty 등 어노테이션 사용)
    api("org.springframework.boot:spring-boot-autoconfigure")

    // Cache (Caffeine)
    api("org.springframework:spring-context-support")
    api("com.github.ben-manes.caffeine:caffeine")

    // Resilience4j CircuitBreaker — 2-tier 캐시 L2(Redis) 호출 보호.
    // 장애 지속 시 회로를 OPEN 해 L2 호출 자체를 차단(타임아웃 대기 0)하고 즉시 L1/DB 로 폴백한다.
    // order-service 의 resilience4j-spring-boot4 와 같은 라인(2.4.0)으로 맞춘다 — 코어와
    // 스타터가 갈리면 registry/config 클래스가 어긋난다.
    api("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")

    // HikariCP — read-replica 라우팅 데이터소스(ReadReplicaDataSourceConfig)의 컴파일 전용 타입.
    // 런타임 구현은 각 앱 모듈의 spring-boot-starter-data-jpa 가 전이 제공한다.
    compileOnly("com.zaxxer:HikariCP")

    // Spring Data Redis — 2-tier 캐시(TwoTierCacheConfig) L2 의 컴파일 전용 타입.
    // 런타임은 redis 를 쓰는 모듈(order-service: spring-boot-starter-data-redis)이 전이 제공하고,
    // 2-tier 는 app.cache.two-tier.enabled=true + Redis 클래스패스 존재 시에만 활성화된다.
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")

    // ShedLock — @Scheduled 의 분산 락 (replicas N 개 중 1 개만 실행 보장)
    api("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    api("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

    // QueryDSL (config bean)
    api("com.querydsl:querydsl-jpa:5.0.0:jakarta")

    // Lombok (JDK 25 지원 — 1.18.40+ 필요)
    compileOnly("org.projectlombok:lombok:1.18.40")
    annotationProcessor("org.projectlombok:lombok:1.18.40")
    testCompileOnly("org.projectlombok:lombok:1.18.40")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.40")

    // 이벤트 계약(contract-as-code) — 소비 서비스가 testFixtures 좌표로 스키마·검증기·샘플을 공유 (ADR 0024)
    // 아키텍처 가드 픽스처(InboundPortReachability)용 — 소비 서비스가 이미 archunit 을 test 클래스패스에
    // 갖고 있으므로 compileOnly 로 두어 픽스처 소비자에게 버전을 강제하지 않는다.
    testFixturesCompileOnly("com.tngtech.archunit:archunit-junit5:1.4.1")
    testFixturesImplementation("com.networknt:json-schema-validator:1.5.6")
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // 2-tier 캐시 L2 통합 테스트용 — compileOnly 인 redis 를 테스트 런타임에 올린다.
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.postgresql:postgresql:42.7.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── 발행 (버전드 내부 라이브러리) ───────────────────────────────────────────────
// 로컬 개발: ./gradlew -p shared-common publishToMavenLocal (별도 repo 불필요).
// 원격(GitHub Packages): GITHUB_ACTOR / GITHUB_TOKEN 환경변수가 있을 때만 활성화 → ./gradlew -p shared-common publish
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])   // java-library → compile/runtime + sourcesJar 포함
        }
    }
    repositories {
        val ghActor = System.getenv("GITHUB_ACTOR")
        val ghToken = System.getenv("GITHUB_TOKEN")
        if (ghActor != null && ghToken != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/MyoungSoo7/settlement")
                credentials {
                    username = ghActor
                    password = ghToken
                }
            }
        }
    }
}

// ── 커버리지 게이트 ─────────────────────────────────────────────────────────────
// 루트 subprojects 에서 강제하던 common.outbox.domain INSTRUCTION 80% 게이트를 이관(라이브러리 자체 책임).
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

// 루트 build.gradle.kts 와 같은 스모크. shared-common 은 composite(included) build 라
// 루트의 subprojects 설정을 전혀 받지 않으므로 정의부터 따로 가져야 한다.
// 취지는 루트와 동일: 측정 대상이 0개면 리포트는 클래스 0개, LINE 90% 검증은 "위반 없음"으로
// 조용히 통과한다. classDirectories 는 설정 시점 즉시 평가라 확인은 실행 시점이어야 한다.
fun requireNonEmptyCoverageScope(taskName: String, classDirectories: FileCollection) {
    val measured = classDirectories.asFileTree.matching { include("**/*.class") }.files.size
    require(measured > 0) {
        "$taskName 의 커버리지 측정 대상이 0개다 — 게이트가 공전한다. " +
            "classDirectories 를 겹쳐 설정하지 않았는지, 제외 패턴이 모듈 전체를 " +
            "삼키지 않았는지 확인할 것."
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    doFirst { requireNonEmptyCoverageScope("jacocoTestReport", classDirectories) }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    // common.pdf 는 Ghostscript 외부 바이너리 래퍼 — 루트 게이트가 adapter/out/pdf/** 를 제외하는
    // 것과 같은 이유(바이너리 없는 환경에서 측정 불가)로 LINE 게이트에서 제외한다.
    classDirectories.setFrom(classDirectories.files.map { dir ->
        fileTree(dir) { exclude("**/common/pdf/**") }
    })
    violationRules {
        // 서비스 모듈과 동일한 번들 게이트 (루트 build.gradle.kts 의 LINE 90% 와 정합)
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("github.lms.lemuel.common.outbox.domain.*")
            limit {
                counter = "INSTRUCTION"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
    doFirst { requireNonEmptyCoverageScope("jacocoTestCoverageVerification", classDirectories) }
}

tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
